package com.example.blackbox.location

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LocationEngineForegroundState(
    val isEnabled: Boolean = false,
    val isRunning: Boolean = false,
    val statusMessage: String = "Keepalive is off.",
    val lastUpdatedAtMillis: Long = System.currentTimeMillis()
)

object LocationEngineForegroundController {
    private const val PREFS_NAME = "location_engine_keepalive"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var initialized = false

    private val _state = MutableStateFlow(LocationEngineForegroundState())
    val state: StateFlow<LocationEngineForegroundState> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val enabled = readEnabledPreference(context.applicationContext)
            updateState(
                isEnabled = enabled,
                isRunning = false,
                statusMessage = if (enabled) {
                    "Keepalive enabled. Restoring service."
                } else {
                    "Keepalive is off."
                }
            )
            if (enabled) {
                start(context.applicationContext, persistPreference = false)
            }
            initialized = true
        }
    }

    fun start(context: Context) {
        start(context = context, persistPreference = true)
    }

    private fun start(
        context: Context,
        persistPreference: Boolean
    ) {
        val appContext = context.applicationContext
        if (persistPreference) {
            writeEnabledPreference(appContext, true)
        }
        updateState(
            isEnabled = true,
            isRunning = true,
            statusMessage = if (persistPreference) {
                "Starting Keepalive service."
            } else {
                "Restoring Keepalive service."
            }
        )
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, LocationEngineForegroundService::class.java).apply {
                    action = LocationEngineForegroundService.ACTION_START
                }
            )
        }.onFailure { throwable ->
            updateState(
                isEnabled = true,
                isRunning = false,
                statusMessage = "Keepalive failed to start: ${throwable.message ?: "unknown error"}"
            )
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        writeEnabledPreference(appContext, false)
        updateState(
            isEnabled = false,
            isRunning = false,
            statusMessage = "Stopping Keepalive service."
        )
        appContext.startService(
            Intent(appContext, LocationEngineForegroundService::class.java).apply {
                action = LocationEngineForegroundService.ACTION_STOP
            }
        )
    }

    internal fun markRunning(statusMessage: String) {
        updateState(
            isEnabled = true,
            isRunning = true,
            statusMessage = statusMessage
        )
    }

    internal fun markStopped(statusMessage: String = "Keepalive is off.") {
        updateState(
            isEnabled = _state.value.isEnabled,
            isRunning = false,
            statusMessage = statusMessage
        )
    }

    private fun updateState(
        isEnabled: Boolean,
        isRunning: Boolean,
        statusMessage: String
    ) {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                isEnabled = isEnabled,
                isRunning = isRunning,
                statusMessage = statusMessage,
                lastUpdatedAtMillis = now
            )
        }
    }

    private fun writeEnabledPreference(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, enabled)
        }
    }

    private fun readEnabledPreference(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }
}
