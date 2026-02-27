package com.example.blackbox.sharing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MIN_NORMAL_INTERVAL_MS = 60_000L
const val MAX_NORMAL_INTERVAL_MS = 30 * 60_000L
const val MIN_FAST_INTERVAL_MS = 30_000L
const val MAX_FAST_INTERVAL_MS = 10 * 60_000L

class SharingSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(SharingSettings())

    val settings: StateFlow<SharingSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            _settings.value = readSettings()
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        _settings.update { it.copy(sharingEnabled = enabled) }
        scope.launch {
            preferences().edit().putBoolean(KEY_SHARING_ENABLED, enabled).apply()
        }
    }

    fun setUsername(username: String) {
        val normalized = normalizeUsername(username)
        _settings.update { it.copy(username = normalized) }
        scope.launch {
            preferences().edit().putString(KEY_USERNAME, normalized).apply()
        }
    }

    fun setRelayBaseUrl(baseUrl: String) {
        val normalized = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_RELAY_BASE_URL }
        _settings.update { it.copy(relayBaseUrl = normalized) }
        scope.launch {
            preferences().edit().putString(KEY_RELAY_BASE_URL, normalized).apply()
        }
    }

    fun setIntervals(normalMs: Long, fastMs: Long) {
        val boundedNormal = normalMs.coerceIn(MIN_NORMAL_INTERVAL_MS, MAX_NORMAL_INTERVAL_MS)
        val boundedFast = fastMs.coerceIn(MIN_FAST_INTERVAL_MS, MAX_FAST_INTERVAL_MS)
        _settings.update {
            it.copy(
                normalIntervalMs = boundedNormal,
                fastIntervalMs = boundedFast
            )
        }
        scope.launch {
            preferences()
                .edit()
                .putLong(KEY_NORMAL_INTERVAL_MS, boundedNormal)
                .putLong(KEY_FAST_INTERVAL_MS, boundedFast)
                .apply()
        }
    }

    private fun readSettings(): SharingSettings {
        val preferences = preferences()
        val relayBaseUrl = preferences.getString(KEY_RELAY_BASE_URL, DEFAULT_RELAY_BASE_URL)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_RELAY_BASE_URL
        val username = normalizeUsername(preferences.getString(KEY_USERNAME, "").orEmpty())
        val normal = preferences.getLong(KEY_NORMAL_INTERVAL_MS, DEFAULT_NORMAL_PUSH_INTERVAL_MS)
            .coerceIn(MIN_NORMAL_INTERVAL_MS, MAX_NORMAL_INTERVAL_MS)
        val fast = preferences.getLong(KEY_FAST_INTERVAL_MS, DEFAULT_FAST_PUSH_INTERVAL_MS)
            .coerceIn(MIN_FAST_INTERVAL_MS, MAX_FAST_INTERVAL_MS)
        return SharingSettings(
            sharingEnabled = preferences.getBoolean(KEY_SHARING_ENABLED, false),
            username = username,
            relayBaseUrl = relayBaseUrl,
            normalIntervalMs = normal,
            fastIntervalMs = fast,
            fastSpeedThresholdMps = DEFAULT_FAST_SPEED_THRESHOLD_MPS
        )
    }

    private fun preferences() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "sharing_settings"
        const val KEY_SHARING_ENABLED = "sharing_enabled"
        const val KEY_USERNAME = "username"
        const val KEY_RELAY_BASE_URL = "relay_base_url"
        const val KEY_NORMAL_INTERVAL_MS = "normal_interval_ms"
        const val KEY_FAST_INTERVAL_MS = "fast_interval_ms"
    }
}
