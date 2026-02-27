package com.example.blackbox.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.blackbox.ui.components.NeoButton as Button
import com.example.blackbox.ui.components.NeoCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.example.blackbox.ui.components.NeoOutlinedButton as OutlinedButton
import com.example.blackbox.ui.components.NeoOutlinedCard as OutlinedCard
import androidx.compose.material3.Text
import com.example.blackbox.ui.components.NeoTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineMode
import com.example.blackbox.location.MotionFix
import com.example.blackbox.location.PositionFix
import com.example.blackbox.location.hasAnyLocationPermission
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.sharing.SharingLogic
import com.example.blackbox.sharing.ShareZone
import com.example.blackbox.sharing.ZONE_NAME_MAX_LENGTH
import com.example.blackbox.sharing.ZONE_NAME_MIN_LENGTH
import com.example.blackbox.sharing.ZONE_RADIUS_MAX_METERS
import com.example.blackbox.sharing.ZONE_RADIUS_MIN_METERS
import com.example.blackbox.sharing.isValidUsername
import com.example.blackbox.sharing.normalizeUsername
import com.example.blackbox.ui.components.ButtonLabel
import com.example.blackbox.ui.components.NeoButtonHapticMode
import com.example.blackbox.ui.perf.UiPerfSection
import com.example.blackbox.ui.perf.uiPerfDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAIN_DIALOG_WIDTH_FRACTION = 0.96f
private const val MAIN_EXPANDED_CONTACT_POLL_INTERVAL_MS = 20_000L
private const val MAIN_BAR_TAP_BOOST_DURATION_MS = 420L
private const val MAIN_ACTIVE_LOCATION_EVENT_INTERVAL_MS = 1_000L
private const val MAIN_LOW_POWER_LOCATION_EVENT_INTERVAL_MS = 3 * 60_000L
private const val MAIN_PROGRESS_SMOOTH_ANIM_MS = 900
private const val MAIN_TOGGLE_DEBOUNCE_MS = 120L
private val MAIN_TOP_BAR_SCROLL_CLEARANCE = 16.dp
private val MAIN_BOTTOM_BAR_SCROLL_CLEARANCE = 120.dp
private val MAIN_QR_BUTTON_HEIGHT = 56.dp

private data class MainViewLocationState(
    val bestPositionFix: PositionFix?,
    val bestMotionFix: MotionFix?,
    val engineEnabled: Boolean,
    val engineMode: LocationEngineMode
)

