package com.example.blackbox.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineForegroundController
import com.example.blackbox.location.hasAnyLocationPermission
import com.example.blackbox.location.hasNotificationPermission
import com.example.blackbox.ui.components.ButtonLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LocationEngineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by LocationEngine.state.collectAsState()
    val foregroundState by LocationEngineForegroundController.state.collectAsState()

    var permissionGranted by rememberSaveable { mutableStateOf(context.hasAnyLocationPermission()) }
    var notificationPermissionGranted by rememberSaveable { mutableStateOf(context.hasNotificationPermission()) }
    var enableAfterPermission by rememberSaveable { mutableStateOf(false) }
    var startBackgroundAfterPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = context.hasAnyLocationPermission()
        notificationPermissionGranted = context.hasNotificationPermission()
        if (permissionGranted && enableAfterPermission) {
            LocationEngine.setEngineEnabled(true)
        }
        if (permissionGranted && notificationPermissionGranted && startBackgroundAfterPermission) {
            LocationEngineForegroundController.start(context)
        }
        enableAfterPermission = false
        startBackgroundAfterPermission = false
    }

    LaunchedEffect(context) {
        LocationEngine.initialize(context.applicationContext)
        LocationEngineForegroundController.initialize(context.applicationContext)
        permissionGranted = context.hasAnyLocationPermission()
        notificationPermissionGranted = context.hasNotificationPermission()
    }

    val bestPosition = state.bestPositionFix
    val bestMotion = state.bestMotionFix

    val runtimeRows = listOf(
        InfoItem("Engine State", state.engineMode.name),
        InfoItem("Engine Enabled", yesNo(state.engineEnabled)),
        InfoItem("Keepalive Enabled", yesNo(foregroundState.isEnabled)),
        InfoItem("Keepalive Running", yesNo(foregroundState.isRunning)),
        InfoItem("Keepalive Status", foregroundState.statusMessage),
        InfoItem("Low-Power Allowed", yesNo(state.allowLowPowerBackground)),
        InfoItem("Force Active", yesNo(state.forceActive)),
        InfoItem("Notification Permission", if (notificationPermissionGranted) "Granted" else "Missing")
    )

    val demandRows = listOf(
        InfoItem("Location Permission", if (permissionGranted) "Granted" else "Missing"),
        InfoItem("High-Demand Count", state.highDemandConsumers.size.toString()),
        InfoItem(
            "High-Demand Consumers",
            state.highDemandConsumers.sorted().joinToString(", ").ifBlank { "None" }
        )
    )

    val providerRows = listOf(
        InfoItem(
            "Enabled Providers",
            state.enabledProviders.sorted().joinToString(", ").ifBlank { "None" }
        ),
        InfoItem(
            "Subscribed Providers",
            state.subscribedProviders.sorted().joinToString(", ").ifBlank { "None" }
        )
    )

    val bestPositionRows = if (bestPosition == null) {
        listOf(
            InfoItem("Summary", "Unavailable"),
            InfoItem("Reason", "No valid fix (accuracy <= 0 or age above mode threshold).")
        )
    } else {
        listOf(
            InfoItem("Provider", bestPosition.provider),
            InfoItem("Latitude", formatDouble(bestPosition.location.latitude, 6)),
            InfoItem("Longitude", formatDouble(bestPosition.location.longitude, 6)),
            InfoItem("Accuracy (m)", formatFloat(bestPosition.accuracyMeters, 2)),
            InfoItem("Age (ms)", bestPosition.ageMillis.toString()),
            InfoItem("Fix Time", formatTime(bestPosition.fixTimeMillis))
        )
    }

    val bestMotionRows = if (bestMotion == null) {
        listOf(
            InfoItem("Summary", "Unavailable"),
            InfoItem("Eligibility", state.motionStatus)
        )
    } else {
        listOf(
            InfoItem("Speed (m/s)", formatFloat(bestMotion.speedMetersPerSecond, 3)),
            InfoItem("Bearing (deg)", formatFloat(bestMotion.bearingDegrees, 2)),
            InfoItem("Age (ms)", bestMotion.ageMillis.toString()),
            InfoItem("Provider", bestMotion.provider),
            InfoItem("Fix Time", formatTime(bestMotion.fixTimeMillis)),
            InfoItem(
                "Speed Accuracy (m/s)",
                bestMotion.speedAccuracyMetersPerSecond?.let { formatFloat(it, 3) } ?: "Unavailable"
            ),
            InfoItem(
                "Bearing Accuracy (deg)",
                bestMotion.bearingAccuracyDegrees?.let { formatFloat(it, 2) } ?: "Unavailable"
            )
        )
    }

    val sensorSatelliteRows = listOf(
        InfoItem("Significant Motion Available", state.significantMotion.available.toString()),
        InfoItem("Significant Motion Sensor", state.significantMotion.sensorName ?: "Unavailable"),
        InfoItem("Significant Motion Armed", state.significantMotion.armed.toString()),
        InfoItem(
            "Significant Motion Last Trigger",
            state.significantMotion.lastTriggeredAtMillis?.let { formatTime(it) } ?: "Never"
        ),
        InfoItem("Satellites Visible", state.satelliteSummary.visibleCount?.toString() ?: "Unavailable"),
        InfoItem("Satellites Used In Fix", state.satelliteSummary.usedInFixCount?.toString() ?: "Unavailable"),
        InfoItem(
            "Avg C/N0 Used (dB-Hz)",
            state.satelliteSummary.avgCn0Used?.let { formatFloat(it, 2) } ?: "Unavailable"
        ),
        InfoItem(
            "Constellations",
            state.satelliteSummary.constellationCounts.entries
                .joinToString(", ") { "${it.key}:${it.value}" }
                .ifBlank { "Unavailable" }
        ),
        InfoItem("Satellite Status", state.satelliteSummary.statusMessage)
    )

    val diagnosticRows = listOf(
        InfoItem("Last Status", state.lastStatusMessage),
        InfoItem("Last Error", state.lastErrorMessage ?: "None", isError = state.lastErrorMessage != null)
    )

    val historyRows = state.statusHistory.reversed().map { entry ->
        val prefix = if (entry.isError) "ERROR" else "INFO"
        InfoItem(
            label = "$prefix @ ${formatTime(entry.timestampMillis)}",
            value = entry.message,
            isError = entry.isError
        )
    }

    val positionSummary = if (bestPosition == null) {
        "Unavailable"
    } else {
        "${bestPosition.provider}, ±${formatFloat(bestPosition.accuracyMeters, 1)}m, age ${bestPosition.ageMillis}ms"
    }
    val motionSummary = if (bestMotion == null) state.motionStatus else {
        "${formatFloat(bestMotion.speedMetersPerSecond, 2)}m/s @ ${formatFloat(bestMotion.bearingDegrees, 1)}deg"
    }
    val providerSummary =
        "Subscribed ${state.subscribedProviders.size}, demand ${state.highDemandConsumers.size}"
    val sensorSatelliteSummary =
        "SigMotion armed=${state.significantMotion.armed}, sats=${state.satelliteSummary.visibleCount ?: 0}"
    val runtimeSummary =
        "${state.engineMode.name} | Engine ${yesNo(state.engineEnabled)} | Keepalive ${yesNo(foregroundState.isEnabled)}"
    val demandSummary = "Demand ${state.highDemandConsumers.size} | Permission ${if (permissionGranted) "Granted" else "Missing"}"
    val groups = listOf(
        InfoGroup(
            title = "Runtime",
            summary = runtimeSummary,
            rows = runtimeRows
        ),
        InfoGroup(
            title = "Demand & Permissions",
            summary = demandSummary,
            rows = demandRows
        ),
        InfoGroup(
            title = "Providers",
            summary = providerSummary,
            rows = providerRows
        ),
        InfoGroup(
            title = "Best Position Fix",
            summary = positionSummary,
            rows = bestPositionRows
        ),
        InfoGroup(
            title = "Best Motion Fix",
            summary = motionSummary,
            rows = bestMotionRows
        ),
        InfoGroup(
            title = "Sensors & Satellites",
            summary = sensorSatelliteSummary,
            rows = sensorSatelliteRows
        ),
        InfoGroup(
            title = "Diagnostics",
            summary = "Last status + error snapshot",
            rows = diagnosticRows
        ),
        InfoGroup(
            title = "Event History",
            summary = "${historyRows.size} entries",
            rows = historyRows
        )
    )

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

        item {
            ToggleRow(
                title = "Keepalive (FGS)",
                subtitle = "Keeps process alive with a persistent notification; mode still follows demand and low-power settings.",
                checked = foregroundState.isEnabled
            ) { checked ->
                if (!checked) {
                    LocationEngineForegroundController.stop(context)
                    return@ToggleRow
                }

                permissionGranted = context.hasAnyLocationPermission()
                notificationPermissionGranted = context.hasNotificationPermission()
                if (permissionGranted && notificationPermissionGranted) {
                    LocationEngineForegroundController.start(context)
                } else {
                    startBackgroundAfterPermission = true
                    val requestedPermissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestedPermissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    permissionLauncher.launch(requestedPermissions.toTypedArray())
                }
            }
        }

        item {
            ToggleRow(
                title = "Allow Low-Power Background",
                subtitle = "When no high-demand consumer is active, use Low-Power instead of Off.",
                checked = state.allowLowPowerBackground
            ) { checked ->
                LocationEngine.setAllowLowPowerBackground(checked)
            }
        }

        item {
            ToggleRow(
                title = "Force Active (Debug)",
                subtitle = "Force Active mode even when there are no high-demand consumers.",
                checked = state.forceActive
            ) { checked ->
                LocationEngine.setForceActive(checked)
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

        item { SectionTitle("Engine Data") }

        items(groups, key = { it.title }) { group ->
            ExpandableInfoBox(
                title = group.title,
                summary = group.summary,
                rows = group.rows,
                initiallyExpanded = group.initiallyExpanded
            )
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
private fun ExpandableInfoBox(
    title: String,
    summary: String,
    rows: List<InfoItem>,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (expanded) "Collapse" else "Expand",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                rows.forEachIndexed { index, row ->
                    GroupInfoRow(row = row)
                    if (index != rows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
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

@Composable
private fun GroupInfoRow(row: InfoItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (row.isError) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class InfoItem(
    val label: String,
    val value: String,
    val isError: Boolean = false
)

private data class InfoGroup(
    val title: String,
    val summary: String,
    val rows: List<InfoItem>,
    val initiallyExpanded: Boolean = false
)

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private fun formatDouble(value: Double, digits: Int): String {
    return "%.${digits}f".format(Locale.US, value)
}

private fun formatFloat(value: Float, digits: Int): String {
    return "%.${digits}f".format(Locale.US, value)
}

private fun formatTime(timestampMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(timestampMillis))
}
