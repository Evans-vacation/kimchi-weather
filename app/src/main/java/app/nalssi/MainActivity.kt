package app.nalssi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

/** The app screen is the bundled web weather app (assets/index.html) in a WebView. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String = ""
    private var updateChecked = false
    private var pendingRelease: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val root = FrameLayout(this)
        root.setBackgroundResource(R.drawable.ic_launcher_bg)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Android 15+ draws edge-to-edge: keep the page clear of the status and navigation bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.addJavascriptInterface(Bridge(), "Android")

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ),
                        REQ_LOCATION
                    )
                }
            }
        }

        // Links (e.g. the Open-Meteo credit) open in the browser.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        if (savedInstanceState == null) {
            val html = assets.open("index.html").bufferedReader().use { it.readText() }
            web.loadDataWithBaseURL("https://nalssi.app/", html, "text/html", "utf-8", null)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            geoCallback?.invoke(geoOrigin, ok, false)
            geoCallback = null
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        val waiting = pendingRelease
        if (waiting != null && packageManager.canRequestPackageInstalls()) {
            pendingRelease = null
            startUpdate(waiting)
        } else if (!updateChecked) {
            updateChecked = true
            Thread {
                val r = try { Updater.check() } catch (e: Exception) { null }
                if (r != null) runOnUiThread { if (!isFinishing && !isDestroyed) offerUpdate(r) }
            }.start()
        }
    }

    private fun offerUpdate(r: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("새 버전이 있어요")
            .setMessage(r.name + (if (r.notes.isNotBlank()) "\n\n" + r.notes else ""))
            .setPositiveButton("업데이트") { _, _ -> startUpdate(r) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun startUpdate(r: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            // First time only: Android needs "이 출처 허용" for this app.
            pendingRelease = r
            Toast.makeText(this, "'이 출처 허용'을 켠 뒤 뒤로 돌아오세요", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                pendingRelease = null
            }
            return
        }
        Toast.makeText(this, "새 버전을 내려받는 중…", Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        Thread {
            try {
                Updater.install(ctx, r)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(ctx, "업데이트를 내려받지 못했어요", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        // Refresh widgets with whatever city the user ended on.
        val ctx = applicationContext
        Thread { WidgetUpdater.refreshAll(ctx) }.start()
    }

    /** Called from the page: window.Android.setPlace(JSON.stringify(place)). */
    inner class Bridge {
        @JavascriptInterface
        fun setPlace(json: String) {
            try {
                val o = JSONObject(json)
                Prefs.save(
                    applicationContext,
                    Place(o.optString("name", "현재 위치"), o.getDouble("lat"), o.getDouble("lon"))
                )
            } catch (e: Exception) {
                // ignore malformed input
            }
        }
    }

    companion object {
        private const val REQ_LOCATION = 1

        @Volatile
        var inForeground = false
    }
}
