package com.example.blackbox.ui.screens

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.locationdb.ArchiveRecord
import com.example.blackbox.data.locationdb.ArchiveStatus
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.ui.components.ButtonLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun DatabaseScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persistenceState by LocationPersistenceController.state.collectAsState()

    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var scanVersion by remember { mutableIntStateOf(0) }
    var keyPassphrase by rememberSaveable { mutableStateOf("") }
    var keyPassphraseConfirm by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(context) {
        LocationPersistenceController.initialize(context.applicationContext)
        scanVersion += 1
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            LocationPersistenceController.setArchiveTreeUri(uri)
            message = "Archive folder updated."
            scanVersion += 1
        }
    }

    val exportKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val passphrase = keyPassphrase.toCharArray()
        scope.launch {
            val result = LocationPersistenceController.exportKeyBundle(passphrase = passphrase, target = uri)
            message = result.fold(
                onSuccess = { "Key bundle exported." },
                onFailure = { "Key export failed: ${it.message ?: "unknown error"}" }
            )
            passphrase.fill('\u0000')
        }
    }

    val importKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val passphrase = keyPassphrase.toCharArray()
        scope.launch {
            val result = LocationPersistenceController.importKeyBundle(passphrase = passphrase, source = uri)
            message = result.fold(
                onSuccess = { "Key bundle imported." },
                onFailure = { "Key import failed: ${it.message ?: "unknown error"}" }
            )
            passphrase.fill('\u0000')
        }
    }

    val records by produceState(
        initialValue = emptyList<ArchiveRecord>(),
        scanVersion,
        persistenceState.archiveRootUri,
        persistenceState.pendingArchiveCount,
        persistenceState.lastArchiveAtMs
    ) {
        value = LocationPersistenceController.getArchiveRecords()
    }

    val pendingCount = records.count { it.status == ArchiveStatus.Pending }
    val archivedCount = records.count { it.status == ArchiveStatus.Archived }
    val failedCount = records.count { it.status == ArchiveStatus.Failed }

    val runtimeRows = listOf(
        StatItem("Initialized", yesNo(persistenceState.initialized)),
        StatItem("Archive Folder", persistenceState.archiveRootUri?.toString() ?: "Not configured"),
        StatItem("Live Day Entries", persistenceState.liveDayEntryCount.toString()),
        StatItem("Persisted Writes", persistenceState.totalPersistedWrites.toString()),
        StatItem("Last Write", persistenceState.lastWriteAtMs?.let(::formatTime) ?: "Never"),
        StatItem("Pending Archives", persistenceState.pendingArchiveCount.toString()),
        StatItem("Last Archive", persistenceState.lastArchiveAtMs?.let(::formatTime) ?: "Never"),
        StatItem("Last Archive Message", persistenceState.lastArchiveMessage),
        StatItem("Last Error", persistenceState.lastError ?: "None", isError = persistenceState.lastError != null)
    )

    val scanRows = listOf(
        StatItem("Total Files Seen", records.size.toString()),
        StatItem("Pending Files", pendingCount.toString()),
        StatItem("Archived Files", archivedCount.toString()),
        StatItem("Failed Files", failedCount.toString())
    )

    val recentRows = records
        .sortedByDescending { it.updatedAtMs }
        .take(30)
        .map { record ->
            val statusPrefix = record.status.name.uppercase(Locale.US)
            StatItem(
                label = "$statusPrefix ${record.dayUtc}",
                value = record.safRelativePath.ifBlank { record.localPath },
                isError = record.status == ArchiveStatus.Failed
            )
        }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Database") }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Controls",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ButtonLabel("Choose Folder")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (!persistenceState.initialized) {
                                        message = "Storage is still initializing. Try again in a moment."
                                        return@launch
                                    }
                                    if (persistenceState.archiveRootUri == null) {
                                        message = "Choose a folder before archiving."
                                        return@launch
                                    }
                                    val result = LocationPersistenceController.archiveNow()
                                    message = result.fold(
                                        onSuccess = { "Archived $it file(s)." },
                                        onFailure = { "Archive failed: ${it.message ?: "unknown error"}" }
                                    )
                                    scanVersion += 1
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Archive Now")
                        }
                        OutlinedButton(
                            onClick = { scanVersion += 1 },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Refresh Scan")
                        }
                    }

                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Key bundle passphrase") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        )
                    )

                    OutlinedTextField(
                        value = keyPassphraseConfirm,
                        onValueChange = { keyPassphraseConfirm = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                when {
                                    keyPassphrase.isBlank() -> {
                                        message = "Passphrase is required for export."
                                    }

                                    keyPassphrase != keyPassphraseConfirm -> {
                                        message = "Passphrase confirmation does not match."
                                    }

                                    else -> {
                                        exportKeyLauncher.launch("blackbox-keybundle-v1.json")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Export Key Bundle")
                        }

                        OutlinedButton(
                            onClick = {
                                if (keyPassphrase.isBlank()) {
                                    message = "Passphrase is required for import."
                                } else {
                                    importKeyLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Import Key Bundle")
                        }
                    }

                    if (!message.isNullOrBlank()) {
                        Text(
                            text = message.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            StatBox(
                title = "Runtime",
                summary = "Persistence and archive worker state",
                rows = runtimeRows,
                initiallyExpanded = true
            )
        }

        item {
            StatBox(
                title = "Folder Scan",
                summary = "Live scan across local + SAF folders",
                rows = scanRows,
                initiallyExpanded = true
            )
        }

        item {
            StatBox(
                title = "Recent Files",
                summary = if (recentRows.isEmpty()) "No files found" else "${recentRows.size} newest entries",
                rows = recentRows,
                initiallyExpanded = false
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
private fun StatBox(
    title: String,
    summary: String,
    rows: List<StatItem>,
    initiallyExpanded: Boolean
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
                if (rows.isEmpty()) {
                    Text(
                        text = "No data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    rows.forEachIndexed { index, row ->
                        StatRow(item = row)
                        if (index != rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(item: StatItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = item.value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

private data class StatItem(
    val label: String,
    val value: String,
    val isError: Boolean = false
)

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private val timestampFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private fun formatTime(timestampMillis: Long): String {
    return timestampFormatter.format(Instant.ofEpochMilli(timestampMillis))
}
