package com.example.blackbox.data.settings

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())

    val settings: StateFlow<UiSettings> = _settings.asStateFlow()

    fun setCustomAccentHex(hex: String?) {
        val normalizedHex = hex
            ?.trim()
            ?.removePrefix("#")
            ?.takeIf { HEX_REGEX.matches(it) }
            ?.uppercase(Locale.US)

        preferences
            .edit()
            .putString(KEY_CUSTOM_ACCENT_HEX, normalizedHex)
            .apply()

        _settings.value = readSettings()
    }

    private fun readSettings(): UiSettings {
        return UiSettings(
            customAccentHex = preferences.getString(KEY_CUSTOM_ACCENT_HEX, null)
        )
    }

    private companion object {
        const val PREFS_NAME = "blackbox_ui_settings"
        const val KEY_CUSTOM_ACCENT_HEX = "custom_accent_hex"
        val HEX_REGEX = Regex("^[0-9A-Fa-f]{6}$")
    }
}
