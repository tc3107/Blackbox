package com.example.blackbox.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.blackbox.MainActivity
import com.example.blackbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocationEngineForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engineStateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LocationEngine.initialize(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                START_NOT_STICKY
            }

            else -> {
                val started = runCatching {
                    startAsForeground("Starting Keepalive.")
                    startHostingEngine()
                }.isSuccess
                if (started) {
                    START_STICKY
                } else {
                    LocationEngineForegroundController.markStopped("Keepalive failed to enter foreground.")
                    stopSelf()
                    START_NOT_STICKY
                }
            }
        }
    }

    override fun onDestroy() {
        engineStateJob?.cancel()
        engineStateJob = null
        serviceScope.cancel()
        LocationEngineForegroundController.markStopped("Keepalive stopped.")
        super.onDestroy()
    }

    private fun startHostingEngine() {
        if (!applicationContext.hasAnyLocationPermission()) {
            LocationEngineForegroundController.markStopped("Permission required for Keepalive.")
            updateNotification("Location permission required.")
            stopSelf()
            return
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            LocationEngineForegroundController.markRunning(
                "Keepalive active, but app notifications are disabled."
            )
        } else {
            LocationEngineForegroundController.markRunning("Keepalive active.")
        }

        if (engineStateJob == null) {
            engineStateJob = serviceScope.launch {
                LocationEngine.state.collectLatest { engineState ->
                    val notificationText = buildNotificationText(engineState)
                    updateNotification(notificationText)
                    LocationEngineForegroundController.markRunning(
                        "Keepalive active (${engineState.engineMode.name})."
                    )
                }
            }
        }
    }

    private fun startAsForeground(contentText: String) {
        val notification = buildNotification(contentText)
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
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
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
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotificationText(state: LocationEngineState): String {
        if (!state.engineEnabled) {
            return "Keepalive active. Engine is off."
        }
        val position = state.bestPositionFix
        return if (position == null) {
            "Mode ${state.engineMode.name}. Waiting for valid fix."
        } else {
            "Mode ${state.engineMode.name}. ${position.provider} ±${position.accuracyMeters.toInt()}m"
        }
    }

    companion object {
        const val ACTION_START = "com.example.blackbox.locationengine.action.START"
        const val ACTION_STOP = "com.example.blackbox.locationengine.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "location_keepalive_silent_v3"
        private const val NOTIFICATION_ID = 4301
    }
}
