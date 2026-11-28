package app.nalssi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from this app's GitHub Releases (repo is baked in at build time).
 * - In the app: a dialog offers the update, then Android's own install prompt confirms it.
 * - In the background (on widget refresh, at most every 6 h): tries a silent install where the
 *   phone allows it. Samsung blocks silent self-updates ("Self update is blocked…"); after the
 *   first such refusal the background path switches itself off and the in-app prompt is used.
 */
object Updater {

    data class Release(val code: Int, val name: String, val url: String, val notes: String)

    private const val PREFS = "nalssi_update"
    private const val BACKGROUND_INTERVAL = 6 * 60 * 60 * 1000L

    /** Blocking. Returns a release newer than this build, or null. */
    fun check(): Release? {
        if (BuildConfig.UPDATE_REPO.isBlank()) return null
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "nalssi-app")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= BuildConfig.VERSION_CODE) return null
            val assets = j.optJSONArray("assets") ?: return null
            // prefer nalssi.apk (the self-updating build); any other .apk only as a fallback
            val order = (0 until assets.length()).sortedBy { if (assets.getJSONObject(it).optString("name") == "nalssi.apk") 0 else 1 }
            for (i in order) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk") && !a.optString("name").contains("store")) {
                    return Release(code, j.optString("name", tag), a.getString("browser_download_url"), j.optString("body", ""))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Blocking. Downloads the APK and hands it to Android's package installer. */
    fun install(ctx: Context, r: Release, silent: Boolean) {
        val file = File(ctx.cacheDir, "update.apk")
        download(r.url, file)

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(
                if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            )
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("nalssi.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val result = Intent(ctx, InstallReceiver::class.java).putExtra(InstallReceiver.EXTRA_SILENT, silent)
            val status = PendingIntent.getBroadcast(ctx, id, result, flags)
            session.commit(status.intentSender)
        }
    }

    /** Called from the widget refresh thread. Silent only; never interrupts the user. */
    fun maybeUpdateInBackground(ctx: Context) {
        if (BuildConfig.UPDATE_REPO.isBlank()) return      // Galaxy Store build: the store updates the app
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (prefs.getBoolean("silent_blocked", false)) return
        if (now - prefs.getLong("last", 0L) < BACKGROUND_INTERVAL) return
        prefs.edit().putLong("last", now).apply()
        if (!ctx.packageManager.canRequestPackageInstalls()) return
        val installedBy = try {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } catch (e: Exception) {
            null
        }
        if (installedBy != ctx.packageName) return // silent install not allowed yet
        try {
            val r = check() ?: return
            install(ctx, r, silent = true)
        } catch (e: Exception) {
            // try again next time
        }
    }

    /** Remember that this phone refuses silent self-updates. */
    fun markSilentBlocked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("silent_blocked", true).apply()
    }

    private fun download(url: String, to: File) {
        var u = URL(url)
        var redirects = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "nalssi-app")
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                val next = c.getHeaderField("Location")
                c.disconnect()
                if (next == null) throw IOException("redirect without location")
                u = URL(u, next)
                redirects++
                continue
            }
            try {
                if (code != 200) throw IOException("HTTP $code")
                c.inputStream.use { input -> to.outputStream().use { out -> input.copyTo(out) } }
            } finally {
                c.disconnect()
            }
            return
        }
    }
}

/** Receives the installer's result. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val silent = intent.getBooleanExtra(EXTRA_SILENT, false)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a tap to confirm; only show it while the app is open.
                if (!MainActivity.inForeground) return
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(confirm)
                } catch (e: Exception) {
                    // ignore; the app offers the update again next time
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> if (silent) {
                // Background attempt refused (e.g. Samsung): stop trying silently; the app will ask instead.
                Updater.markSilentBlocked(ctx)
            } else if (MainActivity.inForeground) {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "업데이트 실패 $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_SILENT = "silent"
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
