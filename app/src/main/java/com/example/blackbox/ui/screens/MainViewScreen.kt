package com.example.blackbox.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.SelectableDates
import com.example.blackbox.ui.components.NeoTextButton as TextButton
import com.example.blackbox.ui.components.StaticRadiusMapPreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.blackbox.data.locationdb.LocationPersistenceController
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationEngineMode
import com.example.blackbox.location.LocationEngineForegroundController
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
import com.example.blackbox.ui.components.CycleTimerProgressBar
import com.example.blackbox.ui.components.MapTargetType
import com.example.blackbox.ui.components.NeoButtonHapticMode
import com.example.blackbox.ui.components.rememberTimerActivityPulseActive
import com.example.blackbox.ui.perf.UiPerfSection
import com.example.blackbox.ui.perf.uiPerfDraw
import com.example.blackbox.ui.theme.neomorphicShadow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAIN_DIALOG_WIDTH_FRACTION = 0.96f
private const val MAIN_ACTIVE_LOCATION_EVENT_INTERVAL_MS = 1_000L
private const val MAIN_LOW_POWER_LOCATION_EVENT_INTERVAL_MS = 3 * 60_000L
private const val MAIN_TOGGLE_DEBOUNCE_MS = 120L
private const val MAIN_SEND_TIMER_TAP_HIGH_DEMAND_WINDOW_MS = 8_000L
private const val MAIN_SEND_TIMER_TAP_CONSUMER_ID = "main_send_timer_tap"
private const val MAIN_MAP_USER_FIX_RECENT_WINDOW_MS = 20 * 60_000L
private const val MAIN_HISTORY_DAY_MS = 86_400_000L
private const val MAIN_HISTORY_HEATMAP_BIN_COUNT = 96
private const val MAIN_HISTORY_HEATMAP_PULSE_DURATION_MS = 1_050
private const val MAIN_HISTORY_POST_PULSE_GAP_MS = 140L
private const val MAIN_HISTORY_HEATMAP_FADE_IN_MS = 540
private const val MAIN_HISTORY_SELECTORS_FADE_IN_MS = 460
private const val MAIN_HISTORY_SELECTORS_REVEAL_DELAY_MS = 340L
private const val MAIN_HISTORY_MAP_PATH_MAX_POINTS = 1_500
private const val MAIN_HISTORY_PATH_MAX_ACCURACY_M = 100.0
private const val MAIN_HISTORY_FILTER_MIN_SIGMA_M = 5.0
private const val MAIN_HISTORY_FILTER_PROCESS_BASE_NOISE_M = 6.0
private const val MAIN_HISTORY_FILTER_PROCESS_NOISE_MPS = 12.0
private const val MAIN_HISTORY_FILTER_MAX_SPEED_MPS = 75.0
private const val MAIN_HISTORY_FILTER_BASE_PLAUSIBLE_DISTANCE_M = 25.0
private const val MAIN_HISTORY_FILTER_OUTLIER_PENALTY = 6.0
private const val MAIN_HISTORY_PANEL_EXPAND_ANIM_MS = 180
private val MAIN_TOP_BAR_SCROLL_CLEARANCE = 16.dp
private val MAIN_BOTTOM_BAR_SCROLL_CLEARANCE = 120.dp
private val MAIN_QR_BUTTON_HEIGHT = 56.dp
private val MAIN_HISTORY_HEATMAP_SLOT_HEIGHT = 56.dp
private val MAIN_HISTORY_TIMELINE_HEIGHT = 82.dp
private val MAIN_HISTORY_TIMELINE_CORNER_RADIUS = 12.dp
private val MAIN_HISTORY_HANDLE_WIDTH = 14.dp
private val MAIN_HISTORY_HANDLE_HEIGHT = 56.dp
private val MAIN_HISTORY_PRECISE_LINE_WIDTH = 4.dp
private val MAIN_HISTORY_DRAG_TOUCH_RADIUS = 28.dp
private val MAIN_HISTORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val MAIN_HISTORY_PRECISE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val MAIN_HISTORY_PRECISE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private data class MainFullscreenMapRequest(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val targetType: MapTargetType = MapTargetType.USER,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val userRadiusMeters: Double? = null
)

private data class MainViewLocationState(
    val bestPositionFix: PositionFix?,
    val bestMotionFix: MotionFix?,
    val engineEnabled: Boolean,
    val engineMode: LocationEngineMode
)

private enum class MainHistoryDatePickerTarget {
    START,
    END
}

private enum class MainHistoryDragTarget {
    START,
    END,
    PRECISE
}

private data class MainHistoryHeatmapData(
    val bins: List<Int>,
    val totalSamples: Int,
    val maxBinCount: Int,
    val timelineSamples: List<MainHistoryTimelineSample>
)

private data class MainHistoryTimelineSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyRadiusMeters: Double
)

private data class MainHistoryMapRenderData(
    val pathPoints: List<FullscreenHistoryPathPoint>,
    val selectedPoint: FullscreenHistorySelectedPoint?
)

private data class MainHistoryPlaybackUiState(
    val canPlay: Boolean,
    val isPlaying: Boolean
)