@Composable
fun MainViewScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val locationState by remember {
        LocationEngine.state
            .map { engine ->
                MainViewLocationState(
                    bestPositionFix = engine.bestPositionFix,
                    bestMotionFix = engine.bestMotionFix,
                    engineEnabled = engine.engineEnabled,
                    engineMode = engine.engineMode
                )
            }
            .distinctUntilChanged()
    }.collectAsState(
        initial = MainViewLocationState(
            bestPositionFix = LocationEngine.state.value.bestPositionFix,
            bestMotionFix = LocationEngine.state.value.bestMotionFix,
            engineEnabled = LocationEngine.state.value.engineEnabled,
            engineMode = LocationEngine.state.value.engineMode
        )
    )
    val sharingState by LocationSharingController.state.collectAsState()
    val persistenceState by LocationPersistenceController.state.collectAsState()

    var permissionGranted by rememberSaveable { mutableStateOf(context.hasAnyLocationPermission()) }
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
    var addContactsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var showQrDialogVisible by rememberSaveable { mutableStateOf(false) }
    var viewContactsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var zonesDialogVisible by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var createZoneDialogVisible by rememberSaveable { mutableStateOf(false) }
    var zoneDialogName by rememberSaveable { mutableStateOf("") }
    var zoneDialogRadius by rememberSaveable { mutableStateOf("100") }
    var zoneDialogError by rememberSaveable { mutableStateOf<String?>(null) }

    val scanQrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanned = result.data?.getStringExtra(com.example.blackbox.sharing.QrScannerActivity.EXTRA_QR_TEXT)
            ?.trim().orEmpty()
        if (scanned.isBlank()) {
            scanError = result.data?.getStringExtra(com.example.blackbox.sharing.QrScannerActivity.EXTRA_ERROR)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            LocationSharingController.importContactCard(scanned)
                .onSuccess {
                    scanError = null
                    addContactsDialogVisible = false
                }
                .onFailure { scanError = it.message ?: "Failed to import location code." }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = context.hasAnyLocationPermission()
        permissionRequestInFlight = false
    }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            LocationEngine.initialize(appContext)
            LocationSharingController.initialize(appContext)
            LocationPersistenceController.initialize(appContext)
        }
        permissionGranted = context.hasAnyLocationPermission()
    }

    val desiredEngineOn = sharingState.settings.sharingEnabled || persistenceState.loggingEnabled
    LaunchedEffect(desiredEngineOn, permissionGranted) {
        if (desiredEngineOn) {
            if (permissionGranted) {
                LocationEngine.setEngineEnabled(true)
            } else if (!permissionRequestInFlight) {
                permissionRequestInFlight = true
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        } else {
            LocationEngine.setEngineEnabled(false)
        }
    }

    val myCode = sharingState.myContactCode
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

    val lastFix = locationState.bestPositionFix

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .uiPerfDraw("Main Lazy Column"),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = MAIN_TOP_BAR_SCROLL_CLEARANCE,
            end = 20.dp,
            bottom = MAIN_BOTTOM_BAR_SCROLL_CLEARANCE
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            UiPerfSection("Main Map Card") {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .uiPerfDraw("Main Map Card")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (lastFix != null) {
                                "Map Placeholder\n${formatLatLon(lastFix.location.latitude, lastFix.location.longitude)}"
                            } else {
                                "Map Placeholder\nNo location yet"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        RelativeAgeLabel(
                            timestampMs = lastFix?.receivedAtMillis,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }
        }

        item {
            UiPerfSection("Main Refresh Delays Card") {
                MainRefreshDelaysCard(
                    sharingState = sharingState,
                    locationState = locationState,
                    contactsOpen = viewContactsDialogVisible
                )
            }
        }

        item {
            UiPerfSection("Main Toggle Card") {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .uiPerfDraw("Main Toggle Card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToggleRow(
                            title = "Location Sharing",
                            checked = sharingState.settings.sharingEnabled
                        ) { enabled ->
                            if (enabled && !isValidUsername(normalizeUsername(sharingState.settings.username))) {
                                onOpenSettings()
                                return@ToggleRow
                            }
                            LocationSharingController.setSharingEnabled(enabled)
                        }
                        ToggleRow(
                            title = "Location Logging",
                            checked = persistenceState.loggingEnabled
                        ) { enabled ->
                            LocationPersistenceController.setLoggingEnabled(enabled)
                        }
                    }
                }
            }
        }

        item {
            UiPerfSection("Main Action Row A") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .uiPerfDraw("Main Action Row A"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionWidget(
                        title = "My Zones",
                        iconRes = android.R.drawable.ic_menu_mapmode,
                        modifier = Modifier.weight(1f),
                        onClick = { zonesDialogVisible = true }
                    )
                    ActionWidget(
                        title = "View Contacts",
                        iconRes = android.R.drawable.ic_menu_myplaces,
                        modifier = Modifier.weight(1f),
                        onClick = { viewContactsDialogVisible = true }
                    )
                }
            }
        }

        item {
            UiPerfSection("Main Action Row B") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .uiPerfDraw("Main Action Row B"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionWidget(
                        title = "Settings",
                        iconRes = android.R.drawable.ic_menu_preferences,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSettings
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (addContactsDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { addContactsDialogVisible = false },
            useContainer = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showQrDialogVisible = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MAIN_QR_BUTTON_HEIGHT)
                        ) {
                            ButtonLabel("Show QR Code")
                        }
                        OutlinedButton(
                            onClick = {
                                scanQrLauncher.launch(Intent(context, com.example.blackbox.sharing.QrScannerActivity::class.java))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MAIN_QR_BUTTON_HEIGHT)
                        ) {
                            ButtonLabel("Scan QR Code")
                        }
                    }
                }
                if (!scanError.isNullOrBlank()) {
                    Text(
                        text = scanError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showQrDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { showQrDialogVisible = false },
            useContainer = false
        ) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val image = qrImage
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = "My location QR",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    } else {
                        Text(
                            text = "Location code not available yet.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            myCode?.let { copyToClipboard(context, "blackbox_location_code", it) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ButtonLabel("Copy Code")
                    }
                }
            }
        }
    }

    if (viewContactsDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { viewContactsDialogVisible = false },
            useContainer = false
        ) {
            ContactsSection(
                state = sharingState,
                onShowQr = { showQrDialogVisible = true },
                onScanQr = {
                    scanQrLauncher.launch(Intent(context, com.example.blackbox.sharing.QrScannerActivity::class.java))
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
    }

    if (zonesDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { zonesDialogVisible = false },
            useContainer = false
        ) {
            ZonesSection(
                zones = sharingState.zones,
                canCreate = lastFix != null,
                currentLat = lastFix?.location?.latitude,
                currentLon = lastFix?.location?.longitude,
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
                },
                onDeleteZone = { id ->
                    LocationSharingController.removeZone(id)
                }
            )
        }
    }

    if (createZoneDialogVisible) {
        CreateZoneDialog(
            zoneNameInput = zoneDialogName,
            zoneRadiusInput = zoneDialogRadius,
            error = zoneDialogError,
            hasFix = lastFix != null,
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
                val position = lastFix
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
                createZoneDialogVisible = false
                zoneDialogError = null
                zoneDialogName = ""
                zoneDialogRadius = "100"
            }
        )
    }
}

