package fr.micolomike.cadran

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Shared between the GPS service and the page bridge. */
object TripState {
    @Volatile
    var tripRunning: Boolean = false
}

/**
 * Positions received from the GPS, kept until the page reads them.
 * While the screen is off or another app is in front, they pile up here
 * and the page replays them in order when it runs again.
 */
object FixBuffer {
    private val queue = ArrayDeque<String>()
    private const val MAX = 30000 // about 8 hours at one fix per second

    fun add(l: Location) {
        val speed = if (l.hasSpeed()) l.speed.toString() else "null"
        val acc = if (l.hasAccuracy()) l.accuracy.toString() else "999"
        val json = "{\"lat\":${l.latitude},\"lon\":${l.longitude},\"acc\":$acc,\"speed\":$speed,\"t\":${l.time}}"
        synchronized(queue) {
            queue.addLast(json)
            while (queue.size > MAX) queue.removeFirst()
        }
    }

    fun drain(): String = synchronized(queue) {
        if (queue.isEmpty()) {
            "[]"
        } else {
            val out = queue.joinToString(separator = ",", prefix = "[", postfix = "]")
            queue.clear()
            out
        }
    }
}

/**
 * Foreground service that owns the GPS. It runs while Cadran is on screen,
 * and keeps running in the background only while a trip is being measured.
 */
class TrackingService : Service(), LocationListener {

    private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android refuses a location service started from the background: stop quietly.
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (listening) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            listening = true
        } catch (e: SecurityException) {
            listening = false
        } catch (e: IllegalArgumentException) {
            listening = false
        }
    }

    override fun onLocationChanged(location: Location) {
        FixBuffer.add(location)
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Required on Android 9 and older")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        if (listening) {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
            listening = false
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Mesure GPS", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Indique quand Cadran utilise le GPS."
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val running = TripState.tripRunning
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cadran)
            .setContentTitle(if (running) "Trajet en cours" else "Cadran")
            .setContentText(
                if (running) "La distance est mesurée, même écran éteint."
                else "GPS actif tant que Cadran est ouvert."
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 7
        const val CHANNEL_ID = "cadran_gps"
    }
}
