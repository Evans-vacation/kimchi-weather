package app.nalssi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The app screen is the bundled web weather app (assets/index.html) in a WebView. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String = ""
    private var updateChecked = false
    private var insetTop = 0
    private var insetBottom = 0

    private fun pushInsets() {
        if (!::web.isInitialized) return
        val d = resources.displayMetrics.density
        val js = "document.documentElement.style.setProperty('--sat','${(insetTop / d).toInt()}px');" +
            "document.documentElement.style.setProperty('--sab','${(insetBottom / d).toInt()}px');" +
            "window.layoutScene && window.layoutScene();"
        web.post { web.evaluateJavascript(js, null) }
    }
    private var pendingRelease: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val root = FrameLayout(this)
        root.setBackgroundResource(R.drawable.ic_launcher_bg)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Edge-to-edge: the page (and its landscape) runs under the status and navigation bars;
        // the page pads its own content with --sat / --sab so nothing sits under them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, 0, bars.right, 0)
                insetTop = bars.top
                insetBottom = bars.bottom
                pushInsets()
                insets
            }
        }

        Notifier.channels(this)
        Notifier.schedule(applicationContext)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.addJavascriptInterface(Bridge(), "Android")
        // The page handles long-press itself (hold 2 s to reorder cards): no text selection or system buzz.
        web.setOnLongClickListener { true }
        web.isLongClickable = false
        web.isHapticFeedbackEnabled = false

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
            override fun onPageFinished(view: WebView, url: String?) {
                pushInsets()
            }

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
                Updater.install(ctx, r, silent = false)
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
        fun getNotify(): String = Notifier.settings(applicationContext).toString()

        @JavascriptInterface
        fun setNotify(json: String) {
            try { Notifier.save(applicationContext, json) } catch (e: Exception) { return }
            val o = try { JSONObject(json) } catch (e: Exception) { return }
            if ((o.optBoolean("rain") || o.optBoolean("brief")) && !Notifier.canPost(applicationContext) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
            }
        }

        /** "미리보기" buttons: post the text the page already built, right now. */
        @JavascriptInterface
        fun testNotify(kind: String, json: String) {
            val ctx = applicationContext
            if (!Notifier.canPost(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
                return
            }
            val o = try { JSONObject(json) } catch (e: Exception) { return }
            Notifier.post(
                ctx,
                if (kind == "rain") Notifier.CH_RAIN else Notifier.CH_BRIEF,
                if (kind == "rain") 201 else 202,
                o.optString("title"), o.optString("body")
            )
        }

        @JavascriptInterface
        fun haptic() {
            web.post {
                @Suppress("DEPRECATION")
                web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            }
        }

        /** City / 동 name → places, with the phone's geocoder. Async: window.__geoDone(id, jsonArray|null). */
        @JavascriptInterface
        fun searchPlace(q: String, id: String) {
            Thread {
                val result = try { lookupName(q) } catch (e: Exception) { null }
                val payload = if (result == null) "null" else JSONObject.quote(result.toString())
                val js = "window.__geoDone && window.__geoDone(${JSONObject.quote(id)}, $payload)"
                web.post { web.evaluateJavascript(js, null) }
            }.start()
        }

        /** Async: answers with window.__geoDone(id, json|null). */
        @JavascriptInterface
        fun reverseGeocode(lat: Double, lon: Double, id: String) {
            Thread {
                val result = try { lookupDong(lat, lon) } catch (e: Exception) { null }
                val payload = if (result == null) "null" else JSONObject.quote(result.toString())
                val js = "window.__geoDone && window.__geoDone(${JSONObject.quote(id)}, $payload)"
                web.post { web.evaluateJavascript(js, null) }
            }.start()
        }

        @JavascriptInterface
        fun setPlace(json: String) {
            try {
                val o = JSONObject(json)
                Prefs.save(
                    applicationContext,
                    Place(o.optString("name", "현재 위치"), o.getDouble("lat"), o.getDouble("lon"))
                )
                Prefs.saveTheme(applicationContext, o.optString("theme", "city"))
            } catch (e: Exception) {
                // ignore malformed input
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun lookupName(q: String): JSONArray? {
        if (!Geocoder.isPresent()) return null
        val list = Geocoder(this, Locale.KOREA).getFromLocationName(q, 5) ?: return null
        val out = JSONArray()
        for (a in list) {
            if (!a.hasLatitude() || !a.hasLongitude()) continue
            val name = listOfNotNull(a.featureName, a.thoroughfare, a.subLocality, a.locality, a.adminArea)
                .firstOrNull { it.isNotBlank() && !it.all { ch -> ch.isDigit() || ch == '-' } } ?: q
            val admin = listOfNotNull(a.subLocality, a.locality, a.adminArea).firstOrNull { it.isNotBlank() && it != name } ?: ""
            out.put(
                JSONObject().put("name", name).put("admin", admin).put("country", a.countryName ?: "")
                    .put("lat", a.latitude).put("lon", a.longitude)
            )
        }
        return out
    }

    /** GPS → 동 이름 with the phone's built-in geocoder. */
    @Suppress("DEPRECATION")
    private fun lookupDong(lat: Double, lon: Double): JSONObject? {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(this, Locale.KOREA).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        val tokens = (a.getAddressLine(0) ?: "").split(" ").filter { it.isNotBlank() }
        val dongRe = Regex("^[가-힣0-9·.]+(동|읍|면|가)$")
        val dong = listOfNotNull(a.thoroughfare, a.subLocality, a.locality).firstOrNull { dongRe.matches(it) }
            ?: tokens.firstOrNull { dongRe.matches(it) && it != "대한민국" }
            ?: ""
        val gu = tokens.lastOrNull { it.endsWith("구") || it.endsWith("군") } ?: a.subLocality ?: ""
        val city = tokens.firstOrNull { it.endsWith("시") || it.endsWith("도") } ?: a.adminArea ?: ""
        if (dong.isEmpty() && gu.isEmpty()) return null
        return JSONObject().put("dong", dong).put("gu", gu).put("city", city)
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val REQ_NOTIFY = 2

        @Volatile
        var inForeground = false
    }
}
