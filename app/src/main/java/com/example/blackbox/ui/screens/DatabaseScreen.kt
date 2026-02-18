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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.ui.components.ButtonLabel
import kotlinx.coroutines.launch

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

    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var keyPassphrase by rememberSaveable { mutableStateOf("") }
    var keyPassphraseConfirm by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(context) {
        LocationPersistenceController.initialize(context.applicationContext)
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
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
                    if (!archiveMessage.isNullOrBlank()) {
                        Text(
                            text = archiveMessage.orEmpty(),
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

private val GOOD_STATUS_COLOR = Color(0xFF2E7D32)
private val BAD_STATUS_COLOR = Color(0xFFC62828)
