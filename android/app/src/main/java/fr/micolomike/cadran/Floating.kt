package fr.micolomike.cadran

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

/**
 * Floating speedometer drawn over other apps (Waze, Maps…). Shown only while Cadran is in the
 * background, the option is on and Android allows "display over other apps".
 * Drag it to move it, tap it to return to Cadran, long-press it to hide it.
 */
object Floating {
    private var view: FloatingView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Call on the main thread: shows or hides the bubble according to the current state. */
    fun sync(ctx: Context) {
        DisplayConfig.load(ctx)
        val want = DisplayConfig.floating && !AppState.foreground && GpsInfo.serviceRunning &&
            Settings.canDrawOverlays(ctx)
        if (want) show(ctx) else hide(ctx)
    }

    private fun show(ctx: Context) {
        if (view != null) { view?.invalidate(); return }
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (128 * app.resources.displayMetrics.density).toInt()
        val prefs = app.getSharedPreferences("cadran", Context.MODE_PRIVATE)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs.getInt("floatX", app.resources.displayMetrics.widthPixels - size - (12 * app.resources.displayMetrics.density).toInt())
        p.y = prefs.getInt("floatY", (180 * app.resources.displayMetrics.density).toInt())
        val v = FloatingView(app, wm, p)
        try {
            wm.addView(v, p)
            view = v
            params = p
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        try {
            (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // Already gone.
        }
        view = null
        params = null
    }

    fun update() {
        view?.invalidate()
    }
}

@SuppressLint("ViewConstructor")
private class FloatingView(
    ctx: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams
) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val arc = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var downAt = 0L
    private var dragging = false
    private var longDone = false
    private val longPress = Runnable {
        if (!dragging) {
            longDone = true
            DisplayConfig.setFloating(context, false)
            Floating.hide(context)
            Toast.makeText(context, "Compteur flottant masqué. Réactivable dans les réglages de Cadran.", Toast.LENGTH_LONG).show()
            TrackingService.instance?.onAppVisibilityChanged()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat()
        val c = s / 2
        val stroke = s * 0.065f
        bg.color = DisplayConfig.ground
        bg.alpha = 238
        canvas.drawCircle(c, c, c - 1, bg)
        arc.set(stroke, stroke, s - stroke, s - stroke)
        ring.strokeWidth = stroke
        ring.color = DisplayConfig.line
        canvas.drawArc(arc, 135f, 270f, false, ring)
        val over = Live.isOver()
        val frac = Live.speedFraction()
        if (frac > 0f) {
            ring.color = if (over) DisplayConfig.over else DisplayConfig.accent
            canvas.drawArc(arc, 135f, 270f * frac, false, ring)
        }
        text.color = if (over) DisplayConfig.over else DisplayConfig.ink
        text.textSize = s * 0.36f
        canvas.drawText(Live.speedText(), c, c + s * 0.10f, text)
        small.color = DisplayConfig.inkDim
        small.textSize = s * 0.105f
        canvas.drawText(DisplayConfig.speedUnit, c, c + s * 0.23f, small)
        small.color = DisplayConfig.ink
        small.textSize = s * 0.10f
        canvas.drawText(Live.altitudeText() + " " + DisplayConfig.altUnit, c, s - stroke * 1.1f, small)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startX = lp.x; startY = lp.y
                downAt = e.eventTime
                dragging = false; longDone = false
                postDelayed(longPress, 700)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    try { wm.updateViewLayout(this, lp) } catch (ex: Exception) { }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    context.getSharedPreferences("cadran", Context.MODE_PRIVATE).edit()
                        .putInt("floatX", lp.x).putInt("floatY", lp.y).apply()
                } else if (!longDone && e.eventTime - downAt < 600) {
                    val open = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    try { context.startActivity(open) } catch (ex: Exception) { }
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    init {
        contentDescription = "Compteur flottant Cadran"
        setBackgroundColor(Color.TRANSPARENT)
    }
}
