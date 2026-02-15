package com.example.blackbox.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.blackbox.location.LocationServiceController
import com.example.blackbox.location.hasAnyLocationPermission
import java.util.Locale

@Composable
fun LocationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by LocationServiceController.state.collectAsState()
    var permissionGranted by rememberSaveable { mutableStateOf(context.hasAnyLocationPermission()) }
    var shouldAutoStartAfterPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = context.hasAnyLocationPermission()
        if (permissionGranted && shouldAutoStartAfterPermission) {
            LocationServiceController.start(context)
        }
        shouldAutoStartAfterPermission = false
    }

    LaunchedEffect(Unit) {
        permissionGranted = context.hasAnyLocationPermission()
    }

    val location = state.lastLocation
    val primaryBlockRows = listOf(
        "Latitude" to formatDouble(location?.latitude, 6),
        "Longitude" to formatDouble(location?.longitude, 6),
        "Altitude (m)" to formatDouble(location?.altitudeMeters, 2),
        "Bearing (deg)" to formatFloat(location?.bearingDegrees, 2)
    )
    val secondaryBlockRows = listOf(
        "Accuracy (m)" to formatFloat(location?.accuracyMeters, 2),
        "Provider" to (location?.provider ?: "Unavailable")
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (state.errorMessage != null) {
            item {
                Text(
                    text = state.errorMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        item {
            Button(
                onClick = {
                    if (state.isRunning) {
                        LocationServiceController.stop(context)
                    } else if (permissionGranted) {
                        LocationServiceController.start(context)
                    } else {
                        shouldAutoStartAfterPermission = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            ) {
                Text(
                    if (state.isRunning) {
                        "Stop Location + Logging"
                    } else {
                        "Start Location + Logging"
                    }
                )
            }
        }
        if (!permissionGranted) {
            item {
                Text(
                    text = "Location permission is required to start foreground tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            LocationInfoBlock(title = "Position", rows = primaryBlockRows)
        }
        item {
            LocationInfoBlock(title = "Signal", rows = secondaryBlockRows)
        }
        if (state.activeProviders.isNotEmpty()) {
            item {
                Text(
                    text = "Active Providers: ${state.activeProviders.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LocationInfoBlock(
    title: String,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            rows.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun formatDouble(value: Double?, digits: Int): String {
    return value?.let { "%.${digits}f".format(Locale.US, it) } ?: "Unavailable"
}

private fun formatFloat(value: Float?, digits: Int): String {
    return value?.let { "%.${digits}f".format(Locale.US, it) } ?: "Unavailable"
}
