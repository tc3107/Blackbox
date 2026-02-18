package com.example.blackbox.ui.screens

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

    var message by rememberSaveable { mutableStateOf<String?>(null) }
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
            message = "Archive folder updated."
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
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Database Location",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = persistenceState.archiveRootUri?.toString() ?: "Not configured",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Archive Now")
                        }
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
