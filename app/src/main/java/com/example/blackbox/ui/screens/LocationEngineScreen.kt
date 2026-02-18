package com.example.blackbox.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineForegroundController
import com.example.blackbox.location.hasAnyLocationPermission
import com.example.blackbox.ui.components.ButtonLabel

@Composable
fun LocationEngineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by LocationEngine.state.collectAsState()

    var permissionGranted by rememberSaveable { mutableStateOf(context.hasAnyLocationPermission()) }
    var enableAfterPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = context.hasAnyLocationPermission()
        if (permissionGranted && enableAfterPermission) {
            LocationEngine.setEngineEnabled(true)
        }
        enableAfterPermission = false
    }

    LaunchedEffect(context) {
        LocationEngine.initialize(context.applicationContext)
        permissionGranted = context.hasAnyLocationPermission()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Controls") }

        item {
            ToggleRow(
                title = "Engine On",
                subtitle = "Master power toggle for the Location Engine.",
                checked = state.engineEnabled
            ) { checked ->
                if (!checked) {
                    LocationEngine.setEngineEnabled(false)
                    return@ToggleRow
                }

                permissionGranted = context.hasAnyLocationPermission()
                if (permissionGranted) {
                    LocationEngine.setEngineEnabled(true)
                } else {
                    enableAfterPermission = true
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }

        if (!permissionGranted) {
            item {
                Text(
                    text = "Location permission is required to subscribe to providers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    ButtonLabel("Grant Location Permission")
                }
            }
        }

    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
