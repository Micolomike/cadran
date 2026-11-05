package fr.micolomike.cadran

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: speed, unit, a speed bar and the altitude, in the colours of the chosen skin.
 * It is refreshed by the GPS service while Cadran is active (app open, trip running,
 * or background mode set to "always"); otherwise it shows how to start it.
 */
class CadranWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, GpsInfo.serviceRunning)
    }

    override fun onEnabled(context: Context) {
        render(context, GpsInfo.serviceRunning)
    }

    companion object {
        fun render(ctx: Context, running: Boolean) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val cn = ComponentName(ctx, CadranWidget::class.java)
                if (mgr.getAppWidgetIds(cn).isEmpty()) return
                DisplayConfig.load(ctx)
                val v = RemoteViews(ctx.packageName, R.layout.widget_cadran)
                val fresh = running && Live.speedFresh()
                val over = running && Live.isOver()
                v.setInt(R.id.w_bg, "setColorFilter", DisplayConfig.ground)
                v.setInt(R.id.w_track, "setColorFilter", DisplayConfig.line)
                v.setInt(R.id.w_bar, "setColorFilter", if (over) DisplayConfig.over else DisplayConfig.accent)
                v.setInt(R.id.w_bar, "setImageLevel", if (fresh) (Live.speedFraction() * 10000).toInt() else 0)
                v.setTextViewText(R.id.w_speed, if (running) Live.speedText() else "--")
                v.setTextColor(R.id.w_speed, if (over) DisplayConfig.over else DisplayConfig.ink)
                v.setTextViewText(R.id.w_unit, DisplayConfig.speedUnit)
                v.setTextColor(R.id.w_unit, DisplayConfig.inkDim)
                v.setTextViewText(
                    R.id.w_alt,
                    if (running) "ALT  " + Live.altitudeText() + " " + DisplayConfig.altUnit else ""
                )
                v.setTextColor(R.id.w_alt, DisplayConfig.ink)
                v.setTextViewText(
                    R.id.w_status,
                    when {
                        !running -> "Toucher pour ouvrir"
                        TripState.tripRunning -> "Trajet en cours"
                        fresh -> "Cadran"
                        else -> "Recherche GPS"
                    }
                )
                v.setTextColor(R.id.w_status, DisplayConfig.inkDim)
                val open = PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                v.setOnClickPendingIntent(R.id.w_root, open)
                mgr.updateAppWidget(cn, v)
            } catch (e: Exception) {
                // A widget refresh must never stop the GPS service.
            }
        }
    }
}
