package com.example.blackbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.data.settings.UiSettingsStore
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineForegroundController
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.ui.BlackboxApp
import com.example.blackbox.ui.theme.BlackboxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocationEngine.initialize(applicationContext)
        LocationEngineForegroundController.initialize(applicationContext)
        LocationPersistenceController.initialize(applicationContext)
        LocationSharingController.initialize(applicationContext)
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

    override fun onStop() {
        super.onStop()
        LocationEngine.clearUiHighDemandConsumers()
    }
}