@Composable
fun MainViewScreen(
    modifier: Modifier = Modifier,
    settings: UiSettings,
    onCustomAccentSaved: (String?) -> Unit
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
            bestPositionFix = null,
            bestMotionFix = null,
            engineEnabled = false,
            engineMode = LocationEngineMode.Off
        )
    )
    val sharingState by LocationSharingController.state.collectAsState()
    val persistenceState by LocationPersistenceController.state.collectAsState()
    var lastKnownMapFix by remember { mutableStateOf<PositionFix?>(null) }

    var permissionGranted by rememberSaveable { mutableStateOf(context.hasAnyLocationPermission()) }
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
    var addContactsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var showQrDialogVisible by rememberSaveable { mutableStateOf(false) }
    var viewContactsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var zonesDialogVisible by rememberSaveable { mutableStateOf(false) }
    var databaseDialogVisible by rememberSaveable { mutableStateOf(false) }
    var settingsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var createZoneDialogVisible by rememberSaveable { mutableStateOf(false) }
    var zoneDialogName by rememberSaveable { mutableStateOf("") }
    var zoneDialogRadius by rememberSaveable { mutableStateOf("100") }
    var zoneDialogError by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreenMapRequest by remember {
        mutableStateOf<MainFullscreenMapRequest?>(null)
    }
    var historyMapRenderData by remember {
        mutableStateOf<MainHistoryMapRenderData?>(null)
    }
    var historyPlaybackToggleSignal by remember { mutableIntStateOf(0) }
    var historyPlaybackCancelSignal by remember { mutableIntStateOf(0) }
    var historyPlaybackUiState by remember {
        mutableStateOf(MainHistoryPlaybackUiState(canPlay = false, isPlaying = false))
    }

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

    LaunchedEffect(locationState.bestPositionFix) {
        locationState.bestPositionFix?.let { lastKnownMapFix = it }
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
            LocationEngineForegroundController.stop(context.applicationContext)
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

    val lastFix = locationState.bestPositionFix ?: lastKnownMapFix

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
                    val outerShape = RoundedCornerShape(14.dp)
                    val innerShape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(outerShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .neomorphicShadow(
                                shape = outerShape,
                                pressed = false,
                                addBorder = false,
                                depth = 2.dp,
                                blurRadius = 3.dp
                            )
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(innerShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .neomorphicShadow(
                                    shape = innerShape,
                                    pressed = true,
                                    addBorder = false,
                                    depth = 5.dp,
                                    blurRadius = 10.dp
                                )
                                .padding(2.dp)
                        ) {
                            if (lastFix != null) {
                                StaticRadiusMapPreview(
                                    latitude = lastFix.location.latitude,
                                    longitude = lastFix.location.longitude,
                                    radiusMeters = lastFix.accuracyMeters.toDouble(),
                                    targetType = MapTargetType.USER,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(innerShape)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(innerShape)
                                        .clickable {
                                            fullscreenMapRequest = MainFullscreenMapRequest(
                                                latitude = lastFix.location.latitude,
                                                longitude = lastFix.location.longitude,
                                                radiusMeters = lastFix.accuracyMeters.toDouble(),
                                                targetType = MapTargetType.USER
                                            )
                                        }
                                )
                            } else {
                                Text(
                                    text = "Map unavailable\nNo location yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        RelativeAgeLabel(
                            timestampMs = lastFix?.receivedAtMillis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        item {
            UiPerfSection("Main Refresh Delays Card") {
                MainRefreshDelaysCard(
                    sharingState = sharingState,
                    locationState = locationState
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
                                settingsDialogVisible = true
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
                        title = "Zones",
                        iconRes = android.R.drawable.ic_menu_mapmode,
                        modifier = Modifier.weight(1f),
                        onClick = { zonesDialogVisible = true }
                    )
                    ActionWidget(
                        title = "Contacts",
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
                        title = "Database",
                        iconRes = android.R.drawable.ic_menu_save,
                        modifier = Modifier.weight(1f),
                        onClick = { databaseDialogVisible = true }
                    )
                    ActionWidget(
                        title = "Settings",
                        iconRes = android.R.drawable.ic_menu_manage,
                        modifier = Modifier.weight(1f),
                        onClick = { settingsDialogVisible = true }
                    )
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
            title = "Contacts",
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
                },
                onOpenMap = { lat, lon, radiusMeters ->
                    val myFix = locationState.bestPositionFix
                    val hasRecentMyFix = myFix != null &&
                        (System.currentTimeMillis() - myFix.receivedAtMillis) <= MAIN_MAP_USER_FIX_RECENT_WINDOW_MS
                    fullscreenMapRequest = MainFullscreenMapRequest(
                        latitude = lat,
                        longitude = lon,
                        radiusMeters = radiusMeters,
                        targetType = MapTargetType.CONTACT,
                        userLatitude = if (hasRecentMyFix) myFix?.location?.latitude else null,
                        userLongitude = if (hasRecentMyFix) myFix?.location?.longitude else null,
                        userRadiusMeters = if (hasRecentMyFix) myFix?.accuracyMeters?.toDouble() else null
                    )
                }
            )
        }
    }

    if (zonesDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { zonesDialogVisible = false },
            title = "Zones",
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
                },
                onOpenMap = { lat, lon, radiusMeters ->
                    val myFix = locationState.bestPositionFix
                    val hasRecentMyFix = myFix != null &&
                        (System.currentTimeMillis() - myFix.receivedAtMillis) <= MAIN_MAP_USER_FIX_RECENT_WINDOW_MS
                    fullscreenMapRequest = MainFullscreenMapRequest(
                        latitude = lat,
                        longitude = lon,
                        radiusMeters = radiusMeters,
                        targetType = MapTargetType.ZONE,
                        userLatitude = if (hasRecentMyFix) myFix?.location?.latitude else null,
                        userLongitude = if (hasRecentMyFix) myFix?.location?.longitude else null,
                        userRadiusMeters = if (hasRecentMyFix) myFix?.accuracyMeters?.toDouble() else null
                    )
                }
            )
        }
    }

    if (databaseDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { databaseDialogVisible = false },
            title = "Database"
        ) {
            DatabasePanel(
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (settingsDialogVisible) {
        MainOverlayDialog(
            onDismissRequest = { settingsDialogVisible = false },
            title = "Settings"
        ) {
            SettingsScreen(
                settings = settings,
                onCustomAccentSaved = onCustomAccentSaved,
                modifier = Modifier.fillMaxWidth(),
                embeddedInDialog = true
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

    fullscreenMapRequest?.let { request ->
        val liveUserFix = if (request.targetType == MapTargetType.USER) {
            locationState.bestPositionFix ?: lastKnownMapFix
        } else {
            null
        }
        val effectiveLatitude = liveUserFix?.location?.latitude ?: request.latitude
        val effectiveLongitude = liveUserFix?.location?.longitude ?: request.longitude
        val effectiveRadiusMeters = liveUserFix?.accuracyMeters?.toDouble() ?: request.radiusMeters
        val useLocationHistoryOverlay = request.targetType == MapTargetType.USER
        FullscreenInteractiveMapDialog(
            latitude = effectiveLatitude,
            longitude = effectiveLongitude,
            radiusMeters = effectiveRadiusMeters,
            targetType = request.targetType,
            userLatitude = request.userLatitude,
            userLongitude = request.userLongitude,
            userRadiusMeters = request.userRadiusMeters,
            onDismiss = {
                fullscreenMapRequest = null
                historyMapRenderData = null
                historyPlaybackUiState = MainHistoryPlaybackUiState(canPlay = false, isPlaying = false)
            },
            historyPathPoints = if (useLocationHistoryOverlay) {
                historyMapRenderData?.pathPoints.orEmpty()
            } else {
                emptyList()
            },
            historySelectedPoint = if (useLocationHistoryOverlay) {
                historyMapRenderData?.selectedPoint
            } else {
                null
            },
            showHistorySelectedCenterButton = useLocationHistoryOverlay,
            showHistoryPlayButton = useLocationHistoryOverlay && historyPlaybackUiState.canPlay,
            historyPlayRunning = historyPlaybackUiState.isPlaying,
            onHistoryPlayClick = if (useLocationHistoryOverlay) {
                { historyPlaybackToggleSignal += 1 }
            } else {
                null
            },
            onHistoryManualCenterAction = if (useLocationHistoryOverlay) {
                { historyPlaybackCancelSignal += 1 }
            } else {
                null
            },
            followHistorySelectedPoint = useLocationHistoryOverlay,
            offscreenIndicatorLatitude = if (useLocationHistoryOverlay) {
                (locationState.bestPositionFix ?: lastKnownMapFix)?.location?.latitude
            } else {
                null
            },
            offscreenIndicatorLongitude = if (useLocationHistoryOverlay) {
                (locationState.bestPositionFix ?: lastKnownMapFix)?.location?.longitude
            } else {
                request.userLongitude
            },
            secondaryOffscreenIndicatorLatitude = if (useLocationHistoryOverlay) {
                null
            } else {
                effectiveLatitude
            },
            secondaryOffscreenIndicatorLongitude = if (useLocationHistoryOverlay) {
                null
            } else {
                effectiveLongitude
            },
            showDefaultBackButton = !useLocationHistoryOverlay,
            topOverlay = if (useLocationHistoryOverlay) {
                {
                    MainLocationHistoryOverlayPanel(
                        onDismiss = {
                            fullscreenMapRequest = null
                            historyMapRenderData = null
                            historyPlaybackUiState = MainHistoryPlaybackUiState(canPlay = false, isPlaying = false)
                        },
                        playToggleSignal = historyPlaybackToggleSignal,
                        playbackCancelSignal = historyPlaybackCancelSignal,
                        onMapRenderDataChanged = { historyMapRenderData = it },
                        onPlaybackUiStateChanged = { historyPlaybackUiState = it }
                    )
                }
            } else {
                null
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
                val contentModifier = baseContentModifier
                    .wrapContentHeight()
                    .heightIn(max = 680.dp)
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
                            .fillMaxWidth()
                            .wrapContentHeight()
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
                            modifier = Modifier.fillMaxWidth(),
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
                    .wrapContentHeight()
                Column(
                    modifier = contentModifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                        )
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun MainRefreshDelaysCard(
    sharingState: com.example.blackbox.sharing.LocationSharingState,
    locationState: MainViewLocationState
) {
    val scope = rememberCoroutineScope()
    var sendTapReleaseJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            sendTapReleaseJob?.cancel()
            LocationEngine.unregisterHighDemandConsumer(MAIN_SEND_TIMER_TAP_CONSUMER_ID)
        }
    }
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
    val relayTotal = com.example.blackbox.sharing.RELAY_STATUS_INTERVAL_MS
    val pollTotal = com.example.blackbox.sharing.POLL_INTERVAL_MS
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
    val hasFollowTargets = sharingState.followingCount > 0
    val pollDisplayRemaining = pollRemaining
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
    val retrieveWaiting = hasFollowTargets

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
                isActive = true,
                isInProgress = sharingState.sync.relayStatusChecking,
                onTapAction = { LocationSharingController.manualRelayStatusNow() }
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
                isActive = sendWaiting,
                isInProgress = sharingState.sync.pushRequestInFlight,
                allowTimerDrivenVisualActive = false,
                onTapAction = {
                    LocationEngine.registerHighDemandConsumer(MAIN_SEND_TIMER_TAP_CONSUMER_ID)
                    sendTapReleaseJob?.cancel()
                    sendTapReleaseJob = scope.launch {
                        delay(MAIN_SEND_TIMER_TAP_HIGH_DEMAND_WINDOW_MS)
                        LocationEngine.unregisterHighDemandConsumer(MAIN_SEND_TIMER_TAP_CONSUMER_ID)
                    }
                    LocationSharingController.manualPushNow()
                }
            )
            MainDelayProgressRow(
                label = "Retrieve Locations",
                totalMs = pollTotal,
                remainingMs = pollDisplayRemaining,
                nowMs = nowMs,
                statusColor = mainStatusDotColor(
                    failureStreak = sharingState.sync.pollFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastPollSuccessAtMs
                ),
                isActive = retrieveWaiting,
                isInProgress = sharingState.sync.pollRequestInFlight,
                allowTimerDrivenVisualActive = false,
                onTapAction = { LocationSharingController.manualPollNow() }
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
    isActive: Boolean,
    isInProgress: Boolean = false,
    allowTimerDrivenVisualActive: Boolean = true,
    onTapAction: () -> Unit
) {
    val visualActive = isActive || (
        allowTimerDrivenVisualActive && totalMs > 0L && remainingMs in 1L until totalMs
    )
    val pulseActive = rememberTimerActivityPulseActive(isInProgress = isInProgress)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        val effectiveStatusColor = if (visualActive) statusColor else muted
        val labelColor = if (visualActive) {
            MaterialTheme.colorScheme.onSurface
        } else {
            muted
        }
        val baseValueColor = if (visualActive) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            muted
        }
        val valueColor = if (pulseActive) {
            baseValueColor.copy(alpha = baseValueColor.alpha * 0.55f)
        } else {
            baseValueColor
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
        MainDelayProgressBar(
            totalMs = totalMs,
            remainingMs = remainingMs,
            nowMs = nowMs,
            isActive = visualActive,
            pulse = pulseActive,
            onTapAction = onTapAction
        )
    }
}

@Composable
private fun MainDelayProgressBar(
    totalMs: Long,
    remainingMs: Long,
    nowMs: Long,
    isActive: Boolean,
    pulse: Boolean,
    onTapAction: () -> Unit
) {
    CycleTimerProgressBar(
        totalMs = totalMs,
        remainingMs = remainingMs,
        sampleNowMs = nowMs,
        isActive = isActive,
        pulse = pulse,
        onTap = onTapAction
    )
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
    var lastToggleAtMs by remember(title) { mutableLongStateOf(0L) }
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

@Suppress("InlinedApi")
private fun textHandleMoveHapticCode(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        HapticFeedbackConstants.TEXT_HANDLE_MOVE
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainLocationHistoryOverlayPanel(
    onDismiss: () -> Unit,
    playToggleSignal: Int,
    playbackCancelSignal: Int,
    onMapRenderDataChanged: (MainHistoryMapRenderData?) -> Unit,
    onPlaybackUiStateChanged: (MainHistoryPlaybackUiState) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var userEditedStartDate by remember { mutableStateOf(false) }
    var datePickerTarget by remember { mutableStateOf<MainHistoryDatePickerTarget?>(null) }
    var selectedStartFraction by rememberSaveable { mutableFloatStateOf(0f) }
    var selectedEndFraction by rememberSaveable { mutableFloatStateOf(1f) }
    var preciseFraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }

    val todayUtc = remember { LocalDate.now(ZoneOffset.UTC) }
    val earliestDate by produceState<LocalDate?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            loadMainHistoryEarliestDateUtc()
        }
    }

    LaunchedEffect(earliestDate, todayUtc) {
        val defaultStartDate = defaultMainHistoryStartDate(
            earliestDate = earliestDate,
            todayUtc = todayUtc
        )
        if (startDate == null) {
            startDate = defaultStartDate
        }
        if (endDate == null) {
            endDate = todayUtc
        }
        if (!userEditedStartDate) {
            startDate = defaultStartDate
        }
    }

    val effectiveStartDate = remember(startDate, endDate) {
        when {
            startDate == null && endDate == null -> null
            startDate == null -> endDate
            endDate == null -> startDate
            else -> minOf(startDate!!, endDate!!)
        }
    }
    val effectiveEndDate = remember(startDate, endDate) {
        when {
            startDate == null && endDate == null -> null
            startDate == null -> endDate
            endDate == null -> startDate
            else -> maxOf(startDate!!, endDate!!)
        }
    }
    val heatmapData by produceState<MainHistoryHeatmapData?>(
        initialValue = null,
        key1 = effectiveStartDate,
        key2 = effectiveEndDate
    ) {
        val start = effectiveStartDate
        val end = effectiveEndDate
        if (start == null || end == null) {
            value = null
            return@produceState
        }
        value = null
        value = withContext(Dispatchers.IO) {
            loadMainHistoryHeatmapData(
                startDate = start,
                endDate = end
            )
        }
    }
    val heatmapLoading = effectiveStartDate != null && effectiveEndDate != null && heatmapData == null
    val heatmapLoaded = !heatmapLoading && heatmapData != null
    var heatmapVisible by remember(effectiveStartDate, effectiveEndDate) { mutableStateOf(false) }
    var selectorsVisible by remember(effectiveStartDate, effectiveEndDate) { mutableStateOf(false) }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)

    LaunchedEffect(heatmapLoaded, effectiveStartDate, effectiveEndDate) {
        if (!heatmapLoaded) {
            heatmapVisible = false
            selectorsVisible = false
            return@LaunchedEffect
        }
        heatmapVisible = false
        selectorsVisible = false
        // Wait for one full pulse cycle so loading ends on a soft fade-out.
        delay(MAIN_HISTORY_HEATMAP_PULSE_DURATION_MS.toLong() * 2L)
        delay(MAIN_HISTORY_POST_PULSE_GAP_MS)
        heatmapVisible = true
        delay(MAIN_HISTORY_SELECTORS_REVEAL_DELAY_MS)
        selectorsVisible = true
    }

    LaunchedEffect(selectedStartFraction, selectedEndFraction, preciseFraction) {
        val minGapFraction = 0.001f
        var normalizedStart = selectedStartFraction.coerceIn(0f, 1f)
        var normalizedEnd = selectedEndFraction.coerceIn(0f, 1f)
        if (normalizedEnd - normalizedStart < minGapFraction) {
            normalizedEnd = (normalizedStart + minGapFraction).coerceAtMost(1f)
            normalizedStart = (normalizedEnd - minGapFraction).coerceAtLeast(0f)
        }
        val normalizedPrecise = preciseFraction.coerceIn(normalizedStart, normalizedEnd)
        if (normalizedStart != selectedStartFraction) {
            selectedStartFraction = normalizedStart
        }
        if (normalizedEnd != selectedEndFraction) {
            selectedEndFraction = normalizedEnd
        }
        if (normalizedPrecise != preciseFraction) {
            preciseFraction = normalizedPrecise
        }
    }

    val selectedRangeStartMs = remember(effectiveStartDate, effectiveEndDate, selectedStartFraction) {
        val startDateValue = effectiveStartDate ?: return@remember null
        val endDateValue = effectiveEndDate ?: return@remember null
        val windowStartMs = localDateToUtcStartMillis(startDateValue)
        val windowEndInclusiveMs = localDateToUtcStartMillis(endDateValue.plusDays(1)) - 1L
        mainHistoryMsForFraction(
            windowStartMs = windowStartMs,
            windowEndInclusiveMs = windowEndInclusiveMs,
            fraction = selectedStartFraction
        )
    }
    val selectedRangeEndMs = remember(effectiveStartDate, effectiveEndDate, selectedEndFraction) {
        val startDateValue = effectiveStartDate ?: return@remember null
        val endDateValue = effectiveEndDate ?: return@remember null
        val windowStartMs = localDateToUtcStartMillis(startDateValue)
        val windowEndInclusiveMs = localDateToUtcStartMillis(endDateValue.plusDays(1)) - 1L
        mainHistoryMsForFraction(
            windowStartMs = windowStartMs,
            windowEndInclusiveMs = windowEndInclusiveMs,
            fraction = selectedEndFraction
        )
    }
    val preciseSelectedMs = remember(effectiveStartDate, effectiveEndDate, preciseFraction) {
        val startDateValue = effectiveStartDate ?: return@remember null
        val endDateValue = effectiveEndDate ?: return@remember null
        val windowStartMs = localDateToUtcStartMillis(startDateValue)
        val windowEndInclusiveMs = localDateToUtcStartMillis(endDateValue.plusDays(1)) - 1L
        mainHistoryMsForFraction(
            windowStartMs = windowStartMs,
            windowEndInclusiveMs = windowEndInclusiveMs,
            fraction = preciseFraction
        )
    }
    val qualityFilteredTimelineSamples = remember(heatmapData) {
        heatmapData?.timelineSamples
            .orEmpty()
            .filter { it.accuracyRadiusMeters <= MAIN_HISTORY_PATH_MAX_ACCURACY_M }
    }
    val smoothedTimelineSamples by produceState(
        initialValue = qualityFilteredTimelineSamples,
        key1 = heatmapData
    ) {
        value = if (qualityFilteredTimelineSamples.size < 3) {
            qualityFilteredTimelineSamples
        } else {
            withContext(Dispatchers.Default) {
                smoothMainHistoryTimelineSamples(qualityFilteredTimelineSamples)
            }
        }
    }
    val selectedRangeSamples = remember(smoothedTimelineSamples, selectedRangeStartMs, selectedRangeEndMs) {
        val rangeStartMs = selectedRangeStartMs
        val rangeEndMs = selectedRangeEndMs
        if (rangeStartMs == null || rangeEndMs == null) {
            emptyList()
        } else {
            timelineSamplesInRange(
                samples = smoothedTimelineSamples,
                rangeStartMs = minOf(rangeStartMs, rangeEndMs),
                rangeEndMs = maxOf(rangeStartMs, rangeEndMs)
            )
        }
    }
    val canPlayRange = selectorsVisible && selectedRangeSamples.size >= 2
    val preciseTimelineSample = remember(selectedRangeSamples, preciseSelectedMs) {
        findNearestTimelineSample(
            samples = selectedRangeSamples,
            targetUtcMs = preciseSelectedMs
        )
    }
    val preciseDateTimeParts = remember(preciseSelectedMs, preciseTimelineSample) {
        formatMainHistoryPreciseDateTimeParts(
            utcTimeMs = preciseSelectedMs,
            sample = preciseTimelineSample
        )
    }
    val decimatedPathPoints by produceState(
        initialValue = emptyList<FullscreenHistoryPathPoint>(),
        key1 = selectedRangeSamples
    ) {
        value = if (selectedRangeSamples.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                decimateHistoryPathPoints(
                    samples = selectedRangeSamples,
                    maxPoints = MAIN_HISTORY_MAP_PATH_MAX_POINTS
                )
            }
        }
    }
    val selectedHistoryPoint = remember(preciseTimelineSample) {
        preciseTimelineSample?.let { sample ->
            FullscreenHistorySelectedPoint(
                latitude = sample.latitude,
                longitude = sample.longitude,
                accuracyRadiusMeters = sample.accuracyRadiusMeters
            )
        }
    }

    LaunchedEffect(playToggleSignal) {
        if (playToggleSignal <= 0) return@LaunchedEffect
        if (!canPlayRange) {
            isPlaying = false
            return@LaunchedEffect
        }
        isPlaying = !isPlaying
    }
    LaunchedEffect(playbackCancelSignal) {
        if (playbackCancelSignal <= 0) return@LaunchedEffect
        isPlaying = false
    }
    LaunchedEffect(canPlayRange) {
        if (!canPlayRange) {
            isPlaying = false
        }
    }
    LaunchedEffect(isPlaying, canPlayRange, selectedStartFraction, selectedEndFraction) {
        if (!isPlaying || !canPlayRange) return@LaunchedEffect
        val playStart = selectedStartFraction
        val playEnd = selectedEndFraction
        if (playEnd <= playStart) {
            preciseFraction = playStart
            isPlaying = false
            return@LaunchedEffect
        }
        preciseFraction = playStart
        val totalSpan = (playEnd - playStart).coerceAtLeast(0.00001f)
        val remainingSpan = (playEnd - playStart).coerceAtLeast(0f)
        if (remainingSpan <= 0f) {
            preciseFraction = playEnd
            isPlaying = false
            return@LaunchedEffect
        }
        val totalDurationNs = 30_000_000_000L
        val durationNs = (totalDurationNs.toDouble() * (remainingSpan / totalSpan).toDouble()).toLong()
            .coerceAtLeast(50_000_000L)
        val startPrecise = playStart
        val startNs = withFrameNanos { it }
        var lastFrameNs = startNs
        while (isPlaying) {
            val nowNs = withFrameNanos { it }
            if (nowNs - lastFrameNs < 16_000_000L) continue
            lastFrameNs = nowNs
            val elapsedNs = (nowNs - startNs).coerceAtLeast(0L)
            val progress = (elapsedNs.toDouble() / durationNs.toDouble()).coerceIn(0.0, 1.0)
            preciseFraction = startPrecise + ((playEnd - startPrecise) * progress.toFloat())
            if (preciseFraction >= (playEnd - 0.00001f)) {
                preciseFraction = playEnd
                isPlaying = false
                break
            }
            if (progress >= 1.0) {
                preciseFraction = playEnd
                isPlaying = false
                break
            }
        }
    }

    LaunchedEffect(selectorsVisible, decimatedPathPoints, selectedHistoryPoint) {
        if (!selectorsVisible || (decimatedPathPoints.isEmpty() && selectedHistoryPoint == null)) {
            onMapRenderDataChanged(null)
            return@LaunchedEffect
        }
        onMapRenderDataChanged(
            MainHistoryMapRenderData(
                pathPoints = decimatedPathPoints,
                selectedPoint = selectedHistoryPoint
            )
        )
    }
    LaunchedEffect(canPlayRange, isPlaying) {
        onPlaybackUiStateChanged(
            MainHistoryPlaybackUiState(
                canPlay = canPlayRange,
                isPlaying = isPlaying && canPlayRange
            )
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            onMapRenderDataChanged(null)
            onPlaybackUiStateChanged(MainHistoryPlaybackUiState(canPlay = false, isPlaying = false))
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_media_previous),
                        contentDescription = "Back"
                    )
                }
                Text(
                    text = "Location History",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    latched = expanded,
                    toggleTargetState = expanded,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (expanded) 90f else -90f,
                        animationSpec = tween(
                            durationMillis = MAIN_HISTORY_PANEL_EXPAND_ANIM_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "main_history_expand_arrow_rotation"
                    )
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_media_previous),
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(arrowRotation)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = MAIN_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = MAIN_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = MAIN_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = MAIN_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MainHistoryDateRangeHeader(
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MainHistoryDateButton(
                            text = formatMainHistoryDate(startDate),
                            onClick = { datePickerTarget = MainHistoryDatePickerTarget.START },
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .width(1.dp)
                                .background(dividerColor)
                        )
                        MainHistoryDateButton(
                            text = formatMainHistoryDate(endDate),
                            onClick = { datePickerTarget = MainHistoryDatePickerTarget.END },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    MainHistoryHeatmapSlot(
                        heatmapData = heatmapData,
                        loading = heatmapLoading,
                        heatmapVisible = heatmapVisible,
                        selectorsVisible = selectorsVisible,
                        selectionStartFraction = selectedStartFraction,
                        selectionEndFraction = selectedEndFraction,
                        preciseSelectionFraction = preciseFraction,
                        onSelectionStartFractionChange = { selectedStartFraction = it },
                        onSelectionEndFractionChange = { selectedEndFraction = it },
                        onPreciseSelectionFractionChange = { preciseFraction = it },
                        onManualPreciseSelection = { isPlaying = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AnimatedVisibility(
                        visible = selectorsVisible,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = MAIN_HISTORY_SELECTORS_FADE_IN_MS,
                                easing = FastOutSlowInEasing
                            )
                        ),
                        exit = fadeOut(animationSpec = tween(durationMillis = 120))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = preciseDateTimeParts.first,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(22.dp))
                            Text(
                                text = preciseDateTimeParts.second,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } 
                    }
                }
            }
        }
    }

    datePickerTarget?.let { target ->
        val selectedDate = when (target) {
            MainHistoryDatePickerTarget.START -> startDate
            MainHistoryDatePickerTarget.END -> endDate
        } ?: (earliestDate ?: todayUtc)

        val minSelectableMs = earliestDate?.let(::localDateToUtcStartMillis)
        val maxSelectableMs = localDateToUtcStartMillis(todayUtc)
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtcStartMillis(selectedDate),
            selectableDates = remember(minSelectableMs, maxSelectableMs) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        if (minSelectableMs != null && utcTimeMillis < minSelectableMs) {
                            return false
                        }
                        return utcTimeMillis <= maxSelectableMs
                    }

                    override fun isSelectableYear(year: Int): Boolean = true
                }
            }
        )

        DatePickerDialog(
            onDismissRequest = { datePickerTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedDate = datePickerState.selectedDateMillis?.let(::utcMillisToLocalDate)
                        if (pickedDate != null) {
                            when (target) {
                                MainHistoryDatePickerTarget.START -> {
                                    startDate = pickedDate
                                    userEditedStartDate = true
                                }

                                MainHistoryDatePickerTarget.END -> {
                                    endDate = pickedDate
                                }
                            }
                        }
                        datePickerTarget = null
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { datePickerTarget = null }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun MainHistoryDateRangeHeader(
    color: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelHalfWidthPx = with(density) { 42.dp.toPx() }
    val strokePx = with(density) { 1.dp.toPx() }
    val tickHeightPx = with(density) { 8.dp.toPx() }
    val buttonCenterStartFraction = 0.25f
    val buttonCenterEndFraction = 0.75f

    Box(
        modifier = modifier.height(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width * 0.5f
            val centerY = size.height * 0.45f
            val leftAnchorX = size.width * buttonCenterStartFraction
            val rightAnchorX = size.width * buttonCenterEndFraction
            val leftLabelEdgeX = centerX - labelHalfWidthPx
            val rightLabelEdgeX = centerX + labelHalfWidthPx

            drawLine(
                color = color,
                start = Offset(leftLabelEdgeX, centerY),
                end = Offset(leftAnchorX, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(rightLabelEdgeX, centerY),
                end = Offset(rightAnchorX, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(leftAnchorX, centerY),
                end = Offset(leftAnchorX, (centerY + tickHeightPx).coerceAtMost(size.height)),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(rightAnchorX, centerY),
                end = Offset(rightAnchorX, (centerY + tickHeightPx).coerceAtMost(size.height)),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }
        Text(
            text = "Date Range",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun MainHistoryDateButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(44.dp)
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MainHistoryHeatmapSlot(
    heatmapData: MainHistoryHeatmapData?,
    loading: Boolean,
    heatmapVisible: Boolean,
    selectorsVisible: Boolean,
    selectionStartFraction: Float,
    selectionEndFraction: Float,
    preciseSelectionFraction: Float,
    onSelectionStartFractionChange: (Float) -> Unit,
    onSelectionEndFractionChange: (Float) -> Unit,
    onPreciseSelectionFractionChange: (Float) -> Unit,
    onManualPreciseSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val slotShape = RoundedCornerShape(MAIN_HISTORY_TIMELINE_CORNER_RADIUS)
    val view = LocalView.current
    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val lowColor = Color(0xFF1F334A)
    val highColor = Color(0xFF4DA3FF)
    val pulseColor = Color(0xFF4DA3FF)
    val selectionShellColor = Color(0xFFDDEEFF).copy(alpha = 0.24f)
    val selectionOutlineColor = Color.White.copy(alpha = 0.56f)
    val handleGlyphColor = Color(0xFF1E3B57)
    val preciseLineColor = Color.White.copy(alpha = 0.96f)
    val density = LocalDensity.current
    val slotCornerRadiusPx = with(density) { MAIN_HISTORY_TIMELINE_CORNER_RADIUS.toPx() }
    val handleWidthPx = with(density) { MAIN_HISTORY_HANDLE_WIDTH.toPx() }
    val handleHeightPx = with(density) { MAIN_HISTORY_HANDLE_HEIGHT.toPx() }
    val preciseLineWidthPx = with(density) { MAIN_HISTORY_PRECISE_LINE_WIDTH.toPx() }
    val dragTouchRadiusPx = with(density) { MAIN_HISTORY_DRAG_TOUCH_RADIUS.toPx() }
    val selectionStrokeWidthPx = with(density) { 1.5.dp.toPx() }
    val halfPreciseLinePx = preciseLineWidthPx * 0.5f
    val latestStartFraction by rememberUpdatedState(selectionStartFraction.coerceIn(0f, 1f))
    val latestEndFraction by rememberUpdatedState(selectionEndFraction.coerceIn(0f, 1f))
    val latestPreciseFraction by rememberUpdatedState(preciseSelectionFraction.coerceIn(0f, 1f))
    val updateStartFraction by rememberUpdatedState(onSelectionStartFractionChange)
    val updateEndFraction by rememberUpdatedState(onSelectionEndFractionChange)
    val updatePreciseFraction by rememberUpdatedState(onPreciseSelectionFractionChange)
    val notifyManualPreciseSelection by rememberUpdatedState(onManualPreciseSelection)
    val pulseTransition = rememberInfiniteTransition(label = "main_history_heatmap_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = MAIN_HISTORY_HEATMAP_PULSE_DURATION_MS,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "main_history_heatmap_pulse_alpha"
    )
    val heatmapAlpha by animateFloatAsState(
        targetValue = if (heatmapVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = MAIN_HISTORY_HEATMAP_FADE_IN_MS,
            easing = FastOutSlowInEasing
        ),
        label = "main_history_heatmap_alpha"
    )
    val selectorsAlpha by animateFloatAsState(
        targetValue = if (selectorsVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = MAIN_HISTORY_SELECTORS_FADE_IN_MS,
            easing = FastOutSlowInEasing
        ),
        label = "main_history_selectors_alpha"
    )

    BoxWithConstraints(
        modifier = modifier
            .height(MAIN_HISTORY_TIMELINE_HEIGHT)
            .clip(slotShape)
            .background(MaterialTheme.colorScheme.surface)
            .neomorphicShadow(
                shape = slotShape,
                pressed = true,
                addBorder = false,
                depth = 4.dp,
                blurRadius = 8.dp
            )
            .padding(3.dp)
    ) {
        val barWidthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val minSelectionSpanFraction = remember(barWidthPx, handleWidthPx, preciseLineWidthPx) {
            ((2f * handleWidthPx + preciseLineWidthPx + 1f) / barWidthPx).coerceIn(0f, 1f)
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(slotShape)
                .background(baseColor)
                .then(
                    if (selectorsVisible) {
                        Modifier.pointerInput(
                            barWidthPx,
                            minSelectionSpanFraction,
                            handleWidthPx,
                            preciseLineWidthPx,
                            dragTouchRadiusPx
                        ) {
                            var activeTarget: MainHistoryDragTarget? = null
                            var workingStart = latestStartFraction
                            var workingEnd = latestEndFraction
                            var workingPrecise = latestPreciseFraction
                            var hitStartBoundary = false
                            var hitEndBoundary = false
                            var hitPreciseMin = false
                            var hitPreciseMax = false

                            detectDragGestures(
                                onDragStart = { touchOffset ->
                                    workingStart = latestStartFraction
                                    workingEnd = latestEndFraction
                                    workingPrecise = latestPreciseFraction

                                    if (workingEnd - workingStart < minSelectionSpanFraction) {
                                        workingEnd = (workingStart + minSelectionSpanFraction).coerceAtMost(1f)
                                        workingStart = (workingEnd - minSelectionSpanFraction).coerceAtLeast(0f)
                                    }
                                    val startOuterX = workingStart * barWidthPx
                                    val endOuterX = workingEnd * barWidthPx
                                    val startCenterX = startOuterX + (handleWidthPx * 0.5f)
                                    val endCenterX = endOuterX - (handleWidthPx * 0.5f)
                                    val preciseMinX = startOuterX + handleWidthPx + (preciseLineWidthPx * 0.5f)
                                    val preciseMaxX = endOuterX - handleWidthPx - (preciseLineWidthPx * 0.5f)
                                    val preciseX = (workingPrecise * barWidthPx).coerceIn(preciseMinX, preciseMaxX)
                                    workingPrecise = preciseX / barWidthPx

                                    updateStartFraction(workingStart)
                                    updateEndFraction(workingEnd)
                                    updatePreciseFraction(workingPrecise)

                                    val touchX = touchOffset.x.coerceIn(0f, barWidthPx)
                                    val startDistance = kotlin.math.abs(touchX - startCenterX)
                                    val endDistance = kotlin.math.abs(touchX - endCenterX)
                                    val preciseDistance = kotlin.math.abs(touchX - preciseX)
                                    val nearest = listOf(
                                        MainHistoryDragTarget.START to startDistance,
                                        MainHistoryDragTarget.END to endDistance,
                                        MainHistoryDragTarget.PRECISE to preciseDistance
                                    ).minByOrNull { it.second }
                                    activeTarget = if (nearest != null && nearest.second <= dragTouchRadiusPx) {
                                        nearest.first
                                    } else {
                                        MainHistoryDragTarget.PRECISE
                                    }
                                    if (activeTarget == MainHistoryDragTarget.PRECISE) {
                                        notifyManualPreciseSelection()
                                    }
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    hitStartBoundary = false
                                    hitEndBoundary = false
                                    hitPreciseMin = false
                                    hitPreciseMax = false
                                },
                                onDragEnd = {
                                    activeTarget = null
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                },
                                onDragCancel = {
                                    activeTarget = null
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val target = activeTarget ?: return@detectDragGestures
                                    val deltaFraction = dragAmount.x / barWidthPx
                                    val fingerX = change.position.x.coerceIn(0f, barWidthPx)

                                    when (target) {
                                        MainHistoryDragTarget.START -> {
                                            val proposedStart = workingStart + deltaFraction
                                            val maxStartWithoutPush = (workingEnd - minSelectionSpanFraction).coerceAtLeast(0f)
                                            if (proposedStart <= maxStartWithoutPush) {
                                                workingStart = proposedStart.coerceAtLeast(0f)
                                            } else {
                                                val overflow = proposedStart - maxStartWithoutPush
                                                val pushedEnd = (workingEnd + overflow).coerceAtMost(1f)
                                                workingEnd = pushedEnd
                                                workingStart = (proposedStart).coerceAtMost(workingEnd - minSelectionSpanFraction)
                                            }
                                        }

                                        MainHistoryDragTarget.END -> {
                                            val proposedEnd = workingEnd + deltaFraction
                                            val minEndWithoutPush = (workingStart + minSelectionSpanFraction).coerceAtMost(1f)
                                            if (proposedEnd >= minEndWithoutPush) {
                                                workingEnd = proposedEnd.coerceAtMost(1f)
                                            } else {
                                                val overflow = minEndWithoutPush - proposedEnd
                                                val pushedStart = (workingStart - overflow).coerceAtLeast(0f)
                                                workingStart = pushedStart
                                                workingEnd = (proposedEnd).coerceAtLeast(workingStart + minSelectionSpanFraction)
                                            }
                                        }

                                        MainHistoryDragTarget.PRECISE -> Unit
                                    }

                                    val preciseMinX = (workingStart * barWidthPx) + handleWidthPx + (preciseLineWidthPx * 0.5f)
                                    val preciseMaxX = (workingEnd * barWidthPx) - handleWidthPx - (preciseLineWidthPx * 0.5f)
                                    val nextPreciseX = if (target == MainHistoryDragTarget.PRECISE) {
                                        fingerX.coerceIn(preciseMinX, preciseMaxX)
                                    } else {
                                        (workingPrecise * barWidthPx).coerceIn(preciseMinX, preciseMaxX)
                                    }
                                    workingPrecise = nextPreciseX / barWidthPx

                                    val startAtBoundary = workingStart <= 0f
                                    val endAtBoundary = workingEnd >= 1f
                                    val preciseAtMin = nextPreciseX <= preciseMinX
                                    val preciseAtMax = nextPreciseX >= preciseMaxX
                                    if (startAtBoundary && !hitStartBoundary) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }
                                    if (endAtBoundary && !hitEndBoundary) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }
                                    if (preciseAtMin && !hitPreciseMin) {
                                        view.performHapticFeedback(textHandleMoveHapticCode())
                                    }
                                    if (preciseAtMax && !hitPreciseMax) {
                                        view.performHapticFeedback(textHandleMoveHapticCode())
                                    }
                                    hitStartBoundary = startAtBoundary
                                    hitEndBoundary = endAtBoundary
                                    hitPreciseMin = preciseAtMin
                                    hitPreciseMax = preciseAtMax

                                    updateStartFraction(workingStart)
                                    updateEndFraction(workingEnd)
                                    updatePreciseFraction(workingPrecise)
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val bins = heatmapData?.bins.orEmpty()
            val maxBinCount = heatmapData?.maxBinCount?.coerceAtLeast(1) ?: 1
            var safeStartOuter = selectionStartFraction.coerceIn(0f, 1f)
            var safeEndOuter = selectionEndFraction.coerceIn(0f, 1f)
            if (safeEndOuter - safeStartOuter < minSelectionSpanFraction) {
                safeEndOuter = (safeStartOuter + minSelectionSpanFraction).coerceAtMost(1f)
                safeStartOuter = (safeEndOuter - minSelectionSpanFraction).coerceAtLeast(0f)
            }
            val startOuterX = safeStartOuter * size.width
            val endOuterX = safeEndOuter * size.width
            val startAtBoundary = startOuterX <= 0.5f
            val endAtBoundary = endOuterX >= (size.width - 0.5f)
            val boundaryInsetPx = selectionStrokeWidthPx * 0.5f
            val startHandleX = if (startAtBoundary) {
                startOuterX + boundaryInsetPx
            } else {
                startOuterX
            }
            val endHandleX = if (endAtBoundary) {
                (endOuterX - handleWidthPx) + boundaryInsetPx
            } else {
                endOuterX - handleWidthPx
            }
            val handleDrawWidth = if (startAtBoundary || endAtBoundary) {
                (handleWidthPx - boundaryInsetPx).coerceAtLeast(1f)
            } else {
                handleWidthPx
            }
            val startCenterX = startHandleX + (handleDrawWidth * 0.5f)
            val endCenterX = endHandleX + (handleDrawWidth * 0.5f)
            val preciseMinX = startOuterX + handleWidthPx + halfPreciseLinePx
            val preciseMaxX = endOuterX - handleWidthPx - halfPreciseLinePx
            val preciseX = (preciseSelectionFraction.coerceIn(0f, 1f) * size.width).coerceIn(preciseMinX, preciseMaxX)
            val handleDrawHeight = size.height
            val handleTop = 0f
            val rangeBodyHeight = size.height
            val rangeBodyTop = 0f
            val handleCornerRadius = CornerRadius(
                x = slotCornerRadiusPx,
                y = slotCornerRadiusPx
            )
            val rangeCornerRadius = CornerRadius(
                x = (handleWidthPx * 0.46f).coerceAtLeast(1f),
                y = (handleWidthPx * 0.46f).coerceAtLeast(1f)
            )

            if (bins.isNotEmpty()) {
                val binWidth = size.width / bins.size.toFloat()
                bins.forEachIndexed { index, count ->
                    val intensity = (count.toFloat() / maxBinCount.toFloat()).coerceIn(0f, 1f)
                    val color = lerp(lowColor, highColor, intensity)
                    drawRect(
                        color = color.copy(alpha = (0.18f + (0.72f * intensity)) * heatmapAlpha),
                        topLeft = Offset(x = index * binWidth, y = 0f),
                        size = Size(width = binWidth + 0.75f, height = size.height)
                    )
                }
            }
            if (selectorsAlpha > 0.001f) {
                val rangeWidth = (endOuterX - startOuterX).coerceAtLeast(1f)
                drawRoundRect(
                    color = selectionShellColor.copy(alpha = selectionShellColor.alpha * selectorsAlpha),
                    topLeft = Offset(x = startOuterX, y = rangeBodyTop),
                    size = Size(width = rangeWidth, height = rangeBodyHeight),
                    cornerRadius = rangeCornerRadius
                )
                drawRoundRect(
                    color = selectionOutlineColor.copy(alpha = selectionOutlineColor.alpha * selectorsAlpha),
                    topLeft = Offset(x = startOuterX, y = rangeBodyTop),
                    size = Size(width = rangeWidth, height = rangeBodyHeight),
                    cornerRadius = rangeCornerRadius,
                    style = Stroke(width = selectionStrokeWidthPx)
                )
            }
            if (loading) {
                drawRect(
                    color = pulseColor.copy(alpha = pulseAlpha),
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            if (selectorsAlpha > 0.001f) {
                drawRoundRect(
                    color = selectionShellColor.copy(alpha = selectionShellColor.alpha * selectorsAlpha),
                    topLeft = Offset(x = startHandleX, y = handleTop),
                    size = Size(width = handleDrawWidth, height = handleDrawHeight),
                    cornerRadius = handleCornerRadius
                )
                drawRoundRect(
                    color = selectionOutlineColor.copy(alpha = selectionOutlineColor.alpha * selectorsAlpha),
                    topLeft = Offset(x = startHandleX, y = handleTop),
                    size = Size(width = handleDrawWidth, height = handleDrawHeight),
                    cornerRadius = handleCornerRadius,
                    style = Stroke(width = selectionStrokeWidthPx)
                )
                drawRoundRect(
                    color = selectionShellColor.copy(alpha = selectionShellColor.alpha * selectorsAlpha),
                    topLeft = Offset(x = endHandleX, y = handleTop),
                    size = Size(width = handleDrawWidth, height = handleDrawHeight),
                    cornerRadius = handleCornerRadius
                )
                drawRoundRect(
                    color = selectionOutlineColor.copy(alpha = selectionOutlineColor.alpha * selectorsAlpha),
                    topLeft = Offset(x = endHandleX, y = handleTop),
                    size = Size(width = handleDrawWidth, height = handleDrawHeight),
                    cornerRadius = handleCornerRadius,
                    style = Stroke(width = selectionStrokeWidthPx)
                )

                drawMainHistoryHandleGlyph(
                    centerX = startCenterX,
                    centerY = size.height / 2f,
                    handleWidthPx = handleDrawWidth,
                    handleHeightPx = handleDrawHeight,
                    outwardLeft = true,
                    atBoundary = startAtBoundary,
                    color = handleGlyphColor.copy(alpha = selectorsAlpha)
                )
                drawMainHistoryHandleGlyph(
                    centerX = endCenterX,
                    centerY = size.height / 2f,
                    handleWidthPx = handleDrawWidth,
                    handleHeightPx = handleDrawHeight,
                    outwardLeft = false,
                    atBoundary = endAtBoundary,
                    color = handleGlyphColor.copy(alpha = selectorsAlpha)
                )

                drawRoundRect(
                    color = preciseLineColor.copy(alpha = selectorsAlpha),
                    topLeft = Offset(x = preciseX - halfPreciseLinePx, y = 4f),
                    size = Size(width = preciseLineWidthPx, height = size.height - 8f),
                    cornerRadius = CornerRadius(
                        x = preciseLineWidthPx * 0.5f,
                        y = preciseLineWidthPx * 0.5f
                    )
                )
            }
        }
        if (!loading && heatmapVisible && (heatmapData?.totalSamples ?: 0) == 0) {
            Text(
                text = "No samples in selected range",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun DrawScope.drawMainHistoryHandleGlyph(
    centerX: Float,
    centerY: Float,
    handleWidthPx: Float,
    handleHeightPx: Float,
    outwardLeft: Boolean,
    atBoundary: Boolean,
    color: Color
) {
    val ySpread = handleHeightPx * 0.22f
    val stroke = (handleWidthPx * 0.18f).coerceAtLeast(1.2f)
    if (atBoundary) {
        drawLine(
            color = color,
            start = Offset(centerX, centerY - ySpread),
            end = Offset(centerX, centerY + ySpread),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        return
    }

    val arrowVertexX = if (outwardLeft) {
        centerX - (handleWidthPx * 0.20f)
    } else {
        centerX + (handleWidthPx * 0.20f)
    }
    val arrowArmX = if (outwardLeft) {
        centerX + (handleWidthPx * 0.20f)
    } else {
        centerX - (handleWidthPx * 0.20f)
    }
    drawLine(
        color = color,
        start = Offset(arrowArmX, centerY - ySpread),
        end = Offset(arrowVertexX, centerY),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(arrowArmX, centerY + ySpread),
        end = Offset(arrowVertexX, centerY),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
}

private fun formatMainHistoryDate(date: LocalDate?): String {
    return date?.format(MAIN_HISTORY_DATE_FORMATTER) ?: "--"
}

private fun formatMainHistoryPreciseDateTimeParts(
    utcTimeMs: Long?,
    sample: MainHistoryTimelineSample?
): Pair<String, String> {
    if (utcTimeMs == null) return "--" to "--"
    val zoneOffset = sample?.let(::approximateZoneOffsetForSample) ?: ZoneOffset.UTC
    val zoned = Instant.ofEpochMilli(utcTimeMs).atOffset(zoneOffset)
    val datePart = zoned.format(MAIN_HISTORY_PRECISE_DATE_FORMATTER)
    val timePart = "${zoned.format(MAIN_HISTORY_PRECISE_TIME_FORMATTER)}  ${zoneOffset.id}"
    return datePart to timePart
}

private fun findNearestTimelineSample(
    samples: List<MainHistoryTimelineSample>,
    targetUtcMs: Long?
): MainHistoryTimelineSample? {
    if (samples.isEmpty() || targetUtcMs == null) return null
    var low = 0
    var high = samples.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val value = samples[mid].timestampMs
        when {
            value < targetUtcMs -> low = mid + 1
            value > targetUtcMs -> high = mid - 1
            else -> return samples[mid]
        }
    }
    val lower = samples.getOrNull(high)
    val upper = samples.getOrNull(low)
    return when {
        lower == null -> upper
        upper == null -> lower
        kotlin.math.abs(lower.timestampMs - targetUtcMs) <= kotlin.math.abs(upper.timestampMs - targetUtcMs) -> lower
        else -> upper
    }
}

private fun timelineSamplesInRange(
    samples: List<MainHistoryTimelineSample>,
    rangeStartMs: Long,
    rangeEndMs: Long
): List<MainHistoryTimelineSample> {
    if (samples.isEmpty() || rangeEndMs < rangeStartMs) return emptyList()
    val startIndex = lowerBoundTimelineIndex(samples, rangeStartMs)
    val endExclusive = upperBoundTimelineIndex(samples, rangeEndMs)
    if (startIndex >= endExclusive) return emptyList()
    return samples.subList(startIndex, endExclusive)
}

private fun lowerBoundTimelineIndex(
    samples: List<MainHistoryTimelineSample>,
    targetMs: Long
): Int {
    var low = 0
    var high = samples.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (samples[mid].timestampMs < targetMs) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low.coerceIn(0, samples.size)
}

private fun upperBoundTimelineIndex(
    samples: List<MainHistoryTimelineSample>,
    targetMs: Long
): Int {
    var low = 0
    var high = samples.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (samples[mid].timestampMs <= targetMs) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low.coerceIn(0, samples.size)
}

private fun decimateHistoryPathPoints(
    samples: List<MainHistoryTimelineSample>,
    maxPoints: Int
): List<FullscreenHistoryPathPoint> {
    if (samples.isEmpty()) return emptyList()
    if (samples.size <= maxPoints) {
        return samples.map { sample ->
            FullscreenHistoryPathPoint(
                latitude = sample.latitude,
                longitude = sample.longitude
            )
        }
    }
    val pointLimit = maxPoints.coerceAtLeast(2)
    val step = (samples.size - 1).toDouble() / (pointLimit - 1).toDouble()
    val output = ArrayList<FullscreenHistoryPathPoint>(pointLimit)
    var cursor = 0.0
    repeat(pointLimit) { index ->
        val sample = if (index == pointLimit - 1) {
            samples.last()
        } else {
            samples[cursor.toInt().coerceIn(0, samples.lastIndex)]
        }
        output += FullscreenHistoryPathPoint(
            latitude = sample.latitude,
            longitude = sample.longitude
        )
        cursor += step
    }
    return output
}

private fun smoothMainHistoryTimelineSamples(
    samples: List<MainHistoryTimelineSample>
): List<MainHistoryTimelineSample> {
    if (samples.size < 3) return samples
    val ordered = if (samples.size < 2 || samples.first().timestampMs <= samples.last().timestampMs) {
        samples
    } else {
        samples.sortedBy { it.timestampMs }
    }
    val pointCount = ordered.size
    val referenceLat = ordered.first().latitude
    val referenceLon = ordered.first().longitude
    val metersPerDegreeLat = 111_132.0
    val metersPerDegreeLon = (111_320.0 * kotlin.math.cos(Math.toRadians(referenceLat))).coerceAtLeast(1e-6)

    val measuredX = DoubleArray(pointCount)
    val measuredY = DoubleArray(pointCount)
    val measuredSigma = DoubleArray(pointCount)
    for (index in 0 until pointCount) {
        val sample = ordered[index]
        measuredX[index] = (sample.longitude - referenceLon) * metersPerDegreeLon
        measuredY[index] = (sample.latitude - referenceLat) * metersPerDegreeLat
        measuredSigma[index] = sample.accuracyRadiusMeters.coerceAtLeast(MAIN_HISTORY_FILTER_MIN_SIGMA_M)
    }

    val forwardX = DoubleArray(pointCount)
    val forwardY = DoubleArray(pointCount)
    val forwardSigma = DoubleArray(pointCount)
    forwardX[0] = measuredX[0]
    forwardY[0] = measuredY[0]
    forwardSigma[0] = measuredSigma[0]

    for (index in 1 until pointCount) {
        val dtSeconds = ((ordered[index].timestampMs - ordered[index - 1].timestampMs).coerceAtLeast(0L) / 1_000.0)
        val processSigma = MAIN_HISTORY_FILTER_PROCESS_BASE_NOISE_M +
            (MAIN_HISTORY_FILTER_PROCESS_NOISE_MPS * dtSeconds)
        val priorVariance = (forwardSigma[index - 1] * forwardSigma[index - 1]) + (processSigma * processSigma)

        var measurementSigma = measuredSigma[index]
        val innovationDx = measuredX[index] - forwardX[index - 1]
        val innovationDy = measuredY[index] - forwardY[index - 1]
        val innovationDistance = kotlin.math.hypot(innovationDx, innovationDy)
        val plausibleDistance = MAIN_HISTORY_FILTER_BASE_PLAUSIBLE_DISTANCE_M +
            (MAIN_HISTORY_FILTER_MAX_SPEED_MPS * dtSeconds) +
            (2.0 * forwardSigma[index - 1]) +
            (2.0 * measurementSigma)
        if (innovationDistance > plausibleDistance) {
            measurementSigma *= MAIN_HISTORY_FILTER_OUTLIER_PENALTY
        }

        val measurementVariance = measurementSigma * measurementSigma
        val kalmanGain = priorVariance / (priorVariance + measurementVariance)
        forwardX[index] = forwardX[index - 1] + (kalmanGain * innovationDx)
        forwardY[index] = forwardY[index - 1] + (kalmanGain * innovationDy)
        forwardSigma[index] = kotlin.math.sqrt((1.0 - kalmanGain) * priorVariance)
            .coerceAtLeast(MAIN_HISTORY_FILTER_MIN_SIGMA_M * 0.35)
    }

    val backwardX = DoubleArray(pointCount)
    val backwardY = DoubleArray(pointCount)
    val backwardSigma = DoubleArray(pointCount)
    val lastIndex = pointCount - 1
    backwardX[lastIndex] = measuredX[lastIndex]
    backwardY[lastIndex] = measuredY[lastIndex]
    backwardSigma[lastIndex] = measuredSigma[lastIndex]

    for (index in (lastIndex - 1) downTo 0) {
        val dtSeconds = ((ordered[index + 1].timestampMs - ordered[index].timestampMs).coerceAtLeast(0L) / 1_000.0)
        val processSigma = MAIN_HISTORY_FILTER_PROCESS_BASE_NOISE_M +
            (MAIN_HISTORY_FILTER_PROCESS_NOISE_MPS * dtSeconds)
        val priorVariance = (backwardSigma[index + 1] * backwardSigma[index + 1]) + (processSigma * processSigma)

        var measurementSigma = measuredSigma[index]
        val innovationDx = measuredX[index] - backwardX[index + 1]
        val innovationDy = measuredY[index] - backwardY[index + 1]
        val innovationDistance = kotlin.math.hypot(innovationDx, innovationDy)
        val plausibleDistance = MAIN_HISTORY_FILTER_BASE_PLAUSIBLE_DISTANCE_M +
            (MAIN_HISTORY_FILTER_MAX_SPEED_MPS * dtSeconds) +
            (2.0 * backwardSigma[index + 1]) +
            (2.0 * measurementSigma)
        if (innovationDistance > plausibleDistance) {
            measurementSigma *= MAIN_HISTORY_FILTER_OUTLIER_PENALTY
        }

        val measurementVariance = measurementSigma * measurementSigma
        val kalmanGain = priorVariance / (priorVariance + measurementVariance)
        backwardX[index] = backwardX[index + 1] + (kalmanGain * innovationDx)
        backwardY[index] = backwardY[index + 1] + (kalmanGain * innovationDy)
        backwardSigma[index] = kotlin.math.sqrt((1.0 - kalmanGain) * priorVariance)
            .coerceAtLeast(MAIN_HISTORY_FILTER_MIN_SIGMA_M * 0.35)
    }

    val output = ArrayList<MainHistoryTimelineSample>(pointCount)
    for (index in 0 until pointCount) {
        val forwardVariance = (forwardSigma[index] * forwardSigma[index]).coerceAtLeast(1e-4)
        val backwardVariance = (backwardSigma[index] * backwardSigma[index]).coerceAtLeast(1e-4)
        val forwardWeight = 1.0 / forwardVariance
        val backwardWeight = 1.0 / backwardVariance
        val combinedWeight = (forwardWeight + backwardWeight).coerceAtLeast(1e-6)

        val smoothedX = ((forwardX[index] * forwardWeight) + (backwardX[index] * backwardWeight)) / combinedWeight
        val smoothedY = ((forwardY[index] * forwardWeight) + (backwardY[index] * backwardWeight)) / combinedWeight
        val smoothedSigma = kotlin.math.sqrt(1.0 / combinedWeight)
            .coerceIn(1.0, MAIN_HISTORY_PATH_MAX_ACCURACY_M)

        val latitude = (referenceLat + (smoothedY / metersPerDegreeLat)).coerceIn(-90.0, 90.0)
        val rawLongitude = referenceLon + (smoothedX / metersPerDegreeLon)
        val longitude = ((rawLongitude + 540.0) % 360.0) - 180.0
        val original = ordered[index]
        output += MainHistoryTimelineSample(
            timestampMs = original.timestampMs,
            latitude = latitude,
            longitude = longitude,
            accuracyRadiusMeters = smoothedSigma
        )
    }
    return output
}

private fun approximateZoneOffsetForSample(sample: MainHistoryTimelineSample): ZoneOffset {
    val estimatedHour = kotlin.math.round(sample.longitude / 15.0)
        .toInt()
        .coerceIn(-12, 14)
    return ZoneOffset.ofHours(estimatedHour)
}

private fun mainHistoryMsForFraction(
    windowStartMs: Long,
    windowEndInclusiveMs: Long,
    fraction: Float
): Long {
    if (windowEndInclusiveMs <= windowStartMs) {
        return windowStartMs
    }
    val clampedFraction = fraction.coerceIn(0f, 1f)
    val rangeMs = (windowEndInclusiveMs - windowStartMs).toDouble()
    return windowStartMs + kotlin.math.round(rangeMs * clampedFraction).toLong()
}

private fun localDateToUtcStartMillis(date: LocalDate): Long {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun utcMillisToLocalDate(utcTimeMillis: Long): LocalDate {
    return Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
}

private fun defaultMainHistoryStartDate(
    earliestDate: LocalDate?,
    todayUtc: LocalDate
): LocalDate {
    val defaultStart = todayUtc.minusDays(1)
    val earliest = earliestDate ?: return defaultStart
    return maxOf(earliest, defaultStart)
}

private suspend fun loadMainHistoryEarliestDateUtc(): LocalDate? {
    val archiveEarliest = runCatching {
        LocationPersistenceController.getArchiveRecords()
            .minOfOrNull { it.dayUtc }
    }.getOrElse { throwable ->
        if (throwable is CancellationException) throw throwable
        null
    }
    if (archiveEarliest != null) {
        return archiveEarliest
    }

    val todayUtc = LocalDate.now(ZoneOffset.UTC)
    val startMs = localDateToUtcStartMillis(todayUtc)
    val endMs = System.currentTimeMillis() + MAIN_HISTORY_DAY_MS
    val rows = runCatching {
        LocationPersistenceController.readHistoryRange(
            startInclusiveMs = startMs,
            endInclusiveMs = endMs
        )
    }.getOrElse { throwable ->
        if (throwable is CancellationException) throw throwable
        emptyList()
    }
    val earliestSampleMs = rows.minOfOrNull { it.receivedAtMs } ?: return null
    return Instant.ofEpochMilli(earliestSampleMs).atZone(ZoneOffset.UTC).toLocalDate()
}

private suspend fun loadMainHistoryHeatmapData(
    startDate: LocalDate,
    endDate: LocalDate
): MainHistoryHeatmapData {
    val normalizedStartDate = minOf(startDate, endDate)
    val normalizedEndDate = maxOf(startDate, endDate)
    val startMs = localDateToUtcStartMillis(normalizedStartDate)
    val endExclusiveMs = localDateToUtcStartMillis(normalizedEndDate.plusDays(1))
    val endInclusiveMs = (endExclusiveMs - 1L).coerceAtLeast(startMs)
    val bins = IntArray(MAIN_HISTORY_HEATMAP_BIN_COUNT)
    val samples = runCatching {
        LocationPersistenceController.readHistoryRange(
            startInclusiveMs = startMs,
            endInclusiveMs = endInclusiveMs
        )
    }.getOrElse { throwable ->
        if (throwable is CancellationException) throw throwable
        emptyList()
    }
    val durationMs = (endInclusiveMs - startMs).coerceAtLeast(1L)

    samples.forEach { sample ->
        val offsetMs = (sample.receivedAtMs - startMs).coerceIn(0L, durationMs)
        val index = ((offsetMs.toDouble() / durationMs.toDouble()) * bins.size.toDouble())
            .toInt()
            .coerceIn(0, bins.lastIndex)
        bins[index] += sample.samplesMergedCount.coerceAtLeast(1)
    }
    val timelineSamples = buildMainHistoryTimelineSamples(samples)

    return MainHistoryHeatmapData(
        bins = bins.toList(),
        totalSamples = bins.sum(),
        maxBinCount = bins.maxOrNull() ?: 0,
        timelineSamples = timelineSamples
    )
}

private fun buildMainHistoryTimelineSamples(
    samples: List<com.example.blackbox.data.locationdb.LocationHistorySample>,
    maxPoints: Int = 4_000
): List<MainHistoryTimelineSample> {
    if (samples.isEmpty()) return emptyList()
    if (samples.size <= maxPoints) {
        return samples.map {
            MainHistoryTimelineSample(
                timestampMs = it.receivedAtMs,
                latitude = it.lat,
                longitude = it.lon,
                accuracyRadiusMeters = it.bestAccuracyM.toDouble().coerceAtLeast(1.0)
            )
        }
    }
    val step = (samples.size.toDouble() / maxPoints.toDouble()).coerceAtLeast(1.0)
    val output = ArrayList<MainHistoryTimelineSample>(maxPoints)
    var idx = 0.0
    while (idx < samples.size) {
        val sample = samples[idx.toInt().coerceIn(0, samples.lastIndex)]
        output += MainHistoryTimelineSample(
            timestampMs = sample.receivedAtMs,
            latitude = sample.lat,
            longitude = sample.lon,
            accuracyRadiusMeters = sample.bestAccuracyM.toDouble().coerceAtLeast(1.0)
        )
        idx += step
    }
    return output.sortedBy { it.timestampMs }
}
