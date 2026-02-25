package com.example.blackbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.sharing.LocationSharingState
import com.example.blackbox.sharing.ZONE_NAME_MAX_LENGTH
import com.example.blackbox.sharing.ZONE_NAME_MIN_LENGTH
import com.example.blackbox.sharing.ZONE_RADIUS_MAX_METERS
import com.example.blackbox.sharing.ZONE_RADIUS_MIN_METERS
import com.example.blackbox.sharing.ShareZone
import com.example.blackbox.sharing.QrScannerActivity
import com.example.blackbox.sharing.SHARING_DEBUG_TAG
import com.example.blackbox.sharing.hasSharingNetworkPermissions
import com.example.blackbox.ui.components.ButtonLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DIALOG_WIDTH_FRACTION = 0.96f
private const val RELAY_STATUS_UI_DEBOUNCE_MS = 800L
private const val BAR_UPDATE_TICK_MS = 250L

@Composable
fun SharingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharingState by LocationSharingController.state.collectAsState()
    val locationState by LocationEngine.state.collectAsState()

    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
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

    val scanQrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanned = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)?.trim().orEmpty()
        if (scanned.isBlank()) {
            val error = result.data?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
            if (!error.isNullOrBlank()) {
                scanManualCodeError = error
            }
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RelayConnectionStatusBar(sharingState)
        }

        item {
            RefreshDelaysCard(
                sharingState = sharingState,
                locationState = locationState
            )
        }

        item {
            Text(
                text = "Location Sharing",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            ShareMyLocationToggleRow(
                enabled = sharingState.settings.sharingEnabled,
                outboundRecipientsCount = sharingState.outboundRecipientsCount,
                onCheckedChange = { LocationSharingController.setSharingEnabled(it) }
            )
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
            OnboardingSection(
                onOpenShareCode = {
                    Log.d(
                        SHARING_DEBUG_TAG,
                        "Share Location Code pressed code=${sharingState.myContactCode ?: "UNAVAILABLE"}"
                    )
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
                onToggleShareTo = { senderId, checked ->
                    LocationSharingController.setOutboundAuthorization(senderId, checked)
                },
                onToggleFollow = { senderId, checked ->
                    LocationSharingController.setFollowing(senderId, checked)
                },
                onAliasApply = { senderId, alias ->
                    LocationSharingController.setLocalAlias(senderId, alias)
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
                scanQrLauncher.launch(Intent(context, QrScannerActivity::class.java))
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
    var displayedReachable by remember { mutableStateOf(sync.relayReachable == true) }
    val targetReachable = sync.relayReachable ?: displayedReachable

    LaunchedEffect(targetReachable) {
        if (targetReachable != displayedReachable) {
            delay(RELAY_STATUS_UI_DEBOUNCE_MS)
            displayedReachable = targetReachable
        }
    }

    val title: String
    val detail: String
    val containerColor: Color
    val contentColor: Color
    val dotColor: Color

    if (displayedReachable) {
        title = "Relay connected"
        detail = ""
        containerColor = MaterialTheme.colorScheme.secondaryContainer
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        dotColor = Color(0xFF2E7D32)
    } else {
        title = "Relay unreachable"
        detail = sync.lastRelayStatusError ?: "Last check ${formatTime(sync.lastRelayStatusCheckAtMs)}"
        containerColor = MaterialTheme.colorScheme.errorContainer
        contentColor = MaterialTheme.colorScheme.onErrorContainer
        dotColor = MaterialTheme.colorScheme.error
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
private fun RefreshDelaysCard(
    sharingState: LocationSharingState,
    locationState: com.example.blackbox.location.LocationEngineState
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(BAR_UPDATE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val relayTotal = com.example.blackbox.sharing.RELAY_STATUS_INTERVAL_MS
    val pollTotal = com.example.blackbox.sharing.POLL_INTERVAL_MS
    val isFast = (locationState.bestMotionFix?.speedMetersPerSecond ?: -1f) >= sharingState.settings.fastSpeedThresholdMps
    val sendTotal = if (isFast) sharingState.settings.fastIntervalMs else sharingState.settings.normalIntervalMs

    val relayRemaining = remainingDelayMs(
        nowMs = nowMs,
        lastAtMs = sharingState.sync.lastRelayStatusCheckAtMs,
        totalMs = relayTotal
    )
    val pollRemaining = remainingDelayMs(
        nowMs = nowMs,
        lastAtMs = sharingState.sync.lastPollAttemptAtMs,
        totalMs = pollTotal
    )
    val sendAnchor = sharingState.sync.lastPushSuccessAtMs ?: sharingState.sync.lastPushAttemptAtMs
    val sendRemaining = remainingDelayMs(
        nowMs = nowMs,
        lastAtMs = sendAnchor,
        totalMs = sendTotal
    )

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DelayProgressRow(
                label = "Relay Check",
                totalMs = relayTotal,
                remainingMs = relayRemaining
            )
            DelayProgressRow(
                label = "Retrieve Locations (polling)",
                totalMs = pollTotal,
                remainingMs = pollRemaining
            )
            DelayProgressRow(
                label = "Sending Location",
                totalMs = sendTotal,
                remainingMs = sendRemaining
            )
        }
    }
}

@Composable
private fun DelayProgressRow(
    label: String,
    totalMs: Long,
    remainingMs: Long
) {
    val progress = if (totalMs <= 0L) {
        1f
    } else {
        ((totalMs - remainingMs).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = formatDelayMs(remainingMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DelayProgressBar(progress = progress)
    }
}

@Composable
private fun DelayProgressBar(progress: Float) {
    val shape = RoundedCornerShape(7.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(shape)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.75f), shape = shape)
            .padding(1.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val headX = width * progress.coerceIn(0f, 1f)
            val trailLength = width * 0.34f
            val trailStart = (headX - trailLength).coerceAtLeast(0f)
            val fillWidth = headX.coerceAtLeast(0f)

            if (fillWidth > 0f) {
                drawRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(0f, 0f),
                    size = Size(fillWidth, height)
                )
            }
            if (headX > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.9f)
                        ),
                        startX = trailStart,
                        endX = headX
                    ),
                    topLeft = Offset(trailStart, 0f),
                    size = Size((headX - trailStart).coerceAtLeast(1f), height)
                )
            }
        }
    }
}

