package com.example.blackbox.data.settings

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(UiSettings())

    val settings: StateFlow<UiSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            _settings.value = readSettings()
        }
    }

    fun setCustomAccentHex(hex: String?) {
        val normalizedHex = hex
            ?.trim()
            ?.removePrefix("#")
            ?.takeIf { HEX_REGEX.matches(it) }
            ?.uppercase(Locale.US)

        _settings.value = UiSettings(customAccentHex = normalizedHex)
        scope.launch {
            preferences().edit {
                putString(KEY_CUSTOM_ACCENT_HEX, normalizedHex)
            }
        }
    }

    private fun readSettings(): UiSettings {
        val preferences = preferences()
        return UiSettings(
            customAccentHex = preferences.getString(KEY_CUSTOM_ACCENT_HEX, null)
        )
    }

    private fun preferences() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "blackbox_ui_settings"
        const val KEY_CUSTOM_ACCENT_HEX = "custom_accent_hex"
        val HEX_REGEX = Regex("^[0-9A-Fa-f]{6}$")
    }
}