@Composable
private fun MainOverlayDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    actions: (@Composable () -> Unit)? = null,
    useContainer: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismissRequest)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .blur(10.dp)
            )
            val baseContentModifier = Modifier
                .fillMaxWidth(MAIN_DIALOG_WIDTH_FRACTION)
                .align(Alignment.Center)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
            if (useContainer) {
                val contentModifier = baseContentModifier.fillMaxHeight(0.9f)
                Card(
                    modifier = contentModifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(20.dp)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!title.isNullOrBlank()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            content()
                        }
                        if (actions != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                actions()
                            }
                        }
                    }
                }
            } else {
                val contentModifier = baseContentModifier
                    .wrapContentHeight(unbounded = true)
                Column(
                    modifier = contentModifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun MainRefreshDelaysCard(
    sharingState: com.example.blackbox.sharing.LocationSharingState,
    locationState: MainViewLocationState,
    contactsOpen: Boolean
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis(), key1 = contactsOpen) {
        while (true) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
    val relayTotal = com.example.blackbox.sharing.RELAY_STATUS_INTERVAL_MS
    val pollTotal = if (contactsOpen) {
        MAIN_EXPANDED_CONTACT_POLL_INTERVAL_MS
    } else {
        com.example.blackbox.sharing.POLL_INTERVAL_MS
    }
    val isFast =
        (locationState.bestMotionFix?.speedMetersPerSecond ?: -1f) >= sharingState.settings.fastSpeedThresholdMps
    val sendTotal = if (isFast) sharingState.settings.fastIntervalMs else sharingState.settings.normalIntervalMs

    val relayRemaining = mainRemainingDelayMs(
        nowMs = nowMs,
        lastAtMs = sharingState.sync.lastRelayStatusCheckAtMs,
        totalMs = relayTotal
    )
    val pollRemaining = mainRemainingDelayMs(
        nowMs = nowMs,
        lastAtMs = sharingState.sync.lastPollAttemptAtMs,
        totalMs = pollTotal
    )
    val sendAnchor = sharingState.sync.lastPushSuccessAtMs ?: sharingState.sync.lastPushAttemptAtMs
    val sendRemaining = mainRemainingDelayMs(
        nowMs = nowMs,
        lastAtMs = sendAnchor,
        totalMs = sendTotal
    )

    val sendWaiting = sharingState.settings.sharingEnabled &&
        sharingState.outboundRecipientsCount > 0 &&
        locationState.engineEnabled &&
        locationState.engineMode != LocationEngineMode.Off
    val locationEventIntervalMs = when (locationState.engineMode) {
        LocationEngineMode.Active -> MAIN_ACTIVE_LOCATION_EVENT_INTERVAL_MS
        LocationEngineMode.LowPower -> MAIN_LOW_POWER_LOCATION_EVENT_INTERVAL_MS
        LocationEngineMode.Off -> 0L
    }
    val waitingForLocationEvent = sendWaiting && sendRemaining == 0L && locationEventIntervalMs > 0L
    val sendDisplayTotal = if (waitingForLocationEvent) locationEventIntervalMs else sendTotal
    val sendDisplayRemaining = if (waitingForLocationEvent) {
        mainRemainingDelayMs(
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
            MainDelayProgressRow(
                label = "Relay Check",
                totalMs = relayTotal,
                remainingMs = relayRemaining,
                nowMs = nowMs,
                statusColor = mainStatusDotColor(
                    failureStreak = sharingState.sync.relayCheckFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastRelayStatusOkAtMs
                ),
                isActive = true
            )
            MainDelayProgressRow(
                label = "Sending Location",
                totalMs = sendDisplayTotal,
                remainingMs = sendDisplayRemaining,
                nowMs = nowMs,
                statusColor = mainStatusDotColor(
                    failureStreak = sharingState.sync.pushFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastPushSuccessAtMs
                ),
                isActive = sendWaiting
            )
            MainDelayProgressRow(
                label = "Retrieve Locations",
                totalMs = pollTotal,
                remainingMs = pollRemaining,
                nowMs = nowMs,
                statusColor = mainStatusDotColor(
                    failureStreak = sharingState.sync.pollFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastPollSuccessAtMs
                ),
                isActive = retrieveWaiting
            )
        }
    }
}

@Composable
private fun MainDelayProgressRow(
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
    val smoothProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = MAIN_PROGRESS_SMOOTH_ANIM_MS,
            easing = LinearEasing
        ),
        label = "mainDelaySmoothProgress"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        val effectiveStatusColor = if (isActive) statusColor else muted
        val labelColor = if (isActive) {
            MaterialTheme.colorScheme.onSurface
        } else {
            muted
        }
        val valueColor = if (isActive) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            muted
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(effectiveStatusColor)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
            }
            Text(
                text = mainFormatDelayMs(remainingMs),
                style = MaterialTheme.typography.labelSmall,
                color = valueColor
            )
        }
        MainDelayProgressBar(progress = smoothProgress, nowMs = nowMs, isActive = isActive)
    }
}

