package com.example.blackbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import com.example.blackbox.ui.components.NeoAlertDialog as AlertDialog
import com.example.blackbox.ui.components.NeoButton as Button
import com.example.blackbox.ui.components.NeoCard as Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.example.blackbox.ui.components.NeoOutlinedButton as OutlinedButton
import com.example.blackbox.ui.components.NeoOutlinedCard as OutlinedCard
import com.example.blackbox.ui.components.NeoOutlinedTextField as OutlinedTextField
import com.example.blackbox.ui.components.NeoSwitch as Switch
import androidx.compose.material3.Text
import com.example.blackbox.ui.components.NeoTextButton as TextButton
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
import androidx.compose.ui.platform.LocalView
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.blackbox.ui.theme.neomorphicShadow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DIALOG_WIDTH_FRACTION = 0.96f
private const val RELAY_STATUS_UI_DEBOUNCE_MS = 800L
private const val BAR_TAP_BOOST_DURATION_MS = 420L
private const val EXPANDED_CONTACT_POLL_INTERVAL_MS = 20_000L
private const val ACTIVE_LOCATION_EVENT_INTERVAL_MS = 1_000L
private const val LOW_POWER_LOCATION_EVENT_INTERVAL_MS = 3 * 60_000L
private const val ROW_EXPAND_ANIM_MS = 180
private val SHARING_QR_BUTTON_HEIGHT = 56.dp

private fun emitToggleRowHaptic(view: android.view.View, scope: CoroutineScope) {
    if (!view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) {
        view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
    }
    scope.launch {
        delay(ROW_EXPAND_ANIM_MS.toLong())
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

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
    var networkPermissionGranted by rememberSaveable {
        mutableStateOf(context.applicationContext.hasSharingNetworkPermissions())
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
        val permissionGranted = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            LocationSharingController.initialize(appContext)
            appContext.hasSharingNetworkPermissions()
        }
        networkPermissionGranted = permissionGranted
        if (!networkPermissionGranted) {
            statusMessage = "Network permission is missing. Update/reinstall app and reopen Location Sharing."
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> LocationSharingController.onSharingPageVisible(true)
                Lifecycle.Event.ON_STOP -> LocationSharingController.onSharingPageVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        LocationSharingController.onSharingPageVisible(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LocationSharingController.onSharingPageVisible(false)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
            ContactsSection(
                state = sharingState,
                onShowQr = {
                    Log.d(
                        SHARING_DEBUG_TAG,
                        "Share Location Code pressed code=${sharingState.myContactCode ?: "UNAVAILABLE"}"
                    )
                    shareCodeDialogVisible = true
                    shareManualCodeInput = ""
                    shareManualCodeError = null
                },
                onScanQr = {
                    scanCodeDialogVisible = true
                    scanManualCodeInput = ""
                    scanManualCodeError = null
                },
                onPollNow = { LocationSharingController.manualPollNow() },
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
                currentLat = locationState.bestPositionFix?.location?.latitude,
                currentLon = locationState.bestPositionFix?.location?.longitude,
                onCreateZone = {
                    createZoneDialogVisible = true
                    zoneDialogName = ""
                    zoneDialogRadius = "100"
                    zoneDialogError = null
                },
                onRenameZone = { zoneId, name ->
                    val zone = sharingState.zones.firstOrNull { it.id == zoneId } ?: return@ZonesSection
                    LocationSharingController.addOrUpdateZone(
                        zoneId = zone.id,
                        name = name,
                        centerLat = zone.centerLat,
                        centerLon = zone.centerLon,
                        radiusM = zone.radiusM
                    )
                    statusMessage = "Zone renamed."
                },
                onDeleteZone = { id ->
                    LocationSharingController.removeZone(id)
                }
            )
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
    locationState: com.example.blackbox.location.LocationEngineState,
    expandedContactRowOpen: Boolean
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
    val relayTotal = com.example.blackbox.sharing.RELAY_STATUS_INTERVAL_MS
    val pollTotal = if (expandedContactRowOpen) {
        EXPANDED_CONTACT_POLL_INTERVAL_MS
    } else {
        com.example.blackbox.sharing.POLL_INTERVAL_MS
    }
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
    val sendWaiting = sharingState.settings.sharingEnabled &&
        sharingState.outboundRecipientsCount > 0 &&
        locationState.engineEnabled &&
        locationState.engineMode != com.example.blackbox.location.LocationEngineMode.Off
    val locationEventIntervalMs = when (locationState.engineMode) {
        com.example.blackbox.location.LocationEngineMode.Active -> ACTIVE_LOCATION_EVENT_INTERVAL_MS
        com.example.blackbox.location.LocationEngineMode.LowPower -> LOW_POWER_LOCATION_EVENT_INTERVAL_MS
        com.example.blackbox.location.LocationEngineMode.Off -> 0L
    }
    val waitingForLocationEvent = sendWaiting && sendRemaining == 0L && locationEventIntervalMs > 0L
    val sendDisplayTotal = if (waitingForLocationEvent) locationEventIntervalMs else sendTotal
    val sendDisplayRemaining = if (waitingForLocationEvent) {
        remainingDelayMs(
            nowMs = nowMs,
            lastAtMs = locationState.bestPositionFix?.receivedAtMillis,
            totalMs = locationEventIntervalMs
        )
    } else {
        sendRemaining
    }
    val retrieveWaiting = sharingState.sync.pollingActive && sharingState.followingCount > 0

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
                remainingMs = relayRemaining,
                nowMs = nowMs,
                statusColor = statusDotColor(
                    failureStreak = sharingState.sync.relayCheckFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastRelayStatusOkAtMs
                ),
                isActive = true
            )
            DelayProgressRow(
                label = "Retrieve Locations",
                totalMs = pollTotal,
                remainingMs = pollRemaining,
                nowMs = nowMs,
                statusColor = statusDotColor(
                    failureStreak = sharingState.sync.pollFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastPollSuccessAtMs
                ),
                isActive = retrieveWaiting
            )
            DelayProgressRow(
                label = "Sending Location",
                totalMs = sendDisplayTotal,
                remainingMs = sendDisplayRemaining,
                nowMs = nowMs,
                statusColor = statusDotColor(
                    failureStreak = sharingState.sync.pushFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastPushSuccessAtMs
                ),
                isActive = sendWaiting
            )
        }
    }
}

