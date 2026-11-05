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
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Shared between the GPS service and the page bridge. */
object TripState {
    @Volatile
    var tripRunning: Boolean = false
}

/** What the GPS service is doing, for the status line and the diagnostic. */
object GpsInfo {
    @Volatile var serviceRunning = false
    @Volatile var gpsListening = false
    @Volatile var fallbackProvider: String? = null
    @Volatile var satellitesUsed = 0
    @Volatile var satellitesVisible = 0
    @Volatile var lastGpsAt = 0L       // SystemClock.elapsedRealtime()
    @Volatile var lastFallbackAt = 0L

    fun json(): String {
        val now = SystemClock.elapsedRealtime()
        fun age(t: Long) = if (t == 0L) -1 else ((now - t) / 1000)
        return "{\"service\":$serviceRunning,\"gps\":$gpsListening,\"fallback\":" +
            (fallbackProvider?.let { "\"$it\"" } ?: "null") +
            ",\"used\":$satellitesUsed,\"visible\":$satellitesVisible" +
            ",\"gpsAge\":${age(lastGpsAt)},\"fallbackAge\":${age(lastFallbackAt)}}"
    }
}

/**
 * Positions received, kept until the page reads them.
 * While the screen is off or another app is in front, they pile up here
 * and the page replays them in order when it runs again.
 */
object FixBuffer {
    private val queue = ArrayDeque<String>()
    private const val MAX = 30000 // about 8 hours at one fix per second

    fun add(l: Location, fromNetwork: Boolean) {
        val speed = if (l.hasSpeed()) l.speed.toString() else "null"
        val acc = if (l.hasAccuracy()) l.accuracy.toString() else "999"
        val json = "{\"lat\":${l.latitude},\"lon\":${l.longitude},\"acc\":$acc,\"speed\":$speed,\"t\":${l.time}" +
            (if (fromNetwork) ",\"net\":true}" else "}")
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
 * Foreground service that owns the location sources. It runs while Cadran is on screen,
 * and keeps running in the background only while a trip is being measured.
 *
 * The GPS chip gives the precise positions and speeds used for measuring.
 * A second, network-assisted source (Wi-Fi, cell towers) is only passed on when the GPS
 * has said nothing for 3 seconds, so the app still knows roughly where it is indoors.
 * The page never measures distance from those network positions.
 */
class TrackingService : Service() {

    private var registered = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            GpsInfo.lastGpsAt = SystemClock.elapsedRealtime()
            FixBuffer.add(location, fromNetwork = false)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Required on Android 9 and older")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val fallbackListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            GpsInfo.lastFallbackAt = now
            if (now - GpsInfo.lastGpsAt > 3000) FixBuffer.add(location, fromNetwork = true)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Required on Android 9 and older")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            GpsInfo.satellitesUsed = used
            GpsInfo.satellitesVisible = status.satelliteCount
        }
        override fun onStopped() {
            GpsInfo.satellitesUsed = 0
            GpsInfo.satellitesVisible = 0
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android refuses a location service started from the background: stop quietly.
            GpsInfo.serviceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        GpsInfo.serviceRunning = true
        startListening()
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun startListening() {
        if (registered) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val looper = Looper.getMainLooper()
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, looper)
            GpsInfo.gpsListening = true
        } catch (e: Exception) {
            GpsInfo.gpsListening = false
        }
        val providers = lm.allProviders
        val fallback = when {
            Build.VERSION.SDK_INT >= 31 && providers.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (fallback != null) {
            try {
                lm.requestLocationUpdates(fallback, 2000L, 0f, fallbackListener, looper)
                GpsInfo.fallbackProvider = fallback
            } catch (e: Exception) {
                GpsInfo.fallbackProvider = null
            }
        }
        try {
            lm.registerGnssStatusCallback(gnssCallback, Handler(looper))
        } catch (e: Exception) {
            // Satellite count is only informative.
        }
        registered = true
    }

    override fun onDestroy() {
        if (registered) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(gpsListener)
            lm.removeUpdates(fallbackListener)
            lm.unregisterGnssStatusCallback(gnssCallback)
            registered = false
        }
        GpsInfo.serviceRunning = false
        GpsInfo.gpsListening = false
        GpsInfo.fallbackProvider = null
        GpsInfo.satellitesUsed = 0
        GpsInfo.satellitesVisible = 0
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
