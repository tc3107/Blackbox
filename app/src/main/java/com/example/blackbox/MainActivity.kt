package com.example.blackbox

import android.os.Bundle
import android.os.Build
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.example.blackbox.debug.MainThreadBlockTracker
import com.example.blackbox.debug.MainThreadWatchdog
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.data.settings.UiSettingsStore
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineForegroundController
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.ui.BlackboxApp
import com.example.blackbox.ui.theme.BlackboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: UiSettingsStore
    private val strictModeListenerExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bbx-strictmode").apply { isDaemon = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureStrictModeForDebug()
        if (BuildConfig.DEBUG) {
            MainThreadWatchdog.start()
        }
        settingsStore = UiSettingsStore(applicationContext)
        enableEdgeToEdge()
        setContent {
            val settings by settingsStore.settings.collectAsState()

            BlackboxTheme(customAccentHex = settings.customAccentHex) {
                BlackboxApp(
                    settings = settings,
                    onCustomAccentSaved = settingsStore::setCustomAccentHex
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            LocationEngine.initialize(applicationContext)
            LocationEngineForegroundController.initialize(applicationContext)
            LocationPersistenceController.initialize(applicationContext)
            LocationSharingController.initialize(applicationContext)
        }
    }

    override fun onStop() {
        super.onStop()
        LocationEngine.clearUiHighDemandConsumers()
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) {
            MainThreadWatchdog.stop()
        }
        super.onDestroy()
    }

    private fun configureStrictModeForDebug() {
        if (!BuildConfig.DEBUG) return
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            threadPolicyBuilder.penaltyListener(strictModeListenerExecutor) { violation ->
                val source = "strictmode.${violation.javaClass.simpleName.removeSuffix("Violation")}"
                MainThreadBlockTracker.recordBlock(
                    durationMs = parseViolationDurationMs(violation.message),
                    stackTrace = violation.stackTrace,
                    source = source
                )
            }
        }
        StrictMode.setThreadPolicy(threadPolicyBuilder.build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }

    private fun parseViolationDurationMs(message: String?): Long {
        val duration = message
            ?.let(STRICTMODE_DURATION_REGEX::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        return duration?.coerceAtLeast(1L) ?: 16L
    }

    private companion object {
        val STRICTMODE_DURATION_REGEX = Regex("~duration=(\\d+) ms")
    }
}
