package fr.micolomike.cadran

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    @Volatile
    private var deniedForGood = false

    @Volatile
    private var fullscreen = false

    private val prefs by lazy { getSharedPreferences("cadran", Context.MODE_PRIVATE) }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            prefs.edit().putBoolean("asked", true).apply()
            val fine = hasFine()
            deniedForGood = !fine && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            if (fine) startTracking()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val ground = Color.parseColor("#071A1F")
        val root = FrameLayout(this)
        root.setBackgroundColor(ground)
        web = WebView(this)
        web.setBackgroundColor(ground)
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        // Keep the page clear of the status bar, the navigation bar and the camera cutout.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // The interface ships inside the APK and is served from a local https origin.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(Bridge(), "CadranNative")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__cadranBack ? window.__cadranBack() : false)") { result ->
                    // Nothing left to close: keep Cadran running in the background instead of quitting.
                    if (result != "true") moveTaskToBack(true)
                }
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (!hasFine() && !prefs.getBoolean("asked", false)) askForLocation()
    }

    override fun onStart() {
        super.onStart()
        if (hasFine()) startTracking()
    }

    override fun onStop() {
        super.onStop()
        if (!TripState.tripRunning) stopService(Intent(this, TrackingService::class.java))
    }

    override fun onDestroy() {
        if (isFinishing && !TripState.tripRunning) stopService(Intent(this, TrackingService::class.java))
        web.destroy()
        super.onDestroy()
    }

    private fun hasFine(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCoarse(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun askForLocation() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        askPermissions.launch(perms.toTypedArray())
    }

    /** Starts the GPS service, or refreshes its notification when it already runs. */
    private fun startTracking() {
        if (!hasFine()) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        } catch (e: Exception) {
            // Refused by the system (app not in front): the page shows "Signal perdu" until it is.
        }
    }

    private fun permissionState(): String {
        if (hasFine()) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "granted" else "gpsoff"
        }
        if (hasCoarse()) return "coarse"
        return if (deniedForGood) "denied" else "prompt"
    }

    private fun shareFile(name: String, content: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "export.csv" }
        val dir = File(cacheDir, "exports")
        dir.mkdirs()
        val file = File(dir, safe)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Exporter les trajets"))
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Methods the page calls as window.CadranNative.*  (they run off the main thread). */
    inner class Bridge {
        @JavascriptInterface
        fun permission(): String = permissionState()

        @JavascriptInterface
        fun requestPermission() {
            runOnUiThread { askForLocation() }
        }

        @JavascriptInterface
        fun openAppSettings() {
            runOnUiThread {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }

        @JavascriptInterface
        fun openLocationSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }

        @JavascriptInterface
        fun start() {
            runOnUiThread { startTracking() }
        }

        @JavascriptInterface
        fun drain(): String = FixBuffer.drain()

        @JavascriptInterface
        fun diag(): String = GpsInfo.json()

        @JavascriptInterface
        fun setTripRunning(on: Boolean) {
            if (TripState.tripRunning == on) return
            TripState.tripRunning = on
            runOnUiThread { startTracking() } // updates the notification text
        }

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            fullscreen = on
            runOnUiThread {
                val c = WindowCompat.getInsetsController(window, window.decorView)
                if (on) {
                    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    c.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        @JavascriptInterface
        fun isFullscreen(): Boolean = fullscreen

        @JavascriptInterface
        fun shareFile(name: String, content: String) {
            runOnUiThread { this@MainActivity.shareFile(name, content) }
        }

        @JavascriptInterface
        fun version(): String = appVersion()
    }
}
