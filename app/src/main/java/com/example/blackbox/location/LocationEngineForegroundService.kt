package com.example.blackbox.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.blackbox.MainActivity
import com.example.blackbox.R
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.sharing.LocationSharingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LocationEngineForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engineStateJob: Job? = null
    private var pendingNotificationJob: Job? = null
    private var pendingExpandedText: String? = null
    private var lastNotifiedExpandedText: String? = null
    private var lastNotifiedAtElapsedMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch(Dispatchers.IO) {
            LocationEngine.initialize(applicationContext)
            LocationPersistenceController.initialize(applicationContext)
            LocationSharingController.initialize(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                START_NOT_STICKY
            }

            else -> {
                val started = runCatching {
                    startAsForeground(currentExpandedNotificationText())
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
        pendingNotificationJob?.cancel()
        pendingNotificationJob = null
        pendingExpandedText = null
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
                combine(
                    LocationEngine.state,
                    LocationPersistenceController.state,
                    LocationSharingController.state
                ) { engineState, persistenceState, sharingState ->
                    NotificationUiState(
                        expandedText = buildExpandedNotificationText(
                            loggingEnabled = persistenceState.loggingEnabled,
                            sharingEnabled = sharingState.settings.sharingEnabled
                        ),
                        engineModeLabel = engineState.engineMode.name
                    )
                }.collectLatest { uiState ->
                    updateNotification(uiState.expandedText)
                    LocationEngineForegroundController.markRunning(
                        "Keepalive active (${uiState.engineModeLabel})."
                    )
                }
            }
        }
    }

    private fun startAsForeground(expandedText: String) {
        val notification = buildNotification(expandedText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        lastNotifiedExpandedText = expandedText
        lastNotifiedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(expandedText: String) {
        if (!applicationContext.hasNotificationPermission()) {
            return
        }

        if (expandedText == lastNotifiedExpandedText) {
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val elapsedSinceLastNotifyMs = nowElapsedMs - lastNotifiedAtElapsedMs
        if (lastNotifiedAtElapsedMs > 0L && elapsedSinceLastNotifyMs < MIN_NOTIFY_INTERVAL_MS) {
            pendingExpandedText = expandedText
            if (pendingNotificationJob?.isActive != true) {
                val delayMs = MIN_NOTIFY_INTERVAL_MS - elapsedSinceLastNotifyMs
                pendingNotificationJob = serviceScope.launch {
                    delay(delayMs)
                    val pending = pendingExpandedText ?: return@launch
                    pendingExpandedText = null
                    updateNotification(pending)
                }
            }
            return
        }

        pendingNotificationJob?.cancel()
        pendingNotificationJob = null
        pendingExpandedText = null

        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(expandedText))
        }.onSuccess {
            lastNotifiedExpandedText = expandedText
            lastNotifiedAtElapsedMs = nowElapsedMs
        }
    }

    private fun buildNotification(expandedText: String): Notification {
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
            .setContentText("")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(expandedText)
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
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

    private fun currentExpandedNotificationText(): String {
        val loggingEnabled = LocationPersistenceController.state.value.loggingEnabled
        val sharingEnabled = LocationSharingController.state.value.settings.sharingEnabled
        return buildExpandedNotificationText(
            loggingEnabled = loggingEnabled,
            sharingEnabled = sharingEnabled
        )
    }

    private fun buildExpandedNotificationText(loggingEnabled: Boolean, sharingEnabled: Boolean): String {
        return when {
            sharingEnabled && loggingEnabled -> "Sharing and logging location."
            sharingEnabled -> "Sharing location."
            loggingEnabled -> "Logging location."
            else -> "Location service active."
        }
    }

    private data class NotificationUiState(
        val expandedText: String,
        val engineModeLabel: String
    )

    companion object {
        const val ACTION_START = "com.example.blackbox.locationengine.action.START"
        const val ACTION_STOP = "com.example.blackbox.locationengine.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "location_keepalive_silent_v3"
        private const val NOTIFICATION_ID = 4301
        private const val MIN_NOTIFY_INTERVAL_MS = 2_000L
    }
}
