package com.example.blackbox.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
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
import com.example.blackbox.ui.components.NeoButton as Button
import com.example.blackbox.ui.components.NeoAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import com.example.blackbox.ui.components.NeoOutlinedButton as OutlinedButton
import com.example.blackbox.ui.components.NeoOutlinedCard as OutlinedCard
import com.example.blackbox.ui.components.NeoOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.example.blackbox.ui.components.NeoTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.ui.components.ButtonLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DATABASE_TOP_BAR_SCROLL_CLEARANCE = 16.dp
private val DATABASE_BOTTOM_BAR_SCROLL_CLEARANCE = 104.dp

@Composable
fun DatabaseScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persistenceState by LocationPersistenceController.state.collectAsState()
    val dbWriteIndicator = rememberDbWriteIndicator(
        initialized = persistenceState.initialized,
        lastError = persistenceState.lastError,
        lastWriteAtMs = persistenceState.lastWriteAtMs
    )
    val integrityIndicator = rememberIntegrityIndicator(
        initialized = persistenceState.initialized,
        archiveConfigured = persistenceState.archiveRootUri != null,
        checkRunning = persistenceState.integrityCheckRunning,
        lastCheckedAtMs = persistenceState.integrityLastCheckedAtMs,
        failedFiles = persistenceState.integrityFailedFiles,
        detailMessage = persistenceState.integrityMessage
    )

    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var plaintextExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var clearAllMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var keyPassphrase by rememberSaveable { mutableStateOf("") }
    var keyPassphraseConfirm by rememberSaveable { mutableStateOf("") }
    var showClearAllConfirm by rememberSaveable { mutableStateOf(false) }
    var clearAllRunning by rememberSaveable { mutableStateOf(false) }

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
            archiveMessage = "Archive folder updated."
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

    val exportPlaintextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            val result = LocationPersistenceController.exportMergedPlaintextDatabase(target = uri)
            plaintextExportMessage = result.fold(
                onSuccess = {
                    "Plaintext merged DB export complete: $it rows."
                },
                onFailure = { "Plaintext export failed: ${it.message ?: "unknown error"}" }
            )
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = DATABASE_TOP_BAR_SCROLL_CLEARANCE,
            end = 20.dp,
            bottom = DATABASE_BOTTOM_BAR_SCROLL_CLEARANCE
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Database Controls") }
        item {
            Text(
                text = dbWriteIndicator.text,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = if (dbWriteIndicator.isGood) GOOD_STATUS_COLOR else BAD_STATUS_COLOR
            )
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val archiveUri = persistenceState.archiveRootUri
                    val archivePath = archiveUri?.let(::resolveArchivePathFromTreeUri) ?: "Not configured"
                    Text(
                        text = "Database Location",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = archivePath,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val canArchiveNow = persistenceState.initialized &&
                            persistenceState.archiveRootUri != null &&
                            persistenceState.liveDayEntryCount > 0L
                        Button(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Choose Folder")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    if (!persistenceState.initialized) {
                                        archiveMessage = "Storage is still initializing. Try again in a moment."
                                        return@launch
                                    }
                                    if (persistenceState.archiveRootUri == null) {
                                        archiveMessage = "Choose a folder before archiving."
                                        return@launch
                                    }
                                    val result = LocationPersistenceController.archiveNow()
                                    archiveMessage = result.fold(
                                        onSuccess = { "Archived $it files." },
                                        onFailure = { "Archive failed: ${it.message ?: "unknown error"}" }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = canArchiveNow
                        ) {
                            ButtonLabel("Archive Now")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            exportPlaintextLauncher.launch(defaultPlaintextExportFileName())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = persistenceState.initialized
                    ) {
                        ButtonLabel("Export Merged Plaintext DB")
                    }
                    OutlinedButton(
                        onClick = { showClearAllConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = persistenceState.initialized && !clearAllRunning
                    ) {
                        ButtonLabel(if (clearAllRunning) "Clearing..." else "Clear All Data")
                    }
                    if (!archiveMessage.isNullOrBlank()) {
                        Text(
                            text = archiveMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!plaintextExportMessage.isNullOrBlank()) {
                        Text(
                            text = plaintextExportMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!clearAllMessage.isNullOrBlank()) {
                        Text(
                            text = clearAllMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "File & Key Integrity",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = integrityIndicator.text,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        color = if (integrityIndicator.isGood) GOOD_STATUS_COLOR else BAD_STATUS_COLOR
                    )
                    Text(
                        text = "Succeeded ${persistenceState.integritySucceededFiles} / Failed ${persistenceState.integrityFailedFiles} / Total ${persistenceState.integrityTotalFiles}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Last check: ${formatStatusTime(persistenceState.integrityLastCheckedAtMs)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                LocationPersistenceController.runArchiveIntegrityCheck()
                            }
                        },
                        enabled = persistenceState.initialized && !persistenceState.integrityCheckRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ButtonLabel(
                            if (persistenceState.integrityCheckRunning) {
                                "Running..."
                            } else {
                                "Run Integrity Check"
                            }
                        )
                    }
                    Text(
                        text = persistenceState.integrityMessage,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Key Management",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

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

                                    keyPassphrase.length < MIN_EXPORT_PASSPHRASE_LENGTH -> {
                                        message = "Use at least $MIN_EXPORT_PASSPHRASE_LENGTH characters for export passphrase."
                                    }

                                    !keyPassphrase.any { it.isLetter() } || !keyPassphrase.any { it.isDigit() } -> {
                                        message = "Use a stronger passphrase (include letters and numbers)."
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
                    Text(
                        text = "Export passphrase recommendation: 12+ chars, mixed letters and numbers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!clearAllRunning) {
                    showClearAllConfirm = false
                }
            },
            title = { Text("Clear all database data?") },
            text = {
                Text(
                    "This deletes all archived files and clears all live/pending database files. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !clearAllRunning,
                    onClick = {
                        clearAllRunning = true
                        scope.launch {
                            val result = LocationPersistenceController.clearAllDatabases()
                            clearAllMessage = result.fold(
                                onSuccess = {
                                    "Cleared live=${it.deletedLiveFiles}, pending=${it.deletedPendingFiles}, archived=${it.deletedArchivedFiles}."
                                },
                                onFailure = { "Clear all failed: ${it.message ?: "unknown error"}" }
                            )
                            clearAllRunning = false
                            showClearAllConfirm = false
                        }
                    }
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearAllRunning,
                    onClick = { showClearAllConfirm = false }
                ) {
                    Text("Cancel")
                }
            }
        )
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

private fun resolveArchivePathFromTreeUri(uri: Uri): String {
    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return uri.toString()

    if (treeDocId.startsWith("raw:")) {
        return treeDocId.removePrefix("raw:")
    }

    val split = treeDocId.split(':', limit = 2)
    if (split.size != 2) {
        return treeDocId
    }

    val volume = split[0]
    val rel = split[1].trim('/')

    return when (volume) {
        "primary" -> if (rel.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
        "home" -> if (rel.isBlank()) "/storage/emulated/0/Documents" else "/storage/emulated/0/Documents/$rel"
        else -> if (rel.isBlank()) "/storage/$volume" else "/storage/$volume/$rel"
    }
}

private data class DbWriteIndicator(
    val text: String,
    val isGood: Boolean
)

private data class IntegrityIndicator(
    val text: String,
    val isGood: Boolean
)

private fun rememberDbWriteIndicator(
    initialized: Boolean,
    lastError: String?,
    lastWriteAtMs: Long?
): DbWriteIndicator {
    return when {
        !initialized -> DbWriteIndicator("DB write status: INIT PENDING", isGood = false)
        lastError != null -> DbWriteIndicator("DB write status: ERROR DETECTED", isGood = false)
        lastWriteAtMs == null -> DbWriteIndicator("DB write status: NO SUCCESSFUL WRITE YET", isGood = false)
        else -> DbWriteIndicator("DB write status: HEALTHY", isGood = true)
    }
}

private fun rememberIntegrityIndicator(
    initialized: Boolean,
    archiveConfigured: Boolean,
    checkRunning: Boolean,
    lastCheckedAtMs: Long?,
    failedFiles: Int,
    detailMessage: String
): IntegrityIndicator {
    return when {
        !initialized -> IntegrityIndicator("Integrity status: INIT PENDING", isGood = false)
        checkRunning -> IntegrityIndicator("Integrity status: RUNNING", isGood = false)
        !archiveConfigured -> IntegrityIndicator("Integrity status: FOLDER NOT CONFIGURED", isGood = false)
        lastCheckedAtMs == null -> IntegrityIndicator("Integrity status: NOT CHECKED YET", isGood = false)
        failedFiles > 0 -> IntegrityIndicator("Integrity status: FAILURES DETECTED", isGood = false)
        detailMessage.startsWith("Archive folder", ignoreCase = true) -> IntegrityIndicator(
            "Integrity status: CHECK FAILED",
            isGood = false
        )
        else -> IntegrityIndicator("Integrity status: HEALTHY", isGood = true)
    }
}

private fun formatStatusTime(timestampMs: Long?): String {
    if (timestampMs == null) {
        return "Never"
    }
    return Instant.ofEpochMilli(timestampMs)
        .atZone(ZoneId.systemDefault())
        .format(DB_STATUS_TIME_FORMATTER)
}

private fun defaultPlaintextExportFileName(nowMs: Long = System.currentTimeMillis()): String {
    val timestamp = Instant.ofEpochMilli(nowMs)
        .atZone(ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.SECONDS)
        .format(EXPORT_FILE_NAME_TIME_FORMATTER)
    return "blackbox-all-databases-merged-$timestamp.db"
}

private val GOOD_STATUS_COLOR = Color(0xFF2E7D32)
private val BAD_STATUS_COLOR = Color(0xFFC62828)
private const val MIN_EXPORT_PASSPHRASE_LENGTH = 12
private val DB_STATUS_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
private val EXPORT_FILE_NAME_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
