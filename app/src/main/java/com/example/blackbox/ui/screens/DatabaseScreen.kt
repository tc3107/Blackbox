package com.example.blackbox.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import com.example.blackbox.ui.components.NeoOutlinedButton as OutlinedButton
import com.example.blackbox.ui.components.NeoOutlinedCard as OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.ui.components.ButtonLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DATABASE_PANEL_PADDING = PaddingValues(2.dp)
private val EXPORT_FILE_NAME_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US)

@Composable
fun DatabasePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persistenceState by LocationPersistenceController.state.collectAsState()

    var exportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var folderMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            LocationPersistenceController.initialize(context.applicationContext)
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            LocationPersistenceController.setArchiveTreeUri(uri)
            folderMessage = "Archive folder updated."
        }
    }

    val exportPlaintextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = LocationPersistenceController.exportMergedPlaintextDatabase(target = uri)
            exportMessage = result.fold(
                onSuccess = { rows ->
                    "Export complete: $rows merged rows."
                },
                onFailure = { "Export failed: ${it.message ?: "unknown error"}" }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(DATABASE_PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DatabaseStatusCard(state = persistenceState)
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Storage & Export",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (persistenceState.archiveRootUri == null) {
                        "Archive folder: Not configured"
                    } else {
                        "Archive folder: Configured"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { folderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ButtonLabel("Choose Archive Folder")
                }
                OutlinedButton(
                    onClick = {
                        exportPlaintextLauncher.launch(defaultPlaintextExportFileName())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = persistenceState.initialized
                ) {
                    ButtonLabel("Export All Data (Plaintext DB)")
                }
                if (!folderMessage.isNullOrBlank()) {
                    Text(
                        text = folderMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!exportMessage.isNullOrBlank()) {
                    Text(
                        text = exportMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private enum class DatabaseHealthState {
    HEALTHY,
    WARNING,
    ERROR
}

@Composable
private fun DatabaseStatusCard(
    state: com.example.blackbox.data.locationdb.LocationPersistenceState
) {
    val health = when {
        state.lastError != null -> DatabaseHealthState.ERROR
        !state.initialized || state.pendingArchiveCount > 0 -> DatabaseHealthState.WARNING
        else -> DatabaseHealthState.HEALTHY
    }
    val healthLabel = when (health) {
        DatabaseHealthState.HEALTHY -> "Healthy"
        DatabaseHealthState.WARNING -> "Syncing"
        DatabaseHealthState.ERROR -> "Attention"
    }
    val healthColor = when (health) {
        DatabaseHealthState.HEALTHY -> Color(0xFF2E7D32)
        DatabaseHealthState.WARNING -> Color(0xFFEF6C00)
        DatabaseHealthState.ERROR -> MaterialTheme.colorScheme.error
    }
    val statusLine = when (health) {
        DatabaseHealthState.HEALTHY -> "Everything is going well."
        DatabaseHealthState.WARNING -> if (!state.initialized) {
            "Database initializing."
        } else {
            "Archiving in progress."
        }
        DatabaseHealthState.ERROR -> "Database needs attention."
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                    text = "Database Health",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(healthColor)
                    )
                    Text(
                        text = healthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor
                    )
                }
            }
            Text(
                text = "Live ${state.liveDayEntryCount}  Pending ${state.pendingArchiveCount}  Writes ${state.totalPersistedWrites}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun defaultPlaintextExportFileName(nowMs: Long = System.currentTimeMillis()): String {
    val timestamp = Instant.ofEpochMilli(nowMs)
        .atZone(ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.SECONDS)
        .format(EXPORT_FILE_NAME_TIME_FORMATTER)
    return "blackbox-all-location-data-$timestamp.db"
}
