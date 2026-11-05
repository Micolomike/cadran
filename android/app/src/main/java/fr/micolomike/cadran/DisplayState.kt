package fr.micolomike.cadran

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** Whether a Cadran screen is in front of the user. */
object AppState {
    @Volatile
    var foreground = false
}

/**
 * Display settings sent by the page (units, alert, altimeter setting, skin colours),
 * so the widget and the floating speedometer read like the app. Kept in preferences
 * so they survive a restart of the GPS service.
 */
object DisplayConfig {
    @Volatile var speedK = 1.0
    @Volatile var speedUnit = "km/h"
    @Volatile var altK = 1.0
    @Volatile var altUnit = "m"
    @Volatile var scaleKmh = 200.0
    @Volatile var alertKmh = 0.0
    @Volatile var altMode = "auto"
    @Volatile var qnh = 1013.25
    @Volatile var altOffset = Double.NaN
    @Volatile var background = "trip"          // "trip" or "always"
    @Volatile var floating = false
    @Volatile var ground = Color.parseColor("#071A1F")
    @Volatile var dial = Color.parseColor("#0C252C")
    @Volatile var accent = Color.parseColor("#FFA42B")
    @Volatile var ink = Color.parseColor("#ECE4D0")
    @Volatile var inkDim = Color.parseColor("#7E9A9C")
    @Volatile var over = Color.parseColor("#FF5B3A")
    @Volatile var line = Color.parseColor("#1C3D46")
    @Volatile private var loaded = false

    /** True when the GPS service should keep running after leaving the app, even without a trip. */
    val keepRunning: Boolean get() = background == "always" || floating

    fun apply(json: String) {
        try {
            val o = JSONObject(json)
            speedK = o.optDouble("speedK", speedK)
            speedUnit = o.optString("speedUnit", speedUnit)
            altK = o.optDouble("altK", altK)
            altUnit = o.optString("altUnit", altUnit)
            scaleKmh = o.optDouble("scaleKmh", scaleKmh).let { if (it.isNaN() || it <= 0) 200.0 else it }
            alertKmh = o.optDouble("alertKmh", 0.0).let { if (it.isNaN()) 0.0 else it }
            altMode = o.optString("altMode", altMode)
            qnh = o.optDouble("qnh", qnh).let { if (it.isNaN() || it < 800) 1013.25 else it }
            altOffset = o.optDouble("altOffset", Double.NaN)
            background = o.optString("background", background)
            val c = o.optJSONObject("colors")
            if (c != null) {
                ground = color(c, "ground", ground)
                dial = color(c, "dial", dial)
                accent = color(c, "accent", accent)
                ink = color(c, "ink", ink)
                inkDim = color(c, "inkDim", inkDim)
                over = color(c, "over", over)
                line = color(c, "line", line)
            }
        } catch (e: Exception) {
            // Keep the previous settings.
        }
    }

    private fun color(o: JSONObject, key: String, def: Int): Int = try {
        Color.parseColor(o.getString(key).trim())
    } catch (e: Exception) {
        def
    }

    fun save(ctx: Context, json: String) {
        prefs(ctx).edit().putString("display", json).apply()
    }

    fun setFloating(ctx: Context, on: Boolean) {
        floating = on
        prefs(ctx).edit().putBoolean("floating", on).apply()
    }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        p.getString("display", null)?.let { apply(it) }
        floating = p.getBoolean("floating", false)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("cadran", Context.MODE_PRIVATE)
}

/** Latest readings from the GPS service, for the widget and the floating speedometer. */
object Live {
    @Volatile var speedKmh = 0.0
    @Volatile var hasSpeed = false
    @Volatile var lastGpsAt = 0L
    @Volatile var gpsAltitude = Double.NaN

    private val fr: NumberFormat = NumberFormat.getIntegerInstance(Locale.FRANCE)

    fun speedFresh(): Boolean = hasSpeed && SystemClock.elapsedRealtime() - lastGpsAt < 5000

    /** Same computation as the page's altimeter: barometer with the GPS correction, QNH, or GPS alone. */
    fun altitudeMeters(): Double? {
        val p = GpsInfo.pressure.toDouble()
        val mode = DisplayConfig.altMode
        if (p > 0 && mode != "gps") {
            return if (mode == "qnh") baroAlt(p, DisplayConfig.qnh)
            else baroAlt(p, 1013.25) + (if (DisplayConfig.altOffset.isNaN()) 0.0 else DisplayConfig.altOffset)
        }
        return if (gpsAltitude.isNaN()) null else gpsAltitude
    }

    private fun baroAlt(p: Double, ref: Double) = 44330.77 * (1 - (p / ref).pow(0.190263))

    fun speedText(): String = if (speedFresh()) (speedKmh * DisplayConfig.speedK).roundToLong().toString() else "--"

    fun altitudeText(): String {
        val a = altitudeMeters() ?: return "--"
        return fr.format((a * DisplayConfig.altK).roundToLong())
    }

    fun isOver(): Boolean = speedFresh() && DisplayConfig.alertKmh > 0 && speedKmh >= DisplayConfig.alertKmh + 0.5

    fun speedFraction(): Float =
        if (speedFresh()) (speedKmh / DisplayConfig.scaleKmh).coerceIn(0.0, 1.0).toFloat() else 0f
}

/** Pushes new readings to the floating speedometer (4 times a second) and the widget (once a second). */
object Outputs {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastFloating = 0L
    @Volatile private var lastWidget = 0L

    fun onData(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        val app = ctx.applicationContext
        if (now - lastFloating >= 250) {
            lastFloating = now
            main.post { Floating.update() }
        }
        if (now - lastWidget >= 1000) {
            lastWidget = now
            CadranWidget.render(app, running = true)
        }
    }
}