@Composable
private fun MainDelayProgressBar(progress: Float, nowMs: Long, isActive: Boolean) {
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
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
    var boostStartMs by remember { mutableStateOf<Long?>(null) }
    var boostFromProgress by remember { mutableStateOf(0f) }
    var boostToken by remember { mutableStateOf(0L) }
    val displayedProgress = run {
        val startMs = boostStartMs
        if (startMs == null) {
            progress.coerceIn(0f, 1f)
        } else {
            val t = ((nowMs - startMs).toFloat() / MAIN_BAR_TAP_BOOST_DURATION_MS.toFloat()).coerceIn(0f, 1f)
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
                    delay(MAIN_BAR_TAP_BOOST_DURATION_MS)
                    if (boostToken == token) {
                        boostStartMs = null
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (!isActive) {
                drawRect(
                    color = inactiveTrackColor,
                    topLeft = Offset.Zero,
                    size = Size(width, height)
                )
                return@Canvas
            }

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

private fun mainRemainingDelayMs(nowMs: Long, lastAtMs: Long?, totalMs: Long): Long {
    if (totalMs <= 0L) return 0L
    val last = lastAtMs ?: return 0L
    val elapsed = (nowMs - last).coerceAtLeast(0L)
    return (totalMs - elapsed).coerceAtLeast(0L)
}

private fun mainFormatDelayMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}

private fun mainStatusDotColor(failureStreak: Int, lastSuccessAtMs: Long?): Color {
    return when {
        failureStreak >= 2 -> Color(0xFFC62828)
        failureStreak == 1 -> Color(0xFFFB8C00)
        lastSuccessAtMs != null -> Color(0xFF2E7D32)
        else -> Color(0xFFFB8C00)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var lastToggleAtMs by remember(title) { mutableStateOf(0L) }
    val toggleInteractionSource = remember(title, checked) { MutableInteractionSource() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        key(title, checked) {
            Button(
                onClick = {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastToggleAtMs < MAIN_TOGGLE_DEBOUNCE_MS) return@Button
                    lastToggleAtMs = nowMs
                    onCheckedChange(!checked)
                },
                latched = checked,
                hapticMode = NeoButtonHapticMode.ToggleCycle,
                toggleTargetState = checked,
                interactionSource = toggleInteractionSource,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.size(width = 108.dp, height = 56.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                ButtonLabel(
                    text = if (checked) "ON" else "OFF",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ActionWidget(
    title: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(2.2f),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
            )
        }
    }
}

private fun formatRelativeAge(timestampMs: Long?, nowMs: Long): String {
    if (timestampMs == null) return "No fix yet"
    val delta = (nowMs - timestampMs).coerceAtLeast(0L)
    val seconds = delta / 1000L
    return when {
        seconds < 60L -> "$seconds seconds ago"
        seconds < 3600L -> "${seconds / 60L} minutes ago"
        seconds < 86_400L -> "${seconds / 3600L} hours ago"
        else -> "${seconds / 86_400L} days ago"
    }
}

@Composable
private fun RelativeAgeLabel(
    timestampMs: Long?,
    modifier: Modifier = Modifier
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis(), key1 = timestampMs) {
        while (true) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
    Text(
        text = formatRelativeAge(timestampMs = timestampMs, nowMs = nowMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatLatLon(lat: Double?, lon: Double?): String {
    if (lat == null || lon == null) return "No location yet"
    return "${"%.6f".format(java.util.Locale.US, lat)}, ${"%.6f".format(java.util.Locale.US, lon)}"
}

private fun isInsideZone(lat: Double?, lon: Double?, zone: ShareZone): Boolean {
    if (lat == null || lon == null) return false
    val distanceM = SharingLogic.haversineMeters(lat, lon, zone.centerLat, zone.centerLon)
    return distanceM <= zone.radiusM.toDouble()
}
