package com.example.blackbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.sharing.LocationSharingState
import com.example.blackbox.sharing.ZONE_NAME_MAX_LENGTH
import com.example.blackbox.sharing.ZONE_NAME_MIN_LENGTH
import com.example.blackbox.sharing.ZONE_RADIUS_MAX_METERS
import com.example.blackbox.sharing.ZONE_RADIUS_MIN_METERS
import com.example.blackbox.sharing.ShareZone
import com.example.blackbox.sharing.SharingSettings
import com.example.blackbox.sharing.USERNAME_MAX_LENGTH
import com.example.blackbox.sharing.USERNAME_MIN_LENGTH
import com.example.blackbox.sharing.MAX_FAST_INTERVAL_MS
import com.example.blackbox.sharing.MAX_NORMAL_INTERVAL_MS
import com.example.blackbox.sharing.MIN_FAST_INTERVAL_MS
import com.example.blackbox.sharing.MIN_NORMAL_INTERVAL_MS
import com.example.blackbox.sharing.hasSharingNetworkPermissions
import com.example.blackbox.sharing.isValidUsername
import com.example.blackbox.ui.components.ButtonLabel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DIALOG_WIDTH_FRACTION = 0.96f

@Composable
fun SharingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharingState by LocationSharingController.state.collectAsState()
    val locationState by LocationEngine.state.collectAsState()

    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var activeSettingsEditor by remember { mutableStateOf<SettingsEditor?>(null) }
    var settingsEditorInput by rememberSaveable { mutableStateOf("") }
    var settingsEditorError by rememberSaveable { mutableStateOf<String?>(null) }
    var shareCodeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var scanCodeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var shareManualCodeInput by rememberSaveable { mutableStateOf("") }
    var shareManualCodeError by rememberSaveable { mutableStateOf<String?>(null) }
    var scanManualCodeInput by rememberSaveable { mutableStateOf("") }
    var scanManualCodeError by rememberSaveable { mutableStateOf<String?>(null) }
    var createZoneDialogVisible by rememberSaveable { mutableStateOf(false) }
    var zoneDialogName by rememberSaveable { mutableStateOf("") }
    var zoneDialogRadius by rememberSaveable { mutableStateOf("100") }
    var zoneDialogError by rememberSaveable { mutableStateOf<String?>(null) }
    var passphraseInput by rememberSaveable { mutableStateOf("") }
    var passphraseConfirmInput by rememberSaveable { mutableStateOf("") }
    var networkPermissionGranted by rememberSaveable {
        mutableStateOf(context.applicationContext.hasSharingNetworkPermissions())
    }

    val aliasDrafts = remember { mutableStateMapOf<String, String>() }

    val exportIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val passphrase = passphraseInput.toCharArray()
        scope.launch {
            val result = LocationSharingController.exportIdentityBundle(passphrase = passphrase, target = uri)
            statusMessage = result.fold(
                onSuccess = { "Identity bundle exported." },
                onFailure = { "Identity export failed: ${it.message ?: "unknown"}" }
            )
            passphrase.fill('\u0000')
        }
    }

    val importIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val passphrase = passphraseInput.toCharArray()
        scope.launch {
            val result = LocationSharingController.importIdentityBundle(passphrase = passphrase, source = uri)
            statusMessage = result.fold(
                onSuccess = { "Identity bundle imported." },
                onFailure = { "Identity import failed: ${it.message ?: "unknown"}" }
            )
            passphrase.fill('\u0000')
        }
    }

    fun importContactCodeAndHandle(
        rawCode: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val code = rawCode.trim()
        if (code.isBlank()) {
            onFailure("Location code cannot be empty.")
            return
        }
        scope.launch {
            val importResult = LocationSharingController.importContactCard(code)
            importResult
                .onSuccess {
                    statusMessage = "Imported ${it.displayName} (fingerprint ${it.safetyFingerprint})."
                    onSuccess()
                }
                .onFailure {
                    onFailure("Import failed: ${it.message ?: "unknown"}")
                }
        }
    }

    val scanQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.trim().orEmpty()
        if (scanned.isBlank()) {
            return@rememberLauncherForActivityResult
        }
        importContactCodeAndHandle(
            rawCode = scanned,
            onSuccess = {
                scanCodeDialogVisible = false
                scanManualCodeInput = ""
                scanManualCodeError = null
            },
            onFailure = { error ->
                scanManualCodeError = error
            }
        )
    }

    LaunchedEffect(context) {
        val appContext = context.applicationContext
        LocationSharingController.initialize(appContext)
        networkPermissionGranted = appContext.hasSharingNetworkPermissions()
        if (!networkPermissionGranted) {
            statusMessage = "Network permission is missing. Update/reinstall app and reopen Location Sharing."
        }
    }

    DisposableEffect(Unit) {
        LocationSharingController.onSharingPageVisible(true)
        onDispose {
            LocationSharingController.onSharingPageVisible(false)
        }
    }

    fun openSettingsEditor(editor: SettingsEditor) {
        activeSettingsEditor = editor
        settingsEditorError = null
        settingsEditorInput = when (editor) {
            SettingsEditor.Username -> sharingState.settings.username
            SettingsEditor.RelayUrl -> sharingState.settings.relayBaseUrl
            SettingsEditor.NormalIntervalMinutes -> (sharingState.settings.normalIntervalMs / 60_000L).toString()
            SettingsEditor.FastIntervalSeconds -> (sharingState.settings.fastIntervalMs / 1_000L).toString()
        }
    }

    fun missingOrInvalidEditor(settings: SharingSettings): SettingsEditor? {
        if (!isValidUsername(settings.username)) {
            return SettingsEditor.Username
        }
        if (!isValidRelayBaseUrl(settings.relayBaseUrl)) {
            return SettingsEditor.RelayUrl
        }
        if (settings.normalIntervalMs !in MIN_NORMAL_INTERVAL_MS..MAX_NORMAL_INTERVAL_MS) {
            return SettingsEditor.NormalIntervalMinutes
        }
        if (settings.fastIntervalMs !in MIN_FAST_INTERVAL_MS..MAX_FAST_INTERVAL_MS) {
            return SettingsEditor.FastIntervalSeconds
        }
        return null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RelayConnectionStatusBar(sharingState)
        }

        item {
            Text(
                text = "Location Sharing",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            SharingStatusHeader(sharingState = sharingState)
        }

        if (!networkPermissionGranted) {
            item {
                Text(
                    text = "Network permissions are required for relay communication. If this persists after updating, reinstall the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            MySharingSection(
                state = sharingState,
                settingsExpanded = settingsExpanded,
                onSettingsExpandedChange = { settingsExpanded = it },
                onEditUsername = { openSettingsEditor(SettingsEditor.Username) },
                onEditRelayUrl = { openSettingsEditor(SettingsEditor.RelayUrl) },
                onEditNormalInterval = { openSettingsEditor(SettingsEditor.NormalIntervalMinutes) },
                onEditFastInterval = { openSettingsEditor(SettingsEditor.FastIntervalSeconds) },
                onToggleSharing = { enabled ->
                    if (!enabled) {
                        LocationSharingController.setSharingEnabled(false)
                        return@MySharingSection
                    }

                    val missing = missingOrInvalidEditor(sharingState.settings)
                    if (missing != null) {
                        openSettingsEditor(missing)
                        statusMessage = when (missing) {
                            SettingsEditor.Username -> "Username required before enabling Share My Location."
                            SettingsEditor.RelayUrl -> "Relay URL is invalid. Update it before enabling Share My Location."
                            SettingsEditor.NormalIntervalMinutes -> "Normal interval is invalid. Update it before enabling Share My Location."
                            SettingsEditor.FastIntervalSeconds -> "Fast interval is invalid. Update it before enabling Share My Location."
                        }
                        return@MySharingSection
                    }
                    LocationSharingController.setSharingEnabled(true)
                },
            )
        }

        item {
            OnboardingSection(
                onOpenShareCode = {
                    shareCodeDialogVisible = true
                    shareManualCodeInput = ""
                    shareManualCodeError = null
                },
                onOpenScanCode = {
                    scanCodeDialogVisible = true
                    scanManualCodeInput = ""
                    scanManualCodeError = null
                }
            )
        }

        item {
            ContactsSection(
                state = sharingState,
                aliasDrafts = aliasDrafts,
                onToggleShareTo = { senderId, checked ->
                    LocationSharingController.setOutboundAuthorization(senderId, checked)
                },
                onToggleFollow = { senderId, checked ->
                    LocationSharingController.setFollowing(senderId, checked)
                },
                onAliasApply = { senderId ->
                    LocationSharingController.setLocalAlias(senderId, aliasDrafts[senderId])
                },
                onRemoveContact = { senderId ->
                    LocationSharingController.removeContact(senderId)
                }
            )
        }

        item {
            ZonesSection(
                zones = sharingState.zones,
                canCreate = locationState.bestPositionFix != null,
                onCreateZone = {
                    createZoneDialogVisible = true
                    zoneDialogName = ""
                    zoneDialogRadius = "100"
                    zoneDialogError = null
                },
                onDeleteZone = { id ->
                    LocationSharingController.removeZone(id)
                }
            )
        }

        item {
            SyncSection(
                state = sharingState,
                onPollNow = { LocationSharingController.manualPollNow() },
                onClearRelay = { LocationSharingController.clearRelayLocationNow() }
            )
        }

        item {
            IdentityBackupSection(
                passphraseInput = passphraseInput,
                passphraseConfirmInput = passphraseConfirmInput,
                onPassphraseInputChange = { passphraseInput = it },
                onPassphraseConfirmInputChange = { passphraseConfirmInput = it },
                onExport = {
                    if (passphraseInput.isBlank() || passphraseInput != passphraseConfirmInput) {
                        statusMessage = "Passphrase must be non-empty and match confirmation."
                        return@IdentityBackupSection
                    }
                    exportIdentityLauncher.launch("blackbox-identity-bundle.json")
                },
                onImport = {
                    if (passphraseInput.isBlank()) {
                        statusMessage = "Passphrase required for import."
                        return@IdentityBackupSection
                    }
                    importIdentityLauncher.launch(arrayOf("application/json"))
                }
            )
        }

        if (!statusMessage.isNullOrBlank()) {
            item {
                Text(
                    text = statusMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!sharingState.lastError.isNullOrBlank()) {
            item {
                Text(
                    text = sharingState.lastError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        item {
            ReceivedCardsSection(state = sharingState)
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
                    SettingsEditor.NormalIntervalMinutes,
                    SettingsEditor.FastIntervalSeconds -> it.filter(Char::isDigit)
                    else -> it
                }
            },
            onDismiss = {
                activeSettingsEditor = null
                settingsEditorError = null
            },
            onConfirm = {
                when (editor) {
                    SettingsEditor.Username -> {
                        val trimmed = settingsEditorInput.trim()
                        if (!isValidUsername(trimmed)) {
                            settingsEditorError = "Username must be $USERNAME_MIN_LENGTH-$USERNAME_MAX_LENGTH characters."
                            return@SettingsEditorDialog
                        }
                        LocationSharingController.setUsername(trimmed)
                        statusMessage = "Username updated."
                    }
                    SettingsEditor.RelayUrl -> {
                        val normalized = settingsEditorInput.trim().trimEnd('/')
                        if (!isValidRelayBaseUrl(normalized)) {
                            settingsEditorError = "Relay URL must start with http:// or https:// and include host."
                            return@SettingsEditorDialog
                        }
                        LocationSharingController.setRelayBaseUrl(normalized)
                        statusMessage = "Relay URL updated."
                    }
                    SettingsEditor.NormalIntervalMinutes -> {
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
                        statusMessage = "Normal interval updated."
                    }
                    SettingsEditor.FastIntervalSeconds -> {
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
                        statusMessage = "Fast interval updated."
                    }
                }
                activeSettingsEditor = null
                settingsEditorError = null
            }
        )
    }

    if (shareCodeDialogVisible) {
        ShareLocationCodeDialog(
            pasteInput = shareManualCodeInput,
            pasteError = shareManualCodeError,
            myCode = sharingState.myContactCode,
            onDismiss = {
                shareCodeDialogVisible = false
                shareManualCodeInput = ""
                shareManualCodeError = null
            },
            onPasteInputChange = {
                shareManualCodeInput = it
                shareManualCodeError = null
            },
            onCopyCode = {
                val myCode = sharingState.myContactCode
                if (myCode.isNullOrBlank()) {
                    shareManualCodeError = "Location code is not available yet."
                    return@ShareLocationCodeDialog
                }
                copyToClipboard(context, "blackbox_location_code", myCode)
                statusMessage = "Location code copied."
            },
            onVerifyPaste = {
                importContactCodeAndHandle(
                    rawCode = shareManualCodeInput,
                    onSuccess = {
                        shareCodeDialogVisible = false
                        shareManualCodeInput = ""
                        shareManualCodeError = null
                    },
                    onFailure = { error ->
                        shareManualCodeError = error
                    }
                )
            }
        )
    }

    if (scanCodeDialogVisible) {
        ScanLocationCodeDialog(
            pasteInput = scanManualCodeInput,
            pasteError = scanManualCodeError,
            onDismiss = {
                scanCodeDialogVisible = false
                scanManualCodeInput = ""
                scanManualCodeError = null
            },
            onPasteInputChange = {
                scanManualCodeInput = it
                scanManualCodeError = null
            },
            onScanCamera = {
                val options = ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan a Blackbox location code")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                scanQrLauncher.launch(options)
            },
            onVerifyPaste = {
                importContactCodeAndHandle(
                    rawCode = scanManualCodeInput,
                    onSuccess = {
                        scanCodeDialogVisible = false
                        scanManualCodeInput = ""
                        scanManualCodeError = null
                    },
                    onFailure = { error ->
                        scanManualCodeError = error
                    }
                )
            }
        )
    }

    if (createZoneDialogVisible) {
        CreateZoneDialog(
            zoneNameInput = zoneDialogName,
            zoneRadiusInput = zoneDialogRadius,
            error = zoneDialogError,
            hasFix = locationState.bestPositionFix != null,
            onZoneNameChange = {
                zoneDialogName = it
                zoneDialogError = null
            },
            onZoneRadiusChange = {
                zoneDialogRadius = it.filter(Char::isDigit)
                zoneDialogError = null
            },
            onDismiss = {
                createZoneDialogVisible = false
                zoneDialogError = null
            },
            onConfirm = {
                val trimmedName = zoneDialogName.trim()
                if (trimmedName.length !in ZONE_NAME_MIN_LENGTH..ZONE_NAME_MAX_LENGTH) {
                    zoneDialogError = "Zone name must be $ZONE_NAME_MIN_LENGTH-$ZONE_NAME_MAX_LENGTH characters."
                    return@CreateZoneDialog
                }

                val radius = zoneDialogRadius.toIntOrNull()
                if (radius == null || radius !in ZONE_RADIUS_MIN_METERS..ZONE_RADIUS_MAX_METERS) {
                    zoneDialogError = "Radius must be $ZONE_RADIUS_MIN_METERS-$ZONE_RADIUS_MAX_METERS meters."
                    return@CreateZoneDialog
                }

                val position = locationState.bestPositionFix
                if (position == null) {
                    zoneDialogError = "Need a valid location fix to create a zone."
                    return@CreateZoneDialog
                }

                LocationSharingController.addOrUpdateZone(
                    zoneId = null,
                    name = trimmedName,
                    centerLat = position.location.latitude,
                    centerLon = position.location.longitude,
                    radiusM = radius
                )
                statusMessage = "Zone created."
                createZoneDialogVisible = false
                zoneDialogError = null
                zoneDialogName = ""
                zoneDialogRadius = "100"
            }
        )
    }
}

@Composable
private fun RelayConnectionStatusBar(sharingState: LocationSharingState) {
    val sync = sharingState.sync
    val title: String
    val detail: String
    val containerColor: Color
    val contentColor: Color
    val dotColor: Color
    val checkingContainerColor = Color(0xFFFFF3E0)
    val checkingContentColor = Color(0xFF8A4B00)
    val checkingDotColor = Color(0xFFFB8C00)

    when {
        sync.relayStatusChecking && sync.relayReachable == null -> {
            title = "Checking relay connection"
            detail = ""
            containerColor = checkingContainerColor
            contentColor = checkingContentColor
            dotColor = checkingDotColor
        }
        sync.relayStatusChecking -> {
            title = if (sync.relayReachable == true) "Relay connected" else "Checking relay connection"
            detail = ""
            containerColor = checkingContainerColor
            contentColor = checkingContentColor
            dotColor = checkingDotColor
        }
        sync.relayReachable == true -> {
            title = "Relay connected"
            detail = ""
            containerColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            dotColor = Color(0xFF2E7D32)
        }
        sync.relayReachable == false -> {
            title = "Relay unreachable"
            detail = sync.lastRelayStatusError ?: "Last check ${formatTime(sync.lastRelayStatusCheckAtMs)}"
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            dotColor = MaterialTheme.colorScheme.error
        }
        else -> {
            title = "Relay status unknown"
            detail = "Open this page to check relay connectivity."
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            dotColor = MaterialTheme.colorScheme.outline
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(dotColor, CircleShape)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, shape: Shape) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(shape)
            .background(color)
    )
}

@Composable
private fun SharingStatusHeader(sharingState: LocationSharingState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "System Status",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Sharing: ${if (sharingState.settings.sharingEnabled) "Enabled" else "Disabled"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Recipients: ${sharingState.outboundRecipientsCount} | Following: ${sharingState.followingCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = sharingState.lastInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MySharingSection(
    state: LocationSharingState,
    settingsExpanded: Boolean,
    onSettingsExpandedChange: (Boolean) -> Unit,
    onEditUsername: () -> Unit,
    onEditRelayUrl: () -> Unit,
    onEditNormalInterval: () -> Unit,
    onEditFastInterval: () -> Unit,
    onToggleSharing: (Boolean) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Share My Location", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Share to ${state.outboundRecipientsCount} contact(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Share my location", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = state.settings.sharingEnabled,
                        onCheckedChange = onToggleSharing
                    )
                }
            }
            Text(
                text = "Username: ${state.settings.username.ifBlank { "(not set)" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Relay: ${state.settings.relayBaseUrl}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Text(
                text = "Normal: ${state.settings.normalIntervalMs / 60_000L} min | Fast: ${state.settings.fastIntervalMs / 1_000L} sec",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { onSettingsExpandedChange(!settingsExpanded) },
                modifier = Modifier.fillMaxWidth()
            ) {
                ButtonLabel(if (settingsExpanded) "Hide Sharing Settings" else "Edit Sharing Settings")
            }
            AnimatedVisibility(visible = settingsExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onEditUsername, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel("Edit Username")
                    }
                    OutlinedButton(onClick = onEditRelayUrl, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel("Edit Relay URL")
                    }
                    OutlinedButton(onClick = onEditNormalInterval, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel("Edit Normal Interval")
                    }
                    OutlinedButton(onClick = onEditFastInterval, modifier = Modifier.fillMaxWidth()) {
                        ButtonLabel("Edit Fast Interval")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsEditorDialog(
    editor: SettingsEditor,
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = when (editor) {
        SettingsEditor.Username -> "Edit Username"
        SettingsEditor.RelayUrl -> "Edit Relay URL"
        SettingsEditor.NormalIntervalMinutes -> "Edit Normal Interval (minutes)"
        SettingsEditor.FastIntervalSeconds -> "Edit Fast Interval (seconds)"
    }
    val keyboardType = when (editor) {
        SettingsEditor.RelayUrl -> KeyboardType.Uri
        SettingsEditor.NormalIntervalMinutes,
        SettingsEditor.FastIntervalSeconds -> KeyboardType.Number
        SettingsEditor.Username -> KeyboardType.Text
    }
    val label = when (editor) {
        SettingsEditor.Username -> "Username"
        SettingsEditor.RelayUrl -> "Relay URL"
        SettingsEditor.NormalIntervalMinutes -> "Minutes"
        SettingsEditor.FastIntervalSeconds -> "Seconds"
    }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION),
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private enum class SettingsEditor {
    Username,
    RelayUrl,
    NormalIntervalMinutes,
    FastIntervalSeconds
}

private fun isValidRelayBaseUrl(baseUrl: String): Boolean {
    val normalized = baseUrl.trim()
    if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
        return false
    }
    val uri = runCatching { android.net.Uri.parse(normalized) }.getOrNull() ?: return false
    return !uri.host.isNullOrBlank()
}

@Composable
private fun OnboardingSection(
    onOpenShareCode: () -> Unit,
    onOpenScanCode: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Location Codes", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Invite contacts with a location code, or import theirs by camera or manual paste.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenShareCode, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel("Share Location Code")
            }
            OutlinedButton(onClick = onOpenScanCode, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel("Scan Location Code")
            }
        }
    }
}

@Composable
private fun ShareLocationCodeDialog(
    pasteInput: String,
    pasteError: String?,
    myCode: String?,
    onDismiss: () -> Unit,
    onPasteInputChange: (String) -> Unit,
    onCopyCode: () -> Unit,
    onVerifyPaste: () -> Unit
) {
    val qrImage by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = myCode
    ) {
        value = if (myCode.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { generateQrImage(myCode) }
        }
    }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("Share Location Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Use the QR below for scanning or copy the code for manual sharing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onCopyCode, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Manually Copy Code")
                }
                val image = qrImage
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "My location code QR",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                }
                OutlinedTextField(
                    value = pasteInput,
                    onValueChange = onPasteInputChange,
                    label = { Text("Paste code to verify/import") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                OutlinedButton(onClick = onVerifyPaste, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Verify & Import Pasted Code")
                }
                if (!pasteError.isNullOrBlank()) {
                    Text(
                        text = pasteError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun ScanLocationCodeDialog(
    pasteInput: String,
    pasteError: String?,
    onDismiss: () -> Unit,
    onPasteInputChange: (String) -> Unit,
    onScanCamera: () -> Unit,
    onVerifyPaste: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("Scan Location Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScanCamera, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Scan QR Code")
                }
                OutlinedTextField(
                    value = pasteInput,
                    onValueChange = onPasteInputChange,
                    label = { Text("Paste code manually") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                OutlinedButton(onClick = onVerifyPaste, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Verify & Import Pasted Code")
                }
                if (!pasteError.isNullOrBlank()) {
                    Text(
                        text = pasteError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun CreateZoneDialog(
    zoneNameInput: String,
    zoneRadiusInput: String,
    error: String?,
    hasFix: Boolean,
    onZoneNameChange: (String) -> Unit,
    onZoneRadiusChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("Create New Zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (hasFix) "Zone center will use your current fix." else "Current fix unavailable. Acquire GPS fix first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasFix) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
                )
                OutlinedTextField(
                    value = zoneNameInput,
                    onValueChange = onZoneNameChange,
                    label = { Text("Zone name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = zoneRadiusInput,
                    onValueChange = onZoneRadiusChange,
                    label = { Text("Radius (meters)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ContactsSection(
    state: LocationSharingState,
    aliasDrafts: MutableMap<String, String>,
    onToggleShareTo: (String, Boolean) -> Unit,
    onToggleFollow: (String, Boolean) -> Unit,
    onAliasApply: (String) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Contacts", style = MaterialTheme.typography.titleSmall)
            if (state.contacts.isEmpty()) {
                Text("No contacts imported yet.", style = MaterialTheme.typography.bodySmall)
            }
            state.contacts.forEach { contact ->
                aliasDrafts.putIfAbsent(contact.senderId, contact.localAlias.orEmpty())
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = contact.senderId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Text(
                        text = "Fingerprint: ${contact.safetyFingerprint}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Share to", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = contact.canReceiveFromMe,
                                onCheckedChange = { onToggleShareTo(contact.senderId, it) }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Follow", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = contact.iFollow,
                                onCheckedChange = { onToggleFollow(contact.senderId, it) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = aliasDrafts[contact.senderId].orEmpty(),
                        onValueChange = { aliasDrafts[contact.senderId] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Local alias override") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onAliasApply(contact.senderId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Apply Alias")
                        }
                        OutlinedButton(
                            onClick = { onRemoveContact(contact.senderId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            ButtonLabel("Remove")
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ZonesSection(
    zones: List<ShareZone>,
    canCreate: Boolean,
    onCreateZone: () -> Unit,
    onDeleteZone: (String) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Zones", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Attach contextual tags to updates when you are near saved areas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onCreateZone, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(if (canCreate) "Create New Zone" else "Create New Zone (Need Fix)")
            }
            if (zones.isEmpty()) {
                Text("No zones defined.", style = MaterialTheme.typography.bodySmall)
            }
            zones.forEach { zone ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(zone.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${zone.radiusM}m @ ${"%.5f".format(Locale.US, zone.centerLat)}, ${"%.5f".format(Locale.US, zone.centerLon)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    OutlinedButton(onClick = { onDeleteZone(zone.id) }) {
                        ButtonLabel("Delete")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun SyncSection(
    state: LocationSharingState,
    onPollNow: () -> Unit,
    onClearRelay: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sync Status", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Polling: ${if (state.pollingVisible) "Active" else "Inactive"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Last valid push: ${formatTime(state.sync.lastPushSuccessAtMs)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Text(
                text = "Last valid poll: ${formatTime(state.sync.lastPollSuccessAtMs)}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Text(
                text = "Last push error: ${state.sync.lastPushError ?: "None"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.sync.lastPushError == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Text(
                text = "Last poll error: ${state.sync.lastPollError ?: "None"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.sync.lastPollError == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPollNow, modifier = Modifier.weight(1f)) {
                    ButtonLabel("Poll Now")
                }
                OutlinedButton(onClick = onClearRelay, modifier = Modifier.weight(1f)) {
                    ButtonLabel("Clear Relay Location")
                }
            }
        }
    }
}

@Composable
private fun IdentityBackupSection(
    passphraseInput: String,
    passphraseConfirmInput: String,
    onPassphraseInputChange: (String) -> Unit,
    onPassphraseConfirmInputChange: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Identity Backup", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = passphraseInput,
                onValueChange = onPassphraseInputChange,
                label = { Text("Passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = passphraseConfirmInput,
                onValueChange = onPassphraseConfirmInputChange,
                label = { Text("Confirm passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                    ButtonLabel("Export Identity")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    ButtonLabel("Import Identity")
                }
            }
        }
    }
}

@Composable
private fun ReceivedCardsSection(state: LocationSharingState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Received Locations", style = MaterialTheme.typography.titleSmall)
            if (state.receivedCards.isEmpty()) {
                Text("No received locations yet.", style = MaterialTheme.typography.bodySmall)
            }
            state.receivedCards.forEach { card ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "From ${card.senderId}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Text(
                        text = "Fix ${formatTime(card.claim.timestampMs)} | relay seq ${card.relaySeq}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Lat/Lon ${"%.6f".format(Locale.US, card.claim.lat)}, ${"%.6f".format(Locale.US, card.claim.lon)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Text(
                        text = "Speed ${card.claim.speed?.let { "%.2f".format(Locale.US, it) } ?: "null"} m/s | Acc ${card.claim.accuracy?.let { "%.1f".format(Locale.US, it) } ?: "null"} m",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Zones ${card.claim.zones?.joinToString(", ") ?: "null"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val pollStatus = card.pollStatus
                    Text(
                        text = "Poll status: ${pollStatus?.lastError ?: "ok"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pollStatus?.lastError == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatTime(timestampMillis: Long?): String {
    if (timestampMillis == null) return "Never"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(timestampMillis))
}
