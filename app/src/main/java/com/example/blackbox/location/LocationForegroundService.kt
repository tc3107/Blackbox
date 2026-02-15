package com.example.blackbox.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.blackbox.MainActivity
import com.example.blackbox.R
import java.util.Locale

class LocationForegroundService : Service() {
    private var locationManager: LocationManager? = null
    private val listeners = mutableMapOf<String, LocationListener>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                startLocationUpdates()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        LocationServiceStateStore.markStopped()
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        if (!applicationContext.hasAnyLocationPermission()) {
            LocationServiceStateStore.updateError("Location permission is not granted.")
            updateNotification("Permission required.")
            stopSelf()
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        if (manager == null) {
            LocationServiceStateStore.updateError("LocationManager service unavailable.")
            updateNotification("LocationManager unavailable.")
            stopSelf()
            return
        }

        locationManager = manager
        stopLocationUpdates()

        val providerCandidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        providerCandidates.forEach { provider ->
            val isEnabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!isEnabled && provider != LocationManager.PASSIVE_PROVIDER) {
                return@forEach
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    LocationServiceStateStore.updateLocation(location, listeners.keys.toSet())
                    val notificationText = buildNotificationText(location)
                    updateNotification(notificationText)
                }

                override fun onProviderEnabled(provider: String) {
                    refreshProviderState()
                }

                override fun onProviderDisabled(provider: String) {
                    refreshProviderState()
                }
            }

            val registrationResult = runCatching {
                manager.requestLocationUpdates(
                    provider,
                    if (provider == LocationManager.PASSIVE_PROVIDER) 0L else 1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }

            if (registrationResult.isSuccess) {
                listeners[provider] = listener
            }
        }

        if (listeners.isEmpty()) {
            LocationServiceStateStore.updateError("No location providers could be registered.")
            updateNotification("No providers enabled.")
            return
        }

        LocationServiceStateStore.markRunning(
            activeProviders = listeners.keys.toSet(),
            statusMessage = "Listening on ${listeners.keys.joinToString(", ")}."
        )
        updateNotification("Listening on ${listeners.keys.joinToString(", ")}.")
        publishBestLastKnownLocation(manager, listeners.keys.toSet())
    }

    private fun publishBestLastKnownLocation(manager: LocationManager, activeProviders: Set<String>) {
        val best = activeProviders.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (best != null) {
            LocationServiceStateStore.updateLocation(best, activeProviders)
            updateNotification(buildNotificationText(best))
        }
    }

    private fun refreshProviderState() {
        val activeProviders = listeners.keys.filter { provider ->
            runCatching { locationManager?.isProviderEnabled(provider) ?: false }.getOrDefault(false)
        }.toSet()

        LocationServiceStateStore.markRunning(
            activeProviders = activeProviders,
            statusMessage = if (activeProviders.isEmpty()) {
                "Service running with no active providers."
            } else {
                "Listening on ${activeProviders.joinToString(", ")}."
            }
        )
    }

    private fun stopLocationUpdates() {
        val manager = locationManager ?: return
        listeners.values.forEach { listener ->
            runCatching { manager.removeUpdates(listener) }
        }
        listeners.clear()
    }

    private fun startAsForeground() {
        val notification = buildNotification("Starting location service.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LocationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.location_stop_button),
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.location_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.location_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotificationText(location: Location): String {
        return String.format(
            Locale.US,
            "Lat %.5f, Lon %.5f (%s)",
            location.latitude,
            location.longitude,
            location.provider ?: "unknown"
        )
    }

    companion object {
        const val ACTION_START = "com.example.blackbox.location.action.START"
        const val ACTION_STOP = "com.example.blackbox.location.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 4201
    }
}