@Composable
private fun DelayProgressRow(
    label: String,
    totalMs: Long,
    remainingMs: Long,
    nowMs: Long,
    statusColor: Color,
    isActive: Boolean
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
        val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        val effectiveStatusColor = if (isActive) statusColor else muted
        val labelColor = if (isActive) MaterialTheme.colorScheme.onSurface else muted
        val valueColor = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else muted
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusDot(
                    color = effectiveStatusColor,
                    shape = CircleShape,
                    size = 8.dp,
                    edgePadding = 0.dp
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
            }
            Text(
                text = formatDelayMs(remainingMs),
                style = MaterialTheme.typography.labelSmall,
                color = valueColor
            )
        }
        DelayProgressBar(progress = progress, nowMs = nowMs, isActive = isActive)
    }
}

@Composable
private fun DelayProgressBar(progress: Float, nowMs: Long, isActive: Boolean) {
    val scope = rememberCoroutineScope()
    val outlineColor = if (isActive) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    }
    val trailColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    var boostStartMs by remember { mutableStateOf<Long?>(null) }
    var boostFromProgress by remember { mutableStateOf(0f) }
    var boostToken by remember { mutableStateOf(0L) }
    val displayedProgress = run {
        val startMs = boostStartMs
        if (startMs == null) {
            progress.coerceIn(0f, 1f)
        } else {
            val t = ((nowMs - startMs).toFloat() / BAR_TAP_BOOST_DURATION_MS.toFloat()).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(t)
            (boostFromProgress + ((1f - boostFromProgress) * eased)).coerceIn(0f, 1f)
        }
    }
    val shape = RoundedCornerShape(7.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(shape)
            .border(width = 1.5.dp, color = outlineColor, shape = shape)
            .padding(1.dp)
            .clickable(enabled = isActive) {
                boostFromProgress = progress.coerceIn(0f, 1f)
                boostStartMs = nowMs
                val token = boostToken + 1L
                boostToken = token
                scope.launch {
                    delay(BAR_TAP_BOOST_DURATION_MS)
                    if (boostToken == token) {
                        boostStartMs = null
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val headX = width * displayedProgress
            val trailLength = width * 0.34f
            val trailStart = (headX - trailLength).coerceAtLeast(0f)
            val fillWidth = headX.coerceAtLeast(0f)

            if (fillWidth > 0f) {
                drawRect(
                    color = trailColor.copy(alpha = 0.10f),
                    topLeft = Offset(0f, 0f),
                    size = Size(fillWidth, height)
                )
            }
            if (headX > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            trailColor.copy(alpha = 0.35f),
                            trailColor.copy(alpha = 0.9f)
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

private fun statusDotColor(failureStreak: Int, lastSuccessAtMs: Long?): Color {
    return when {
        failureStreak >= 2 -> Color(0xFFC62828)
        failureStreak == 1 -> Color(0xFFFB8C00)
        lastSuccessAtMs != null -> Color(0xFF2E7D32)
        else -> Color(0xFFFB8C00)
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
                Button(
                    onClick = onScanCamera,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SHARING_QR_BUTTON_HEIGHT)
                ) {
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
fun CreateZoneDialog(
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
                    onValueChange = { onZoneRadiusChange(it.filter(Char::isDigit)) },
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
fun ContactsSection(
    state: LocationSharingState,
    onShowQr: () -> Unit,
    onScanQr: () -> Unit,
    onPollNow: () -> Unit,
    onToggleShareTo: (String, Boolean) -> Unit,
    onToggleFollow: (String, Boolean) -> Unit,
    onAliasApply: (String, String?) -> Unit,
    onRemoveContact: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
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

    LaunchedEffect(expandedSenderId) {
        val senderId = expandedSenderId ?: return@LaunchedEffect
        onPollNow()
        while (expandedSenderId == senderId) {
            delay(EXPANDED_CONTACT_POLL_INTERVAL_MS)
            if (expandedSenderId != senderId) break
            onPollNow()
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onShowQr,
                    modifier = Modifier
                        .weight(1f)
                        .height(SHARING_QR_BUTTON_HEIGHT)
                ) {
                    ButtonLabel("Show QR")
                }
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier
                        .weight(1f)
                        .height(SHARING_QR_BUTTON_HEIGHT)
                ) {
                    ButtonLabel("Scan QR")
                }
            }
            if (sortedContacts.isEmpty()) {
                Text("No contacts imported yet.", style = MaterialTheme.typography.bodySmall)
            }
            sortedContacts.forEach { contact ->
                val lastReceivedAtMs = receivedBySender[contact.senderId]
                val latestCard = latestCardBySender[contact.senderId]
                val hasRecentLocation = lastReceivedAtMs != null &&
                    (nowMs - lastReceivedAtMs) < 30 * 60_000L
                val isExpanded = expandedSenderId == contact.senderId
                val rowInteractionSource = remember(contact.senderId) { MutableInteractionSource() }
                val cardShape = RoundedCornerShape(14.dp)
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 90f else 0f,
                    animationSpec = tween(durationMillis = ROW_EXPAND_ANIM_MS, easing = FastOutSlowInEasing),
                    label = "contactExpandArrow"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .neomorphicShadow(
                            shape = cardShape,
                            pressed = isExpanded,
                            addBorder = false,
                            depth = 2.dp,
                            blurRadius = 3.dp
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = rowInteractionSource,
                                indication = null
                            ) {
                                emitToggleRowHaptic(view = view, scope = scope)
                                expandedSenderId = if (isExpanded) null else contact.senderId
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(
                            color = contactStatusColor(lastReceivedAtMs, nowMs),
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
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(
                            animationSpec = tween(durationMillis = ROW_EXPAND_ANIM_MS, easing = FastOutSlowInEasing)
                        ),
                        exit = shrinkVertically(
                            animationSpec = tween(durationMillis = ROW_EXPAND_ANIM_MS, easing = FastOutSlowInEasing)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (contact.iFollow && hasRecentLocation && latestCard != null) {
                                val zoneTitle = latestCard.claim.zones
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")
                                    ?.let { "At \"$it\"" }
                                if (zoneTitle != null) {
                                    Text(
                                        text = zoneTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .border(
                                            width = 1.5.dp,
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
                                Button(
                                    onClick = { onToggleShareTo(contact.senderId, !contact.canReceiveFromMe) },
                                    latched = contact.canReceiveFromMe,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        contentColor = if (contact.canReceiveFromMe) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
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

                                Button(
                                    onClick = { onToggleFollow(contact.senderId, !contact.iFollow) },
                                    latched = contact.iFollow,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        contentColor = if (contact.iFollow) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
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
                                                text = formatAgeSince(lastReceivedAtMs, nowMs),
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

private fun contactStatusColor(lastReceivedAtMs: Long?, nowMs: Long): Color {
    if (lastReceivedAtMs == null) {
        return Color(0xFFC62828)
    }
    val ageMs = nowMs - lastReceivedAtMs
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
                .border(1.5.dp, color, RoundedCornerShape(2.dp))
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
fun ZonesSection(
    zones: List<ShareZone>,
    canCreate: Boolean,
    currentLat: Double?,
    currentLon: Double?,
    onCreateZone: () -> Unit,
    onRenameZone: (String, String) -> Unit,
    onDeleteZone: (String) -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var expandedZoneId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTargetZoneId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameZoneInput by rememberSaveable { mutableStateOf("") }
    var deleteTargetZoneId by rememberSaveable { mutableStateOf<String?>(null) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                val isExpanded = expandedZoneId == zone.id
                val inZone = isWithinZone(
                    lat = currentLat,
                    lon = currentLon,
                    zone = zone
                )
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 90f else 0f,
                    animationSpec = tween(durationMillis = ROW_EXPAND_ANIM_MS, easing = FastOutSlowInEasing),
                    label = "zoneExpandArrow"
                )
                val rowInteractionSource = remember(zone.id) { MutableInteractionSource() }
                val rowShape = RoundedCornerShape(14.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .neomorphicShadow(
                            shape = rowShape,
                            pressed = isExpanded,
                            addBorder = false,
                            depth = 2.dp,
                            blurRadius = 3.dp
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = rowInteractionSource,
                                indication = null
                            ) {
                                emitToggleRowHaptic(view = view, scope = scope)
                                expandedZoneId = if (isExpanded) null else zone.id
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(
                            color = Color(0xFF1E88E5),
                            shape = CircleShape,
                            size = 14.dp,
                            edgePadding = 0.dp,
                            hollow = !inZone
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = zone.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = ">",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(arrowRotation)
                        )
                    }
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(
                            animationSpec = tween(durationMillis = ROW_EXPAND_ANIM_MS, easing = FastOutSlowInEasing)
                        ),
                        exit = shrinkVertically(
                            animationSpec = tween(durationMillis = ROW_EXPAND_ANIM_MS, easing = FastOutSlowInEasing)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .border(
                                        width = 1.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Map Placeholder\n${formatLatLon(zone.centerLat, zone.centerLon)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Text(
                                text = "Radius: ${zone.radiusM}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        renameTargetZoneId = zone.id
                                        renameZoneInput = zone.name
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    ButtonLabel("Rename")
                                }
                                OutlinedButton(
                                    onClick = { deleteTargetZoneId = zone.id },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    ButtonLabel("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val renameZoneTarget = zones.firstOrNull { it.id == renameTargetZoneId }
    if (renameZoneTarget != null) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = {
                renameTargetZoneId = null
                renameZoneInput = ""
            },
            title = { Text("Rename ${renameZoneTarget.name}") },
            text = {
                OutlinedTextField(
                    value = renameZoneInput,
                    onValueChange = { renameZoneInput = it },
                    label = { Text("Zone name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameZoneInput.trim()
                        if (trimmed.length in ZONE_NAME_MIN_LENGTH..ZONE_NAME_MAX_LENGTH) {
                            onRenameZone(renameZoneTarget.id, trimmed)
                            renameTargetZoneId = null
                            renameZoneInput = ""
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameTargetZoneId = null
                        renameZoneInput = ""
                    }
                ) { Text("Cancel") }
            }
        )
    }

    val deleteZoneTarget = zones.firstOrNull { it.id == deleteTargetZoneId }
    if (deleteZoneTarget != null) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { deleteTargetZoneId = null },
            title = { Text("Delete ${deleteZoneTarget.name}?") },
            text = {
                Text(
                    text = "This permanently removes the zone.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteZone(deleteZoneTarget.id)
                        if (expandedZoneId == deleteZoneTarget.id) {
                            expandedZoneId = null
                        }
                        deleteTargetZoneId = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetZoneId = null }) { Text("Cancel") }
            }
        )
    }
}

private fun isWithinZone(lat: Double?, lon: Double?, zone: ShareZone): Boolean {
    if (lat == null || lon == null) return false
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(zone.centerLat - lat)
    val dLon = Math.toRadians(zone.centerLon - lon)
    val radLat1 = Math.toRadians(lat)
    val radLat2 = Math.toRadians(zone.centerLat)
    val a = kotlin.math.sin(dLat / 2).pow(2) +
        kotlin.math.cos(radLat1) * kotlin.math.cos(radLat2) * kotlin.math.sin(dLon / 2).pow(2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    val distanceM = earthRadiusM * c
    return distanceM <= zone.radiusM.toDouble()
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

private fun formatAgeSince(timestampMs: Long?, nowMs: Long): String {
    if (timestampMs == null) return "No updates yet"
    val ageSeconds = ((nowMs - timestampMs).coerceAtLeast(0L)) / 1000L
    return "$ageSeconds seconds ago"
}

private fun formatTime(timestampMillis: Long?): String {
    if (timestampMillis == null) return "Never"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(timestampMillis))
}
