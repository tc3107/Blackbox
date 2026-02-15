package com.example.blackbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.blackbox.data.settings.UiSettingsStore
import com.example.blackbox.ui.BlackboxApp
import com.example.blackbox.ui.theme.BlackboxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsStore = remember { UiSettingsStore(applicationContext) }
            val settings by settingsStore.settings.collectAsState()

            BlackboxTheme(customAccentHex = settings.customAccentHex) {
                BlackboxApp(
                    settings = settings,
                    onCustomAccentSaved = settingsStore::setCustomAccentHex
                )
            }
        }
    }
}
