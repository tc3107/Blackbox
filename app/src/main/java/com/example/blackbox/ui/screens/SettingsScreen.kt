package com.example.blackbox.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.example.blackbox.ui.components.NeoAlertDialog as AlertDialog
import com.example.blackbox.ui.components.NeoButton as Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.sharing.MAX_FAST_INTERVAL_MS
import com.example.blackbox.sharing.MAX_NORMAL_INTERVAL_MS
import com.example.blackbox.sharing.MIN_FAST_INTERVAL_MS
import com.example.blackbox.sharing.MIN_NORMAL_INTERVAL_MS
import com.example.blackbox.sharing.USERNAME_MAX_LENGTH
import com.example.blackbox.sharing.USERNAME_MIN_LENGTH
import com.example.blackbox.sharing.isValidUsername
import com.example.blackbox.ui.components.ButtonLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SETTINGS_DIALOG_WIDTH_FRACTION = 0.96f
private val SETTINGS_TOP_BAR_SCROLL_CLEARANCE = 16.dp
private val SETTINGS_BOTTOM_BAR_SCROLL_CLEARANCE = 104.dp

@Composable
@Suppress("UNUSED_PARAMETER")
fun SettingsScreen(
    settings: UiSettings,
    onCustomAccentSaved: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharingState by LocationSharingController.state.collectAsState()

    var activeSettingsEditor by remember { mutableStateOf<SharingConfigEditor?>(null) }
    var settingsEditorInput by rememberSaveable { mutableStateOf("") }
    var settingsEditorError by rememberSaveable { mutableStateOf<String?>(null) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var backupAction by rememberSaveable { mutableStateOf<BackupAction?>(null) }
    var backupPassphrase by rememberSaveable { mutableStateOf("") }
    var backupPassphraseConfirm by rememberSaveable { mutableStateOf("") }
    var backupDialogError by rememberSaveable { mutableStateOf<String?>(null) }

    val exportIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val passphrase = backupPassphrase.toCharArray()
        val confirm = backupPassphraseConfirm.toCharArray()
        backupPassphrase = ""
        backupPassphraseConfirm = ""
        backupAction = null
        backupDialogError = null
        scope.launch {
            val result = LocationSharingController.exportIdentityBundle(passphrase = passphrase, target = uri)
            statusMessage = result.fold(
                onSuccess = { "Identity bundle exported." },
                onFailure = { "Identity export failed: ${it.message ?: "unknown"}" }
            )
            passphrase.fill('\u0000')
            confirm.fill('\u0000')
        }
    }

    val importIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val passphrase = backupPassphrase.toCharArray()
        val confirm = backupPassphraseConfirm.toCharArray()
        backupPassphrase = ""
        backupPassphraseConfirm = ""
        backupAction = null
        backupDialogError = null
        scope.launch {
            val result = LocationSharingController.importIdentityBundle(passphrase = passphrase, source = uri)
            statusMessage = result.fold(
                onSuccess = { "Identity bundle imported." },
                onFailure = { "Identity import failed: ${it.message ?: "unknown"}" }
            )
            passphrase.fill('\u0000')
            confirm.fill('\u0000')
        }
    }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            LocationSharingController.initialize(context.applicationContext)
        }
    }

    fun openSettingsEditor(editor: SharingConfigEditor) {
        activeSettingsEditor = editor
        settingsEditorError = null
        settingsEditorInput = when (editor) {
            SharingConfigEditor.Username -> sharingState.settings.username
            SharingConfigEditor.RelayUrl -> sharingState.settings.relayBaseUrl
            SharingConfigEditor.NormalIntervalMinutes -> (sharingState.settings.normalIntervalMs / 60_000L).toString()
            SharingConfigEditor.FastIntervalSeconds -> (sharingState.settings.fastIntervalMs / 1_000L).toString()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                top = SETTINGS_TOP_BAR_SCROLL_CLEARANCE,
                end = 20.dp,
                bottom = SETTINGS_BOTTOM_BAR_SCROLL_CLEARANCE
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Sharing Configuration", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { openSettingsEditor(SharingConfigEditor.Username) }, modifier = Modifier.weight(1f)) {
                        ButtonLabel("Edit Username")
                    }
                    OutlinedButton(onClick = { openSettingsEditor(SharingConfigEditor.RelayUrl) }, modifier = Modifier.weight(1f)) {
                        ButtonLabel("Edit Relay URL")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { openSettingsEditor(SharingConfigEditor.NormalIntervalMinutes) },
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel("Edit Normal Interval")
                    }
                    OutlinedButton(
                        onClick = { openSettingsEditor(SharingConfigEditor.FastIntervalSeconds) },
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel("Edit Fast Interval")
                    }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Identity Backup", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            backupAction = BackupAction.Export
                            backupPassphrase = ""
                            backupPassphraseConfirm = ""
                            backupDialogError = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel("Export Identity")
                    }
                    OutlinedButton(
                        onClick = {
                            backupAction = BackupAction.Import
                            backupPassphrase = ""
                            backupPassphraseConfirm = ""
                            backupDialogError = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel("Import Identity")
                    }
                }
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (activeSettingsEditor != null) {
        val editor = activeSettingsEditor ?: return
        SettingsEditorDialog(
            editor = editor,
            input = settingsEditorInput,
            error = settingsEditorError,
            onInputChange = {
                settingsEditorError = null
                settingsEditorInput = when (editor) {
                    SharingConfigEditor.NormalIntervalMinutes,
                    SharingConfigEditor.FastIntervalSeconds -> it.filter(Char::isDigit)
                    else -> it
                }
            },
            onDismiss = {
                activeSettingsEditor = null
                settingsEditorError = null
            },
            onConfirm = {
                when (editor) {
                    SharingConfigEditor.Username -> {
                        val trimmed = settingsEditorInput.trim()
                        if (!isValidUsername(trimmed)) {
                            settingsEditorError = "Username must be $USERNAME_MIN_LENGTH-$USERNAME_MAX_LENGTH characters."
                            return@SettingsEditorDialog
                        }
                        LocationSharingController.setUsername(trimmed)
                    }
                    SharingConfigEditor.RelayUrl -> {
                        val normalized = settingsEditorInput.trim().trimEnd('/')
                        if (!isValidRelayBaseUrl(normalized)) {
                            settingsEditorError = "Relay URL must start with http:// or https:// and include host."
                            return@SettingsEditorDialog
                        }
                        LocationSharingController.setRelayBaseUrl(normalized)
                    }
                    SharingConfigEditor.NormalIntervalMinutes -> {
                        val minutes = settingsEditorInput.toLongOrNull()
                        val minValue = MIN_NORMAL_INTERVAL_MS / 60_000L
                        val maxValue = MAX_NORMAL_INTERVAL_MS / 60_000L
                        if (minutes == null || minutes !in minValue..maxValue) {
                            settingsEditorError = "Normal interval must be $minValue-$maxValue minutes."
                            return@SettingsEditorDialog
                        }
                        LocationSharingController.setIntervals(
                            normalMs = minutes * 60_000L,
                            fastMs = sharingState.settings.fastIntervalMs
                        )
                    }
                    SharingConfigEditor.FastIntervalSeconds -> {
                        val seconds = settingsEditorInput.toLongOrNull()
                        val minValue = MIN_FAST_INTERVAL_MS / 1_000L
                        val maxValue = MAX_FAST_INTERVAL_MS / 1_000L
                        if (seconds == null || seconds !in minValue..maxValue) {
                            settingsEditorError = "Fast interval must be $minValue-$maxValue seconds."
                            return@SettingsEditorDialog
                        }
                        LocationSharingController.setIntervals(
                            normalMs = sharingState.settings.normalIntervalMs,
                            fastMs = seconds * 1_000L
                        )
                    }
                }
                activeSettingsEditor = null
                settingsEditorError = null
            }
        )
    }

    if (backupAction != null) {
        val action = backupAction ?: return
        AlertDialog(
            modifier = Modifier.fillMaxWidth(SETTINGS_DIALOG_WIDTH_FRACTION),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = {
                backupAction = null
                backupPassphrase = ""
                backupPassphraseConfirm = ""
                backupDialogError = null
            },
            title = {
                Text(if (action == BackupAction.Export) "Export Identity" else "Import Identity")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = {
                            backupPassphrase = it
                            backupDialogError = null
                        },
                        label = { Text("Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = backupPassphraseConfirm,
                        onValueChange = {
                            backupPassphraseConfirm = it
                            backupDialogError = null
                        },
                        label = { Text("Confirm passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (!backupDialogError.isNullOrBlank()) {
                        Text(
                            text = backupDialogError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (backupPassphrase.isBlank()) {
                            backupDialogError = "Passphrase required."
                            return@TextButton
                        }
                        if (backupPassphrase != backupPassphraseConfirm) {
                            backupDialogError = "Passphrases must match."
                            return@TextButton
                        }
                        if (action == BackupAction.Export) {
                            exportIdentityLauncher.launch("blackbox-identity-bundle.json")
                        } else {
                            importIdentityLauncher.launch(arrayOf("application/json"))
                        }
                    }
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        backupAction = null
                        backupPassphrase = ""
                        backupPassphraseConfirm = ""
                        backupDialogError = null
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

private enum class SharingConfigEditor {
    Username,
    RelayUrl,
    NormalIntervalMinutes,
    FastIntervalSeconds
}

private enum class BackupAction {
    Export,
    Import
}

@Composable
private fun SettingsEditorDialog(
    editor: SharingConfigEditor,
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = when (editor) {
        SharingConfigEditor.Username -> "Edit Username"
        SharingConfigEditor.RelayUrl -> "Edit Relay URL"
        SharingConfigEditor.NormalIntervalMinutes -> "Edit Normal Interval (minutes)"
        SharingConfigEditor.FastIntervalSeconds -> "Edit Fast Interval (seconds)"
    }
    val keyboardType = when (editor) {
        SharingConfigEditor.RelayUrl -> KeyboardType.Uri
        SharingConfigEditor.NormalIntervalMinutes, SharingConfigEditor.FastIntervalSeconds -> KeyboardType.Number
        SharingConfigEditor.Username -> KeyboardType.Text
    }
    val label = when (editor) {
        SharingConfigEditor.Username -> "Username"
        SharingConfigEditor.RelayUrl -> "Relay URL"
        SharingConfigEditor.NormalIntervalMinutes -> "Minutes"
        SharingConfigEditor.FastIntervalSeconds -> "Seconds"
    }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(SETTINGS_DIALOG_WIDTH_FRACTION),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text(label) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun isValidRelayBaseUrl(baseUrl: String): Boolean {
    val normalized = baseUrl.trim()
    if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
        return false
    }
    val uri = runCatching { android.net.Uri.parse(normalized) }.getOrNull() ?: return false
    return !uri.host.isNullOrBlank()
}
