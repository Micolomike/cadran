package fr.micolomike.cadran

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    private lateinit var root: FrameLayout

    @Volatile
    private var deniedForGood = false

    @Volatile
    private var fullscreen = false

    /** Barometer read while Cadran is on screen, at the rate chosen in the settings (0 = as fast as the sensor goes). */
    @Volatile
    private var baroHz = 0
    private var baroListener: SensorEventListener? = null

    private fun startBaro() {
        stopBaro()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values[0]
                if (v < 250f || v > 1100f) return
                BaroLive.pressure = v
                BaroLive.count++
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val period = if (baroHz <= 0) SensorManager.SENSOR_DELAY_FASTEST else 1_000_000 / baroHz
        sm.registerListener(l, sensor, period)
        baroListener = l
    }

    private fun stopBaro() {
        baroListener?.let { (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(it) }
        baroListener = null
    }

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
        root = FrameLayout(this)
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
        // OpenStreetMap asks apps to identify themselves when loading map tiles.
        web.settings.userAgentString = web.settings.userAgentString + " Cadran/" + appVersion() + " (+https://github.com/Micolomike/cadran)"
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
        AppState.foreground = true
        DisplayConfig.load(this)
        Floating.hide(this)
        if (hasFine()) startTracking()
        startBaro()
        TrackingService.instance?.refreshNotification()
    }

    override fun onStop() {
        super.onStop()
        stopBaro()
        AppState.foreground = false
        // Keep measuring in the background during a trip, or for the widget and the floating speedometer.
        if (!TripState.tripRunning && !DisplayConfig.keepRunning) stopService(Intent(this, TrackingService::class.java))
        else TrackingService.instance?.onAppVisibilityChanged()
    }

    override fun onDestroy() {
        if (isFinishing && !TripState.tripRunning && !DisplayConfig.keepRunning) stopService(Intent(this, TrackingService::class.java))
        web.destroy()
        super.onDestroy()
    }

    private fun hasFine(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun networkLocationEnabled(lm: LocationManager): Boolean {
        fun on(provider: String) = try {
            lm.allProviders.contains(provider) && lm.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }
        return on(LocationManager.NETWORK_PROVIDER) ||
            (Build.VERSION.SDK_INT >= 31 && on(LocationManager.FUSED_PROVIDER))
    }

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
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "granted"
            // GPS switched off but Wi-Fi / cell-tower positioning still on: run on the network position.
            return if (networkLocationEnabled(lm)) "netonly" else "gpsoff"
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
            type = if (safe.endsWith(".gpx")) "application/gpx+xml" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, safe)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, if (safe.endsWith(".gpx")) "Exporter le tracé" else "Exporter les trajets"))
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

        /** Latest pressure in hPa and a counter of readings, as "p,count"; empty without a barometer. */
        @JavascriptInterface
        fun baro(): String = if (BaroLive.count == 0L) "" else "${BaroLive.pressure},${BaroLive.count}"

        /** Units, alert, altimeter setting and skin colours for the widget and the floating speedometer. */
        @JavascriptInterface
        fun setDisplayConfig(json: String) {
            DisplayConfig.apply(json)
            DisplayConfig.save(this@MainActivity, json)
            CadranWidget.render(applicationContext, GpsInfo.serviceRunning)
            TrackingService.instance?.refreshNotification()
        }

        @JavascriptInterface
        fun floatingPermission(): Boolean = Settings.canDrawOverlays(this@MainActivity)

        @JavascriptInterface
        fun requestFloatingPermission() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
        }

        @JavascriptInterface
        fun floatingEnabled(): Boolean = DisplayConfig.floating

        @JavascriptInterface
        fun setFloating(on: Boolean) {
            DisplayConfig.setFloating(this@MainActivity, on)
            TrackingService.instance?.refreshNotification()
        }

        @JavascriptInterface
        fun setBaroRate(hz: Int) {
            baroHz = hz
            runOnUiThread { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startBaro() }
        }

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

        @JavascriptInterface
        fun setBarColor(hex: String) {
            runOnUiThread {
                try {
                    val c = Color.parseColor(hex)
                    root.setBackgroundColor(c)
                    web.setBackgroundColor(c)
                } catch (e: Exception) {
                    // Keep the current colour.
                }
            }
        }
    }
}
