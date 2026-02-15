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
import androidx.compose.runtime.DisposableEffect
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
import com.example.blackbox.location.hasAnyLocationPermission
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val LOCATION_SCREEN_CONSUMER_ID = "screen_location"

@Composable
fun LocationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by LocationEngine.state.collectAsState()
    var permissionGranted by rememberSaveable { mutableStateOf(context.hasAnyLocationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = context.hasAnyLocationPermission()
    }

    LaunchedEffect(context) {
        LocationEngine.initialize(context.applicationContext)
        permissionGranted = context.hasAnyLocationPermission()
    }

    DisposableEffect(Unit) {
        LocationEngine.registerHighDemandConsumer(LOCATION_SCREEN_CONSUMER_ID)
        onDispose {
            LocationEngine.unregisterHighDemandConsumer(LOCATION_SCREEN_CONSUMER_ID)
        }
    }

    val position = state.bestPositionFix
    val positionRows = listOf(
        "Latitude" to formatDouble(position?.location?.latitude, 6),
        "Longitude" to formatDouble(position?.location?.longitude, 6),
        "Altitude (m)" to position?.location?.takeIf { it.hasAltitude() }?.altitude?.let {
            formatDouble(it, 2)
        }.orUnavailable(),
        "Accuracy (m)" to position?.accuracyMeters?.let { formatFloat(it, 2) }.orUnavailable(),
        "Age (ms)" to position?.ageMillis?.toString().orUnavailable(),
        "Provider" to (position?.provider ?: "Unavailable"),
        "Fix Time" to position?.fixTimeMillis?.let(::formatTime).orUnavailable()
    )

    val motion = state.bestMotionFix
    val motionRows = listOf(
        "Speed (m/s)" to motion?.speedMetersPerSecond?.let { formatFloat(it, 3) }.orUnavailable(),
        "Bearing (deg)" to motion?.bearingDegrees?.let { formatFloat(it, 2) }.orUnavailable(),
        "Status" to state.motionStatus
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Engine mode: ${state.engineMode.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        item {
            Text(
                text = "Subscribed providers: ${state.subscribedProviders.joinToString(", ").ifBlank { "None" }}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.lastErrorMessage != null) {
            item {
                Text(
                    text = state.lastErrorMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        if (!permissionGranted) {
            item {
                Text(
                    text = "Location permission is required for provider subscriptions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Text("Grant Location Permission")
                }
            }
        }
        item {
            LocationInfoBlock(title = "Best Position", rows = positionRows)
        }
        item {
            LocationInfoBlock(title = "Best Motion", rows = motionRows)
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

private fun formatTime(timestampMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(timestampMillis))
}

private fun String?.orUnavailable(): String = this ?: "Unavailable"
