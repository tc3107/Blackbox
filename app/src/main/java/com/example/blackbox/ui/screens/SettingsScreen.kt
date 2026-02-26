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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.sharing.MAX_FAST_INTERVAL_MS
import com.example.blackbox.sharing.MAX_NORMAL_INTERVAL_MS
import com.example.blackbox.sharing.MIN_FAST_INTERVAL_MS
import com.example.blackbox.sharing.MIN_NORMAL_INTERVAL_MS
import com.example.blackbox.sharing.SharingSettings
import com.example.blackbox.sharing.USERNAME_MAX_LENGTH
import com.example.blackbox.sharing.USERNAME_MIN_LENGTH
import com.example.blackbox.sharing.isValidUsername
import com.example.blackbox.ui.components.ButtonLabel
import com.example.blackbox.ui.theme.accentColorFromHex
import com.example.blackbox.ui.theme.normalizeAccentHex
import kotlinx.coroutines.launch

private const val SETTINGS_DIALOG_WIDTH_FRACTION = 0.96f

@Composable
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

    var inputHex by rememberSaveable(settings.customAccentHex) {
        mutableStateOf(settings.customAccentHex.orEmpty())
    }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

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
        LocationSharingController.initialize(context.applicationContext)
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

    fun missingOrInvalidEditor(config: SharingSettings): SharingConfigEditor? {
        if (!isValidUsername(config.username)) return SharingConfigEditor.Username
        if (!isValidRelayBaseUrl(config.relayBaseUrl)) return SharingConfigEditor.RelayUrl
        if (config.normalIntervalMs !in MIN_NORMAL_INTERVAL_MS..MAX_NORMAL_INTERVAL_MS) {
            return SharingConfigEditor.NormalIntervalMinutes
        }
        if (config.fastIntervalMs !in MIN_FAST_INTERVAL_MS..MAX_FAST_INTERVAL_MS) {
            return SharingConfigEditor.FastIntervalSeconds
        }
        return null
    }

    val activeAccentColor = settings.customAccentHex?.let(::accentColorFromHex)
        ?: MaterialTheme.colorScheme.primary
    val modeDescription = if (settings.customAccentHex == null) {
        "Automatic mode: system Material 3 colors when available, otherwise terminal green."
    } else {
        "Custom mode: #${settings.customAccentHex}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Share my location", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = sharingState.settings.sharingEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                LocationSharingController.setSharingEnabled(false)
                                return@Switch
                            }
                            val missing = missingOrInvalidEditor(sharingState.settings)
                            if (missing != null) {
                                openSettingsEditor(missing)
                                statusMessage = when (missing) {
                                    SharingConfigEditor.Username -> "Username required before enabling Share My Location."
                                    SharingConfigEditor.RelayUrl -> "Relay URL is invalid. Update it before enabling Share My Location."
                                    SharingConfigEditor.NormalIntervalMinutes -> "Normal interval is invalid. Update it before enabling Share My Location."
                                    SharingConfigEditor.FastIntervalSeconds -> "Fast interval is invalid. Update it before enabling Share My Location."
                                }
                                return@Switch
                            }
                            LocationSharingController.setSharingEnabled(true)
                        }
                    )
                }
                Text(
                    text = "Username: ${sharingState.settings.username.ifBlank { "(unset)" }}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = "Relay: ${sharingState.settings.relayBaseUrl}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
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

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Text(text = modeDescription, style = MaterialTheme.typography.bodyMedium)
                ColorPreviewChip(color = activeAccentColor)
                OutlinedTextField(
                    value = inputHex,
                    onValueChange = {
                        inputHex = it.trim().removePrefix("#")
                        validationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Custom accent hex") },
                    placeholder = { Text(text = "00FF66", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    prefix = { Text("#") },
                    isError = validationError != null,
                    supportingText = { Text(text = validationError ?: "Use a 6-digit RGB hex value.") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val normalized = normalizeAccentHex(inputHex)
                            if (normalized == null) {
                                validationError = "Invalid color value. Use 6 hex digits."
                            } else {
                                onCustomAccentSaved(normalized)
                                inputHex = normalized
                                validationError = null
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel("Apply Override")
                    }
                    OutlinedButton(
                        onClick = {
                            onCustomAccentSaved(null)
                            inputHex = ""
                            validationError = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel("Use Automatic")
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

@Composable
private fun ColorPreviewChip(color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color = color, shape = CircleShape)
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
        )
        Text(
            text = "Active accent preview",
            style = MaterialTheme.typography.bodyMedium
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
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text(label) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