private fun remainingDelayMs(nowMs: Long, lastAtMs: Long?, totalMs: Long): Long {
    if (totalMs <= 0L) return 0L
    val last = lastAtMs ?: return 0L
    val elapsed = (nowMs - last).coerceAtLeast(0L)
    return (totalMs - elapsed).coerceAtLeast(0L)
}

private fun formatDelayMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}

@Composable
private fun StatusDot(
    color: Color,
    shape: Shape,
    size: androidx.compose.ui.unit.Dp = 10.dp,
    glow: Boolean = false,
    edgePadding: androidx.compose.ui.unit.Dp = 2.dp,
    hollow: Boolean = false
) {
    Box(
        modifier = Modifier
            .padding(start = edgePadding)
            .then(
                if (glow) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        clip = false
                    )
                } else {
                    Modifier
                }
            )
            .size(size)
            .clip(shape)
            .then(
                if (hollow) {
                    Modifier
                        .border(width = 2.dp, color = color, shape = shape)
                        .background(Color.Transparent)
                } else {
                    Modifier.background(color)
                }
            )
    )
}

@Composable
private fun ShareMyLocationToggleRow(
    enabled: Boolean,
    outboundRecipientsCount: Int,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
                    text = "Share My Location",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Share to $outboundRecipientsCount contact(s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
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
    onToggleShareTo: (String, Boolean) -> Unit,
    onToggleFollow: (String, Boolean) -> Unit,
    onAliasApply: (String, String?) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    val context = LocalContext.current
    var expandedSenderId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTargetSenderId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDialogInput by rememberSaveable { mutableStateOf("") }
    var deleteTargetSenderId by rememberSaveable { mutableStateOf<String?>(null) }
    val receivedBySender = state.receivedCards
        .groupBy { it.senderId }
        .mapValues { (_, cards) -> cards.maxOf { it.receivedAtMs } }
    val latestCardBySender = state.receivedCards
        .groupBy { it.senderId }
        .mapValues { (_, cards) -> cards.maxByOrNull { it.receivedAtMs } }
    val sortedContacts = state.contacts.sortedWith(
        compareByDescending<com.example.blackbox.sharing.ContactView> { receivedBySender[it.senderId] ?: Long.MIN_VALUE }
            .thenBy { it.displayName.lowercase(Locale.US) }
    )

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Contacts", style = MaterialTheme.typography.titleSmall)
            if (sortedContacts.isEmpty()) {
                Text("No contacts imported yet.", style = MaterialTheme.typography.bodySmall)
            }
            sortedContacts.forEach { contact ->
                val lastReceivedAtMs = receivedBySender[contact.senderId]
                val latestCard = latestCardBySender[contact.senderId]
                val hasRecentLocation = lastReceivedAtMs != null &&
                    (System.currentTimeMillis() - lastReceivedAtMs) < 30 * 60_000L
                val isExpanded = expandedSenderId == contact.senderId
                val rowInteractionSource = remember(contact.senderId) { MutableInteractionSource() }
                val cardShape = RoundedCornerShape(14.dp)
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 90f else 0f,
                    label = "contactExpandArrow"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = cardShape
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = rowInteractionSource,
                                indication = null
                            ) { expandedSenderId = if (isExpanded) null else contact.senderId }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(
                            color = contactStatusColor(lastReceivedAtMs),
                            shape = CircleShape,
                            size = 16.dp,
                            glow = true,
                            edgePadding = 0.dp,
                            hollow = !contact.iFollow
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = contact.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = ">",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .rotate(arrowRotation)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (contact.iFollow && hasRecentLocation && latestCard != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Map Placeholder\n${formatLatLon(latestCard.claim.lat, latestCard.claim.lon)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (contact.canReceiveFromMe) {
                                    Button(
                                        onClick = { onToggleShareTo(contact.senderId, false) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Sharing",
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onToggleShareTo(contact.senderId, true) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Sharing",
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                if (contact.iFollow) {
                                    Button(
                                        onClick = { onToggleFollow(contact.senderId, false) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Following",
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onToggleFollow(contact.senderId, true) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Following",
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LatLonTableRow(
                                        lat = latestCard?.claim?.lat,
                                        lon = latestCard?.claim?.lon,
                                        onCopy = { text ->
                                            copyToClipboard(context, "blackbox_coords", text)
                                        }
                                    )
                                    InfoTableRow(
                                        leftLabel = "Speed",
                                        leftValue = formatSpeed(latestCard?.claim?.speed),
                                        rightLabel = "Accuracy",
                                        rightValue = formatAccuracy(latestCard?.claim?.accuracy)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Updated",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = formatAgeSince(lastReceivedAtMs),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Battery",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            BatteryBadge(percent = latestCard?.claim?.batteryPercent)
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        renameTargetSenderId = contact.senderId
                                        renameDialogInput = contact.localAlias.orEmpty()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    ButtonLabel("Rename")
                                }
                                OutlinedButton(
                                    onClick = { deleteTargetSenderId = contact.senderId },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    ButtonLabel("Delete")
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    val renameTargetContact = sortedContacts.firstOrNull { it.senderId == renameTargetSenderId }
    if (renameTargetContact != null) {
        AlertDialog(
            onDismissRequest = {
                renameTargetSenderId = null
                renameDialogInput = ""
            },
            title = { Text("Rename ${renameTargetContact.displayName}") },
            text = {
                OutlinedTextField(
                    value = renameDialogInput,
                    onValueChange = { renameDialogInput = it },
                    label = { Text("Contact name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAliasApply(
                            renameTargetContact.senderId,
                            renameDialogInput.trim().takeIf { it.isNotEmpty() }
                        )
                        renameTargetSenderId = null
                        renameDialogInput = ""
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameTargetSenderId = null
                        renameDialogInput = ""
                    }
                ) { Text("Cancel") }
            }
        )
    }

    val deleteTargetContact = sortedContacts.firstOrNull { it.senderId == deleteTargetSenderId }
    if (deleteTargetContact != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetSenderId = null },
            title = { Text("Delete ${deleteTargetContact.displayName}?") },
            text = {
                Text(
                    text = "This removes the contact and its locally received location data.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveContact(deleteTargetContact.senderId)
                        if (expandedSenderId == deleteTargetContact.senderId) {
                            expandedSenderId = null
                        }
                        deleteTargetSenderId = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetSenderId = null }) { Text("Cancel") }
            }
        )
    }
}

private fun contactStatusColor(lastReceivedAtMs: Long?): Color {
    if (lastReceivedAtMs == null) {
        return Color(0xFFC62828)
    }
    val ageMs = System.currentTimeMillis() - lastReceivedAtMs
    return when {
        ageMs < 10 * 60_000L -> Color(0xFF2E7D32)
        ageMs < 30 * 60_000L -> Color(0xFFFB8C00)
        else -> Color(0xFFC62828)
    }
}

@Composable
private fun LatLonTableRow(
    lat: Double?,
    lon: Double?,
    onCopy: (String) -> Unit
) {
    val latText = lat?.let { "%.6f".format(Locale.US, it) } ?: "Unknown"
    val lonText = lon?.let { "%.6f".format(Locale.US, it) } ?: "Unknown"
    val combined = if (lat != null && lon != null) {
        "${"%.6f".format(Locale.US, lat)}, ${"%.6f".format(Locale.US, lon)}"
    } else {
        "Unknown"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onCopy(combined) }
        ) {
            Text("Lat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(latText, style = MaterialTheme.typography.bodySmall)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onCopy(combined) }
        ) {
            Text("Lon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lonText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoTableRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(leftValue, style = MaterialTheme.typography.bodySmall)
        }
        if (rightLabel.isNotBlank()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rightValue, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BatteryBadge(percent: Int?) {
    val color = when {
        percent == null -> MaterialTheme.colorScheme.outline
        percent < 10 -> Color(0xFFC62828)
        else -> Color(0xFF2E7D32)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = percent?.let { "$it%" } ?: "N/A",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White
        )
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 10.dp)
                .border(1.dp, color, RoundedCornerShape(2.dp))
        ) {
            val fillFraction = ((percent ?: 0).coerceIn(0, 100)) / 100f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillFraction)
                        .background(color, RoundedCornerShape(1.dp))
                )
            }
        }
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 4.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
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

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatLatLon(lat: Double?, lon: Double?): String {
    if (lat == null || lon == null) return "No location yet"
    return "${"%.6f".format(Locale.US, lat)}, ${"%.6f".format(Locale.US, lon)}"
}

private fun formatSpeed(speed: Float?): String {
    return speed?.let { "%.2f m/s".format(Locale.US, it) } ?: "Unknown"
}

private fun formatAccuracy(accuracy: Float?): String {
    return accuracy?.let { "%.1f m".format(Locale.US, it) } ?: "Unknown"
}

private fun formatAgeSince(timestampMs: Long?): String {
    if (timestampMs == null) return "No updates yet"
    val ageSeconds = ((System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)) / 1000L
    return "$ageSeconds seconds ago"
}

private fun formatTime(timestampMillis: Long?): String {
    if (timestampMillis == null) return "Never"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(timestampMillis))
}
