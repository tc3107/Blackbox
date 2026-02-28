package com.example.blackbox.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.blackbox.logging.AppLog as Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.graphics.toColorInt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
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
import com.example.blackbox.ui.components.CycleTimerProgressBar
import com.example.blackbox.ui.components.MapTargetType
import com.example.blackbox.ui.components.StaticRadiusMapPreview
import com.example.blackbox.ui.components.rememberTimerActivityPulseActive
import com.example.blackbox.ui.theme.neomorphicShadow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DIALOG_WIDTH_FRACTION = 0.96f
private const val RELAY_STATUS_UI_DEBOUNCE_MS = 800L
private const val EXPANDED_CONTACT_POLL_INTERVAL_MS = 20_000L
private const val ACTIVE_LOCATION_EVENT_INTERVAL_MS = 1_000L
private const val LOW_POWER_LOCATION_EVENT_INTERVAL_MS = 3 * 60_000L
private const val ROW_EXPAND_ANIM_MS = 180
private val SHARING_QR_BUTTON_HEIGHT = 56.dp
private const val FULLSCREEN_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val FULLSCREEN_MAP_MIN_RADIUS_M = 5.0
private const val FULLSCREEN_MAP_MIN_VERTICAL_SPAN_M = 2_400.0
private const val FULLSCREEN_MAP_EXTRA_ZOOM_OUT_FACTOR = 4.8
private const val FULLSCREEN_MAP_EARTH_CIRCUMFERENCE_M = 40_075_016.686
private const val FULLSCREEN_MAP_TILE_SIZE_PX = 512.0
private const val FULLSCREEN_MAP_MAX_ZOOM = 22.0
private val FULLSCREEN_MAP_INNER_CORNER_RADIUS = 20.dp
private val FULLSCREEN_MAP_CONTENT_INSET = 2.dp
private val FULLSCREEN_MAP_RENDER_CORNER_RADIUS = 18.dp
private const val MAP_USER_FIX_RECENT_WINDOW_MS = 20 * 60_000L
private const val FsTargetAreaSourceId = "bbx_fs_target_area_source"
private const val FsTargetCenterSourceId = "bbx_fs_target_center_source"
private const val FsTargetAreaFillLayerId = "bbx_fs_target_area_fill_layer"
private const val FsTargetAreaStrokeLayerId = "bbx_fs_target_area_stroke_layer"
private const val FsTargetCenterOuterLayerId = "bbx_fs_target_center_outer_layer"
private const val FsTargetCenterInnerLayerId = "bbx_fs_target_center_inner_layer"
private const val FsUserAreaSourceId = "bbx_fs_user_area_source"
private const val FsUserCenterSourceId = "bbx_fs_user_center_source"
private const val FsUserAreaFillLayerId = "bbx_fs_user_area_fill_layer"
private const val FsUserAreaStrokeLayerId = "bbx_fs_user_area_stroke_layer"
private const val FsUserCenterOuterLayerId = "bbx_fs_user_center_outer_layer"
private const val FsUserCenterInnerLayerId = "bbx_fs_user_center_inner_layer"
private const val FsHistoryPathSourceId = "bbx_fs_history_path_source"
private const val FsHistoryPathLayerId = "bbx_fs_history_path_layer"
private const val FsHistoryPointAreaSourceId = "bbx_fs_history_point_area_source"
private const val FsHistoryPointCenterSourceId = "bbx_fs_history_point_center_source"
private const val FsHistoryPointAreaFillLayerId = "bbx_fs_history_point_area_fill_layer"
private const val FsHistoryPointAreaStrokeLayerId = "bbx_fs_history_point_area_stroke_layer"
private const val FsHistoryPointCenterOuterLayerId = "bbx_fs_history_point_center_outer_layer"
private const val FsHistoryPointCenterInnerLayerId = "bbx_fs_history_point_center_inner_layer"
private const val CONTACT_HISTORY_HEATMAP_BIN_COUNT = 96
private const val CONTACT_HISTORY_HEATMAP_PULSE_DURATION_MS = 1_050
private const val CONTACT_HISTORY_HEATMAP_FADE_IN_MS = 540
private const val CONTACT_HISTORY_SELECTORS_FADE_IN_MS = 460
private const val CONTACT_HISTORY_SELECTORS_REVEAL_DELAY_MS = 340L
private const val CONTACT_HISTORY_MAP_PATH_MAX_POINTS = 1_500
private const val CONTACT_HISTORY_PATH_MAX_ACCURACY_M = 100.0
private const val CONTACT_HISTORY_FILTER_MIN_SIGMA_M = 5.0
private const val CONTACT_HISTORY_FILTER_PROCESS_BASE_NOISE_M = 6.0
private const val CONTACT_HISTORY_FILTER_PROCESS_NOISE_MPS = 12.0
private const val CONTACT_HISTORY_FILTER_MAX_SPEED_MPS = 75.0
private const val CONTACT_HISTORY_FILTER_BASE_PLAUSIBLE_DISTANCE_M = 25.0
private const val CONTACT_HISTORY_FILTER_OUTLIER_PENALTY = 6.0
private const val CONTACT_HISTORY_PANEL_EXPAND_ANIM_MS = 180
private val CONTACT_HISTORY_TIMELINE_HEIGHT = 82.dp
private val CONTACT_HISTORY_TIMELINE_CORNER_RADIUS = 12.dp
private val CONTACT_HISTORY_HANDLE_WIDTH = 14.dp
private val CONTACT_HISTORY_HANDLE_HEIGHT = 56.dp
private val CONTACT_HISTORY_PRECISE_LINE_WIDTH = 4.dp
private val CONTACT_HISTORY_DRAG_TOUCH_RADIUS = 28.dp
private val CONTACT_HISTORY_RANGE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val CONTACT_HISTORY_RANGE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
private val CONTACT_HISTORY_PRECISE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val CONTACT_HISTORY_PRECISE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private data class FullscreenMapRequest(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val targetType: MapTargetType = MapTargetType.USER,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val userRadiusMeters: Double? = null,
    val historySenderId: String? = null
)

data class FullscreenHistoryPathPoint(
    val latitude: Double,
    val longitude: Double
)

data class FullscreenHistorySelectedPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyRadiusMeters: Double
)

private enum class ContactHistoryDragTarget {
    START,
    END,
    PRECISE
}

private data class ContactHistoryHeatmapData(
    val windowStartMs: Long,
    val windowEndInclusiveMs: Long,
    val bins: List<Int>,
    val totalSamples: Int,
    val maxBinCount: Int,
    val timelineSamples: List<ContactHistoryTimelineSample>,
    val availableRangeStartMs: Long?,
    val availableRangeEndMs: Long?
)

private data class ContactHistoryTimelineSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyRadiusMeters: Double
)

private data class ContactHistoryMapRenderData(
    val pathPoints: List<FullscreenHistoryPathPoint>,
    val selectedPoint: FullscreenHistorySelectedPoint?
)

private data class ContactHistoryPlaybackUiState(
    val canPlay: Boolean,
    val isPlaying: Boolean
)

private enum class EdgeIndicatorSide {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT
}

private data class EdgeIndicatorUiState(
    val center: Offset,
    val side: EdgeIndicatorSide
)

private data class MapCirclePalette(
    val fillHex: String,
    val strokeHex: String,
    val centerOuterHex: String
)

private fun fullscreenPaletteForTarget(targetType: MapTargetType): MapCirclePalette = when (targetType) {
    MapTargetType.USER -> MapCirclePalette(
        fillHex = "#4785FF",
        strokeHex = "#3B82F6",
        centerOuterHex = "#4DA3FF"
    )
    MapTargetType.CONTACT -> MapCirclePalette(
        fillHex = "#2ECC71",
        strokeHex = "#27AE60",
        centerOuterHex = "#36D67A"
    )
    MapTargetType.ZONE -> MapCirclePalette(
        fillHex = "#9AA0A6",
        strokeHex = "#7D858C",
        centerOuterHex = "#B0B6BC"
    )
}

private fun emitTapHaptic(view: android.view.View) {
    if (!view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) {
        view.performHapticFeedback(textHandleMoveHapticCode())
    }
}

private fun emitToggleRowHaptic(view: android.view.View, scope: CoroutineScope) {
    if (!view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) {
        view.performHapticFeedback(textHandleMoveHapticCode())
    }
    scope.launch {
        delay(ROW_EXPAND_ANIM_MS.toLong())
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

@Suppress("InlinedApi")
private fun textHandleMoveHapticCode(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        HapticFeedbackConstants.TEXT_HANDLE_MOVE
    } else {
        HapticFeedbackConstants.KEYBOARD_TAP
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
    var fullscreenMapRequest by remember {
        mutableStateOf<FullscreenMapRequest?>(null)
    }
    var contactHistoryMapRenderData by remember {
        mutableStateOf<ContactHistoryMapRenderData?>(null)
    }
    var contactHistoryPlaybackToggleSignal by rememberSaveable { mutableStateOf(0) }
    var contactHistoryPlaybackCancelSignal by rememberSaveable { mutableStateOf(0) }
    var contactHistoryPlaybackUiState by remember {
        mutableStateOf(ContactHistoryPlaybackUiState(canPlay = false, isPlaying = false))
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
    val launchQrScanner = {
        runCatching {
            scanQrLauncher.launch(Intent(context, QrScannerActivity::class.java))
        }.onFailure { throwable ->
            Log.e(SHARING_DEBUG_TAG, "Failed to launch QR scanner activity from sharing screen", throwable)
            scanManualCodeError = "Could not open QR scanner."
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            scanManualCodeError = "Camera permission is required to scan QR codes."
        }
    }

    fun requestCameraPermissionAndLaunch() {
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            launchQrScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                },
                onOpenMap = { senderId, _, lat, lon, radiusMeters ->
                    val myFix = locationState.bestPositionFix
                    val hasRecentMyFix = myFix != null &&
                        (System.currentTimeMillis() - myFix.receivedAtMillis) <= MAP_USER_FIX_RECENT_WINDOW_MS
                    contactHistoryMapRenderData = null
                    contactHistoryPlaybackUiState = ContactHistoryPlaybackUiState(canPlay = false, isPlaying = false)
                    fullscreenMapRequest = FullscreenMapRequest(
                        latitude = lat,
                        longitude = lon,
                        radiusMeters = radiusMeters,
                        targetType = MapTargetType.CONTACT,
                        userLatitude = if (hasRecentMyFix) myFix?.location?.latitude else null,
                        userLongitude = if (hasRecentMyFix) myFix?.location?.longitude else null,
                        userRadiusMeters = if (hasRecentMyFix) myFix?.accuracyMeters?.toDouble() else null,
                        historySenderId = senderId
                    )
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
                },
                onOpenMap = { lat, lon, radiusMeters ->
                    val myFix = locationState.bestPositionFix
                    val hasRecentMyFix = myFix != null &&
                        (System.currentTimeMillis() - myFix.receivedAtMillis) <= MAP_USER_FIX_RECENT_WINDOW_MS
                    fullscreenMapRequest = FullscreenMapRequest(
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
                requestCameraPermissionAndLaunch()
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

    fullscreenMapRequest?.let { request ->
        val liveIndicatorFix = locationState.bestPositionFix
        val useContactHistoryOverlay = request.targetType == MapTargetType.CONTACT && request.historySenderId != null
        FullscreenInteractiveMapDialog(
            latitude = request.latitude,
            longitude = request.longitude,
            radiusMeters = request.radiusMeters,
            targetType = request.targetType,
            userLatitude = request.userLatitude,
            userLongitude = request.userLongitude,
            userRadiusMeters = request.userRadiusMeters,
            historyPathPoints = if (useContactHistoryOverlay) {
                contactHistoryMapRenderData?.pathPoints.orEmpty()
            } else {
                emptyList()
            },
            historySelectedPoint = if (useContactHistoryOverlay) {
                contactHistoryMapRenderData?.selectedPoint
            } else {
                null
            },
            showHistorySelectedCenterButton = useContactHistoryOverlay,
            showHistoryPlayButton = useContactHistoryOverlay && contactHistoryPlaybackUiState.canPlay,
            historyPlayRunning = contactHistoryPlaybackUiState.isPlaying,
            onHistoryPlayClick = if (useContactHistoryOverlay) {
                { contactHistoryPlaybackToggleSignal += 1 }
            } else {
                null
            },
            onHistoryManualCenterAction = if (useContactHistoryOverlay) {
                { contactHistoryPlaybackCancelSignal += 1 }
            } else {
                null
            },
            followHistorySelectedPoint = useContactHistoryOverlay,
            offscreenIndicatorLatitude = liveIndicatorFix?.location?.latitude ?: request.userLatitude,
            offscreenIndicatorLongitude = liveIndicatorFix?.location?.longitude ?: request.userLongitude,
            secondaryOffscreenIndicatorLatitude = if (useContactHistoryOverlay) {
                contactHistoryMapRenderData?.selectedPoint?.latitude ?: request.latitude
            } else {
                request.latitude
            },
            secondaryOffscreenIndicatorLongitude = if (useContactHistoryOverlay) {
                contactHistoryMapRenderData?.selectedPoint?.longitude ?: request.longitude
            } else {
                request.longitude
            },
            onDismiss = {
                fullscreenMapRequest = null
                contactHistoryMapRenderData = null
                contactHistoryPlaybackUiState = ContactHistoryPlaybackUiState(canPlay = false, isPlaying = false)
            },
            showDefaultBackButton = !useContactHistoryOverlay,
            topOverlay = if (useContactHistoryOverlay) {
                {
                    ContactLocationHistoryOverlayPanel(
                        senderId = request.historySenderId.orEmpty(),
                        onDismiss = {
                            fullscreenMapRequest = null
                            contactHistoryMapRenderData = null
                            contactHistoryPlaybackUiState = ContactHistoryPlaybackUiState(
                                canPlay = false,
                                isPlaying = false
                            )
                        },
                        playToggleSignal = contactHistoryPlaybackToggleSignal,
                        playbackCancelSignal = contactHistoryPlaybackCancelSignal,
                        onPathPointsChanged = { pathPoints ->
                            contactHistoryMapRenderData = if (pathPoints.isEmpty() && contactHistoryMapRenderData?.selectedPoint == null) {
                                null
                            } else {
                                ContactHistoryMapRenderData(
                                    pathPoints = pathPoints,
                                    selectedPoint = contactHistoryMapRenderData?.selectedPoint
                                )
                            }
                        },
                        onSelectedPointChanged = { selectedPoint ->
                            contactHistoryMapRenderData = if (contactHistoryMapRenderData?.pathPoints.isNullOrEmpty() && selectedPoint == null) {
                                null
                            } else {
                                ContactHistoryMapRenderData(
                                    pathPoints = contactHistoryMapRenderData?.pathPoints.orEmpty(),
                                    selectedPoint = selectedPoint
                                )
                            }
                        },
                        onPlaybackUiStateChanged = { canPlay, isPlaying ->
                            contactHistoryPlaybackUiState = ContactHistoryPlaybackUiState(
                                canPlay = canPlay,
                                isPlaying = isPlaying
                            )
                        }
                    )
                }
            } else {
                null
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
fun ContactLocationHistoryOverlayPanel(
    senderId: String,
    onDismiss: () -> Unit,
    playToggleSignal: Int,
    playbackCancelSignal: Int,
    onPathPointsChanged: (List<FullscreenHistoryPathPoint>) -> Unit,
    onSelectedPointChanged: (FullscreenHistorySelectedPoint?) -> Unit,
    onPlaybackUiStateChanged: (canPlay: Boolean, isPlaying: Boolean) -> Unit
) {
    var expanded by rememberSaveable(senderId) { mutableStateOf(true) }
    var selectedStartFraction by rememberSaveable(senderId) { mutableStateOf(0f) }
    var selectedEndFraction by rememberSaveable(senderId) { mutableStateOf(1f) }
    var preciseFraction by rememberSaveable(senderId) { mutableStateOf(1f) }
    var isPlaying by rememberSaveable(senderId) { mutableStateOf(false) }

    val loadResult by produceState<Result<ContactHistoryHeatmapData>?>(initialValue = null, key1 = senderId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                loadContactHistoryHeatmapData(senderId)
            }.onFailure { throwable ->
                if (throwable is kotlinx.coroutines.CancellationException) {
                    throw throwable
                }
            }
        }
    }

    val heatmapData = loadResult?.getOrNull()
    val loadError = loadResult?.exceptionOrNull()?.message
    val heatmapLoading = loadResult == null
    val heatmapLoaded = !heatmapLoading && heatmapData != null
    var heatmapVisible by remember(senderId) { mutableStateOf(false) }
    var selectorsVisible by remember(senderId) { mutableStateOf(false) }

    LaunchedEffect(heatmapLoaded, senderId) {
        if (!heatmapLoaded) {
            heatmapVisible = false
            selectorsVisible = false
            return@LaunchedEffect
        }
        heatmapVisible = false
        selectorsVisible = false
        delay(CONTACT_HISTORY_SELECTORS_REVEAL_DELAY_MS)
        heatmapVisible = true
        selectorsVisible = true
    }

    LaunchedEffect(selectedStartFraction, selectedEndFraction, preciseFraction) {
        var normalizedStart = selectedStartFraction.coerceIn(0f, 1f)
        var normalizedEnd = selectedEndFraction.coerceIn(0f, 1f)
        if (normalizedEnd < normalizedStart) {
            val swap = normalizedStart
            normalizedStart = normalizedEnd
            normalizedEnd = swap
        }
        if (normalizedStart != selectedStartFraction) {
            selectedStartFraction = normalizedStart
        }
        if (normalizedEnd != selectedEndFraction) {
            selectedEndFraction = normalizedEnd
        }
        val normalizedPrecise = preciseFraction.coerceIn(normalizedStart, normalizedEnd)
        if (normalizedPrecise != preciseFraction) {
            preciseFraction = normalizedPrecise
        }
    }

    val selectedRangeStartMs = remember(heatmapData, selectedStartFraction) {
        val data = heatmapData ?: return@remember null
        contactHistoryMsForFraction(
            windowStartMs = data.windowStartMs,
            windowEndInclusiveMs = data.windowEndInclusiveMs,
            fraction = selectedStartFraction
        )
    }
    val selectedRangeEndMs = remember(heatmapData, selectedEndFraction) {
        val data = heatmapData ?: return@remember null
        contactHistoryMsForFraction(
            windowStartMs = data.windowStartMs,
            windowEndInclusiveMs = data.windowEndInclusiveMs,
            fraction = selectedEndFraction
        )
    }
    val preciseSelectedMs = remember(heatmapData, preciseFraction) {
        val data = heatmapData ?: return@remember null
        contactHistoryMsForFraction(
            windowStartMs = data.windowStartMs,
            windowEndInclusiveMs = data.windowEndInclusiveMs,
            fraction = preciseFraction
        )
    }

    val qualityFilteredTimelineSamples = remember(heatmapData) {
        heatmapData?.timelineSamples
            .orEmpty()
            .filter { it.accuracyRadiusMeters <= CONTACT_HISTORY_PATH_MAX_ACCURACY_M }
    }
    val smoothedTimelineSamples by produceState(
        initialValue = qualityFilteredTimelineSamples,
        key1 = heatmapData
    ) {
        value = if (qualityFilteredTimelineSamples.size < 3) {
            qualityFilteredTimelineSamples
        } else {
            withContext(Dispatchers.Default) {
                smoothContactHistoryTimelineSamples(qualityFilteredTimelineSamples)
            }
        }
    }
    val selectedRangeSamples = remember(smoothedTimelineSamples, selectedRangeStartMs, selectedRangeEndMs) {
        val rangeStartMs = selectedRangeStartMs
        val rangeEndMs = selectedRangeEndMs
        if (rangeStartMs == null || rangeEndMs == null) {
            emptyList()
        } else {
            contactTimelineSamplesInRange(
                samples = smoothedTimelineSamples,
                rangeStartMs = minOf(rangeStartMs, rangeEndMs),
                rangeEndMs = maxOf(rangeStartMs, rangeEndMs)
            )
        }
    }
    val canPlayRange = selectorsVisible && selectedRangeSamples.size >= 2
    val preciseTimelineSample = remember(selectedRangeSamples, preciseSelectedMs) {
        findNearestContactTimelineSample(
            samples = selectedRangeSamples,
            targetUtcMs = preciseSelectedMs
        )
    }
    val preciseDateTimeParts = remember(preciseSelectedMs, preciseTimelineSample) {
        formatContactHistoryPreciseDateTimeParts(
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
                decimateContactHistoryPathPoints(
                    samples = selectedRangeSamples,
                    maxPoints = CONTACT_HISTORY_MAP_PATH_MAX_POINTS
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

    LaunchedEffect(selectorsVisible, decimatedPathPoints) {
        val pathPoints = if (selectorsVisible) decimatedPathPoints else emptyList()
        onPathPointsChanged(pathPoints)
    }
    LaunchedEffect(selectorsVisible, selectedHistoryPoint) {
        val point = if (selectorsVisible) selectedHistoryPoint else null
        onSelectedPointChanged(point)
    }
    LaunchedEffect(canPlayRange, isPlaying) {
        onPlaybackUiStateChanged(
            canPlayRange,
            isPlaying && canPlayRange
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            onPathPointsChanged(emptyList())
            onSelectedPointChanged(null)
            onPlaybackUiStateChanged(false, false)
        }
    }

    val availableRangeStartText = remember(heatmapData) {
        formatContactHistoryRangeEndpoint(heatmapData?.availableRangeStartMs)
    }
    val availableRangeEndText = remember(heatmapData) {
        formatContactHistoryRangeEndpoint(heatmapData?.availableRangeEndMs)
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
                        painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_media_previous),
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
                            durationMillis = CONTACT_HISTORY_PANEL_EXPAND_ANIM_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "contact_history_expand_arrow_rotation"
                    )
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_media_previous),
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(arrowRotation)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = tween(
                        durationMillis = CONTACT_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = CONTACT_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = tween(
                        durationMillis = CONTACT_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = CONTACT_HISTORY_PANEL_EXPAND_ANIM_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ContactHistoryLast24hHeader(
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )

                    AnimatedVisibility(visible = heatmapData != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ContactHistoryRangePill(
                                text = availableRangeStartText,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            )
                            ContactHistoryRangePill(
                                text = availableRangeEndText,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    ContactHistoryHeatmapSlot(
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
                    if (!loadError.isNullOrBlank()) {
                        Text(
                            text = loadError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    AnimatedVisibility(
                        visible = selectorsVisible,
                        enter = androidx.compose.animation.fadeIn(
                            animationSpec = tween(
                                durationMillis = CONTACT_HISTORY_SELECTORS_FADE_IN_MS,
                                easing = FastOutSlowInEasing
                            )
                        ),
                        exit = androidx.compose.animation.fadeOut(animationSpec = tween(durationMillis = 120))
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
}

@Composable
private fun ContactHistoryLast24hHeader(
    color: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelHalfWidthPx = with(density) { 34.dp.toPx() }
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
            text = "Last 24h",
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
private fun ContactHistoryRangePill(
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
        disabledContentColor = MaterialTheme.colorScheme.onSurface
    )
    Button(
        onClick = {},
        enabled = false,
        latched = true,
        modifier = modifier
            .height(44.dp)
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = colors
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
private fun ContactHistoryHeatmapSlot(
    heatmapData: ContactHistoryHeatmapData?,
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
    val slotShape = RoundedCornerShape(CONTACT_HISTORY_TIMELINE_CORNER_RADIUS)
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
    val slotCornerRadiusPx = with(density) { CONTACT_HISTORY_TIMELINE_CORNER_RADIUS.toPx() }
    val handleWidthPx = with(density) { CONTACT_HISTORY_HANDLE_WIDTH.toPx() }
    val handleHeightPx = with(density) { CONTACT_HISTORY_HANDLE_HEIGHT.toPx() }
    val preciseLineWidthPx = with(density) { CONTACT_HISTORY_PRECISE_LINE_WIDTH.toPx() }
    val dragTouchRadiusPx = with(density) { CONTACT_HISTORY_DRAG_TOUCH_RADIUS.toPx() }
    val selectionStrokeWidthPx = with(density) { 1.5.dp.toPx() }
    val halfPreciseLinePx = preciseLineWidthPx * 0.5f
    val latestStartFraction by rememberUpdatedState(selectionStartFraction.coerceIn(0f, 1f))
    val latestEndFraction by rememberUpdatedState(selectionEndFraction.coerceIn(0f, 1f))
    val latestPreciseFraction by rememberUpdatedState(preciseSelectionFraction.coerceIn(0f, 1f))
    val updateStartFraction by rememberUpdatedState(onSelectionStartFractionChange)
    val updateEndFraction by rememberUpdatedState(onSelectionEndFractionChange)
    val updatePreciseFraction by rememberUpdatedState(onPreciseSelectionFractionChange)
    val notifyManualPreciseSelection by rememberUpdatedState(onManualPreciseSelection)
    val pulseTransition = rememberInfiniteTransition(label = "contact_history_heatmap_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = CONTACT_HISTORY_HEATMAP_PULSE_DURATION_MS,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "contact_history_heatmap_pulse_alpha"
    )
    val heatmapAlpha by animateFloatAsState(
        targetValue = if (heatmapVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = CONTACT_HISTORY_HEATMAP_FADE_IN_MS,
            easing = FastOutSlowInEasing
        ),
        label = "contact_history_heatmap_alpha"
    )
    val selectorsAlpha by animateFloatAsState(
        targetValue = if (selectorsVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = CONTACT_HISTORY_SELECTORS_FADE_IN_MS,
            easing = FastOutSlowInEasing
        ),
        label = "contact_history_selectors_alpha"
    )

    BoxWithConstraints(
        modifier = modifier
            .height(CONTACT_HISTORY_TIMELINE_HEIGHT)
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
                            var activeTarget: ContactHistoryDragTarget? = null
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
                                        ContactHistoryDragTarget.START to startDistance,
                                        ContactHistoryDragTarget.END to endDistance,
                                        ContactHistoryDragTarget.PRECISE to preciseDistance
                                    ).minByOrNull { it.second }
                                    activeTarget = if (nearest != null && nearest.second <= dragTouchRadiusPx) {
                                        nearest.first
                                    } else {
                                        ContactHistoryDragTarget.PRECISE
                                    }
                                    if (activeTarget == ContactHistoryDragTarget.PRECISE) {
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
                                        ContactHistoryDragTarget.START -> {
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

                                        ContactHistoryDragTarget.END -> {
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

                                        ContactHistoryDragTarget.PRECISE -> Unit
                                    }

                                    val preciseMinX = (workingStart * barWidthPx) + handleWidthPx + (preciseLineWidthPx * 0.5f)
                                    val preciseMaxX = (workingEnd * barWidthPx) - handleWidthPx - (preciseLineWidthPx * 0.5f)
                                    val nextPreciseX = if (target == ContactHistoryDragTarget.PRECISE) {
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

                drawContactHistoryHandleGlyph(
                    centerX = startCenterX,
                    centerY = size.height / 2f,
                    handleWidthPx = handleDrawWidth,
                    handleHeightPx = handleDrawHeight,
                    outwardLeft = true,
                    atBoundary = startAtBoundary,
                    color = handleGlyphColor.copy(alpha = selectorsAlpha)
                )
                drawContactHistoryHandleGlyph(
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

private fun DrawScope.drawContactHistoryHandleGlyph(
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

private fun formatContactHistoryRangeEndpoint(timestampMs: Long?): String {
    if (timestampMs == null) return "--"
    val zoned = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
    return "${zoned.format(CONTACT_HISTORY_RANGE_DATE_FORMATTER)} ${zoned.format(CONTACT_HISTORY_RANGE_TIME_FORMATTER)}"
}

private fun formatContactHistoryPreciseDateTimeParts(
    utcTimeMs: Long?,
    sample: ContactHistoryTimelineSample?
): Pair<String, String> {
    if (utcTimeMs == null) return "--" to "--"
    val zoneOffset = sample?.let(::approximateZoneOffsetForContactSample) ?: java.time.ZoneOffset.UTC
    val zoned = Instant.ofEpochMilli(utcTimeMs).atOffset(zoneOffset)
    val datePart = zoned.format(CONTACT_HISTORY_PRECISE_DATE_FORMATTER)
    val timePart = "${zoned.format(CONTACT_HISTORY_PRECISE_TIME_FORMATTER)}  ${zoneOffset.id}"
    return datePart to timePart
}

private fun findNearestContactTimelineSample(
    samples: List<ContactHistoryTimelineSample>,
    targetUtcMs: Long?
): ContactHistoryTimelineSample? {
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

private fun contactTimelineSamplesInRange(
    samples: List<ContactHistoryTimelineSample>,
    rangeStartMs: Long,
    rangeEndMs: Long
): List<ContactHistoryTimelineSample> {
    if (samples.isEmpty() || rangeEndMs < rangeStartMs) return emptyList()
    val startIndex = lowerBoundContactTimelineIndex(samples, rangeStartMs)
    val endExclusive = upperBoundContactTimelineIndex(samples, rangeEndMs)
    if (startIndex >= endExclusive) return emptyList()
    return samples.subList(startIndex, endExclusive)
}

private fun lowerBoundContactTimelineIndex(
    samples: List<ContactHistoryTimelineSample>,
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

private fun upperBoundContactTimelineIndex(
    samples: List<ContactHistoryTimelineSample>,
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

private fun decimateContactHistoryPathPoints(
    samples: List<ContactHistoryTimelineSample>,
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

private fun smoothContactHistoryTimelineSamples(
    samples: List<ContactHistoryTimelineSample>
): List<ContactHistoryTimelineSample> {
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
        measuredSigma[index] = sample.accuracyRadiusMeters.coerceAtLeast(CONTACT_HISTORY_FILTER_MIN_SIGMA_M)
    }

    val forwardX = DoubleArray(pointCount)
    val forwardY = DoubleArray(pointCount)
    val forwardSigma = DoubleArray(pointCount)
    forwardX[0] = measuredX[0]
    forwardY[0] = measuredY[0]
    forwardSigma[0] = measuredSigma[0]

    for (index in 1 until pointCount) {
        val dtSeconds = ((ordered[index].timestampMs - ordered[index - 1].timestampMs).coerceAtLeast(0L) / 1_000.0)
        val processSigma = CONTACT_HISTORY_FILTER_PROCESS_BASE_NOISE_M +
            (CONTACT_HISTORY_FILTER_PROCESS_NOISE_MPS * dtSeconds)
        val priorVariance = (forwardSigma[index - 1] * forwardSigma[index - 1]) + (processSigma * processSigma)

        var measurementSigma = measuredSigma[index]
        val innovationDx = measuredX[index] - forwardX[index - 1]
        val innovationDy = measuredY[index] - forwardY[index - 1]
        val innovationDistance = kotlin.math.hypot(innovationDx, innovationDy)
        val plausibleDistance = CONTACT_HISTORY_FILTER_BASE_PLAUSIBLE_DISTANCE_M +
            (CONTACT_HISTORY_FILTER_MAX_SPEED_MPS * dtSeconds) +
            (2.0 * forwardSigma[index - 1]) +
            (2.0 * measurementSigma)
        if (innovationDistance > plausibleDistance) {
            measurementSigma *= CONTACT_HISTORY_FILTER_OUTLIER_PENALTY
        }

        val measurementVariance = measurementSigma * measurementSigma
        val kalmanGain = priorVariance / (priorVariance + measurementVariance)
        forwardX[index] = forwardX[index - 1] + (kalmanGain * innovationDx)
        forwardY[index] = forwardY[index - 1] + (kalmanGain * innovationDy)
        forwardSigma[index] = kotlin.math.sqrt((1.0 - kalmanGain) * priorVariance)
            .coerceAtLeast(CONTACT_HISTORY_FILTER_MIN_SIGMA_M * 0.35)
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
        val processSigma = CONTACT_HISTORY_FILTER_PROCESS_BASE_NOISE_M +
            (CONTACT_HISTORY_FILTER_PROCESS_NOISE_MPS * dtSeconds)
        val priorVariance = (backwardSigma[index + 1] * backwardSigma[index + 1]) + (processSigma * processSigma)

        var measurementSigma = measuredSigma[index]
        val innovationDx = measuredX[index] - backwardX[index + 1]
        val innovationDy = measuredY[index] - backwardY[index + 1]
        val innovationDistance = kotlin.math.hypot(innovationDx, innovationDy)
        val plausibleDistance = CONTACT_HISTORY_FILTER_BASE_PLAUSIBLE_DISTANCE_M +
            (CONTACT_HISTORY_FILTER_MAX_SPEED_MPS * dtSeconds) +
            (2.0 * backwardSigma[index + 1]) +
            (2.0 * measurementSigma)
        if (innovationDistance > plausibleDistance) {
            measurementSigma *= CONTACT_HISTORY_FILTER_OUTLIER_PENALTY
        }

        val measurementVariance = measurementSigma * measurementSigma
        val kalmanGain = priorVariance / (priorVariance + measurementVariance)
        backwardX[index] = backwardX[index + 1] + (kalmanGain * innovationDx)
        backwardY[index] = backwardY[index + 1] + (kalmanGain * innovationDy)
        backwardSigma[index] = kotlin.math.sqrt((1.0 - kalmanGain) * priorVariance)
            .coerceAtLeast(CONTACT_HISTORY_FILTER_MIN_SIGMA_M * 0.35)
    }

    val output = ArrayList<ContactHistoryTimelineSample>(pointCount)
    for (index in 0 until pointCount) {
        val forwardVariance = (forwardSigma[index] * forwardSigma[index]).coerceAtLeast(1e-4)
        val backwardVariance = (backwardSigma[index] * backwardSigma[index]).coerceAtLeast(1e-4)
        val forwardWeight = 1.0 / forwardVariance
        val backwardWeight = 1.0 / backwardVariance
        val combinedWeight = (forwardWeight + backwardWeight).coerceAtLeast(1e-6)

        val smoothedX = ((forwardX[index] * forwardWeight) + (backwardX[index] * backwardWeight)) / combinedWeight
        val smoothedY = ((forwardY[index] * forwardWeight) + (backwardY[index] * backwardWeight)) / combinedWeight
        val smoothedSigma = kotlin.math.sqrt(1.0 / combinedWeight)
            .coerceIn(1.0, CONTACT_HISTORY_PATH_MAX_ACCURACY_M)

        val latitude = (referenceLat + (smoothedY / metersPerDegreeLat)).coerceIn(-90.0, 90.0)
        val rawLongitude = referenceLon + (smoothedX / metersPerDegreeLon)
        val longitude = ((rawLongitude + 540.0) % 360.0) - 180.0
        val original = ordered[index]
        output += ContactHistoryTimelineSample(
            timestampMs = original.timestampMs,
            latitude = latitude,
            longitude = longitude,
            accuracyRadiusMeters = smoothedSigma
        )
    }
    return output
}

private fun approximateZoneOffsetForContactSample(sample: ContactHistoryTimelineSample): java.time.ZoneOffset {
    val estimatedHour = kotlin.math.round(sample.longitude / 15.0)
        .toInt()
        .coerceIn(-12, 14)
    return java.time.ZoneOffset.ofHours(estimatedHour)
}

private fun contactHistoryMsForFraction(
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

private suspend fun loadContactHistoryHeatmapData(senderId: String): ContactHistoryHeatmapData {
    val history = LocationSharingController.fetchContactHistoryLast24h(senderId)
        .getOrElse { throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            throw throwable
        }
    val bins = IntArray(CONTACT_HISTORY_HEATMAP_BIN_COUNT)
    val durationMs = (history.windowEndMs - history.windowStartMs).coerceAtLeast(1L)
    val timelineSamples = buildContactHistoryTimelineSamples(history.samples)

    timelineSamples.forEach { sample ->
        val offsetMs = (sample.timestampMs - history.windowStartMs).coerceIn(0L, durationMs)
        val index = ((offsetMs.toDouble() / durationMs.toDouble()) * bins.size.toDouble())
            .toInt()
            .coerceIn(0, bins.lastIndex)
        bins[index] += 1
    }

    val availableStartMs = timelineSamples.minOfOrNull { it.timestampMs }
    val availableEndMs = timelineSamples.maxOfOrNull { it.timestampMs }

    return ContactHistoryHeatmapData(
        windowStartMs = history.windowStartMs,
        windowEndInclusiveMs = history.windowEndMs,
        bins = bins.toList(),
        totalSamples = bins.sum(),
        maxBinCount = bins.maxOrNull() ?: 0,
        timelineSamples = timelineSamples,
        availableRangeStartMs = availableStartMs,
        availableRangeEndMs = availableEndMs
    )
}

private fun buildContactHistoryTimelineSamples(
    samples: List<com.example.blackbox.sharing.ContactHistorySample>,
    maxPoints: Int = 4_000
): List<ContactHistoryTimelineSample> {
    if (samples.isEmpty()) return emptyList()
    val ordered = samples.sortedBy { it.timestampMs }
    if (ordered.size <= maxPoints) {
        return ordered.map {
            ContactHistoryTimelineSample(
                timestampMs = it.timestampMs,
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyRadiusMeters = it.accuracyMeters.coerceAtLeast(1.0)
            )
        }
    }
    val step = (ordered.size.toDouble() / maxPoints.toDouble()).coerceAtLeast(1.0)
    val output = ArrayList<ContactHistoryTimelineSample>(maxPoints)
    var idx = 0.0
    while (idx < ordered.size) {
        val sample = ordered[idx.toInt().coerceIn(0, ordered.lastIndex)]
        output += ContactHistoryTimelineSample(
            timestampMs = sample.timestampMs,
            latitude = sample.latitude,
            longitude = sample.longitude,
            accuracyRadiusMeters = sample.accuracyMeters.coerceAtLeast(1.0)
        )
        idx += step
    }
    return output.sortedBy { it.timestampMs }
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
    val hasFollowTargets = sharingState.followingCount > 0
    val pollDisplayRemaining = if (hasFollowTargets) pollRemaining else pollTotal
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
    val sendDisplayRemaining = if (!sendWaiting) {
        sendDisplayTotal
    } else if (waitingForLocationEvent) {
        remainingDelayMs(
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
            DelayProgressRow(
                label = "Relay Check",
                totalMs = relayTotal,
                remainingMs = relayRemaining,
                nowMs = nowMs,
                statusColor = statusDotColor(
                    failureStreak = sharingState.sync.relayCheckFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastRelayStatusOkAtMs
                ),
                isActive = true,
                isInProgress = sharingState.sync.relayStatusChecking
            )
            DelayProgressRow(
                label = "Retrieve Locations",
                totalMs = pollTotal,
                remainingMs = pollDisplayRemaining,
                nowMs = nowMs,
                statusColor = statusDotColor(
                    failureStreak = sharingState.sync.pollFailureStreak,
                    lastSuccessAtMs = sharingState.sync.lastPollSuccessAtMs
                ),
                isActive = retrieveWaiting,
                isInProgress = sharingState.sync.pollRequestInFlight,
                allowTimerDrivenVisualActive = false
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
                isActive = sendWaiting,
                isInProgress = sharingState.sync.pushRequestInFlight,
                allowTimerDrivenVisualActive = false
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
    isActive: Boolean,
    isInProgress: Boolean = false,
    allowTimerDrivenVisualActive: Boolean = true
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
        val labelColor = if (visualActive) MaterialTheme.colorScheme.onSurface else muted
        val baseValueColor = if (visualActive) MaterialTheme.colorScheme.onSurfaceVariant else muted
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
        DelayProgressBar(
            totalMs = totalMs,
            remainingMs = remainingMs,
            nowMs = nowMs,
            isActive = visualActive,
            pulse = pulseActive
        )
    }
}

@Composable
private fun DelayProgressBar(
    totalMs: Long,
    remainingMs: Long,
    nowMs: Long,
    isActive: Boolean,
    pulse: Boolean
) {
    CycleTimerProgressBar(
        totalMs = totalMs,
        remainingMs = remainingMs,
        sampleNowMs = nowMs,
        isActive = isActive,
        pulse = pulse,
        onTap = null
    )
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
    onRemoveContact: (String) -> Unit,
    onOpenMap: (String, String, Double, Double, Double) -> Unit = { _, _, _, _, _ -> }
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
                            if (contact.iFollow && latestCard != null) {
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
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .neomorphicShadow(
                                            shape = RoundedCornerShape(12.dp),
                                            pressed = true,
                                            addBorder = false,
                                            depth = 5.dp,
                                            blurRadius = 10.dp
                                        )
                                        .padding(2.dp)
                                ) {
                                    StaticRadiusMapPreview(
                                        latitude = latestCard.claim.lat,
                                        longitude = latestCard.claim.lon,
                                        radiusMeters = latestCard.claim.accuracy?.toDouble() ?: 25.0,
                                        targetType = MapTargetType.CONTACT,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable {
                                                emitTapHaptic(view)
                                                onOpenMap(
                                                    contact.senderId,
                                                    contact.displayName,
                                                    latestCard.claim.lat,
                                                    latestCard.claim.lon,
                                                    latestCard.claim.accuracy?.toDouble() ?: 25.0
                                                )
                                            }
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
    onDeleteZone: (String) -> Unit,
    onOpenMap: (Double, Double, Double) -> Unit = { _, _, _ -> }
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .neomorphicShadow(
                                        shape = RoundedCornerShape(12.dp),
                                        pressed = true,
                                        addBorder = false,
                                        depth = 5.dp,
                                        blurRadius = 10.dp
                                    )
                                    .padding(2.dp)
                            ) {
                                StaticRadiusMapPreview(
                                    latitude = zone.centerLat,
                                    longitude = zone.centerLon,
                                    radiusMeters = zone.radiusM.toDouble(),
                                    targetType = MapTargetType.ZONE,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            emitTapHaptic(view)
                                            onOpenMap(
                                                zone.centerLat,
                                                zone.centerLon,
                                                zone.radiusM.toDouble()
                                            )
                                        }
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

@Composable
fun FullscreenInteractiveMapDialog(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    targetType: MapTargetType = MapTargetType.USER,
    userLatitude: Double? = null,
    userLongitude: Double? = null,
    userRadiusMeters: Double? = null,
    historyPathPoints: List<FullscreenHistoryPathPoint> = emptyList(),
    historySelectedPoint: FullscreenHistorySelectedPoint? = null,
    showHistorySelectedCenterButton: Boolean = false,
    showHistoryPlayButton: Boolean = false,
    historyPlayRunning: Boolean = false,
    onHistoryPlayClick: (() -> Unit)? = null,
    onHistoryManualCenterAction: (() -> Unit)? = null,
    followHistorySelectedPoint: Boolean = false,
    offscreenIndicatorLatitude: Double? = null,
    offscreenIndicatorLongitude: Double? = null,
    secondaryOffscreenIndicatorLatitude: Double? = null,
    secondaryOffscreenIndicatorLongitude: Double? = null,
    onDismiss: () -> Unit,
    showDefaultBackButton: Boolean = true,
    topOverlay: (@Composable () -> Unit)? = null
) {
    val mapView = rememberInteractiveMapViewWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var edgeIndicatorState by remember { mutableStateOf<EdgeIndicatorUiState?>(null) }
    var selectedEdgeIndicatorState by remember { mutableStateOf<EdgeIndicatorUiState?>(null) }
    val fallbackBg = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
    val hasUserLocation =
        userLatitude != null && userLongitude != null && userRadiusMeters != null

    BoxWithConstraints {
        val mapHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val zoomLevel = remember(latitude, radiusMeters, mapHeightPx) {
            fullscreenMapZoomForRadiusMeters(
                latitude = latitude,
                radiusMeters = radiusMeters.coerceAtLeast(FULLSCREEN_MAP_MIN_RADIUS_M),
                mapHeightPx = mapHeightPx
            )
        }

        LaunchedEffect(mapLibreMap, latitude, longitude, zoomLevel) {
            mapLibreMap?.let { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(latitude, longitude))
                    .zoom(zoomLevel)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
            }
        }
        LaunchedEffect(
            mapLibreMap,
            latitude,
            longitude,
            radiusMeters,
            userLatitude,
            userLongitude,
            userRadiusMeters
        ) {
            mapLibreMap?.getStyle { style ->
                upsertFullscreenCircleLayers(
                    style = style,
                    targetLatitude = latitude,
                    targetLongitude = longitude,
                    targetRadiusMeters = radiusMeters.coerceAtLeast(FULLSCREEN_MAP_MIN_RADIUS_M),
                    targetType = targetType,
                    showUser = hasUserLocation,
                    userLatitude = userLatitude,
                    userLongitude = userLongitude,
                    userRadiusMeters = userRadiusMeters
                )
            }
        }
        LaunchedEffect(mapLibreMap, historyPathPoints) {
            mapLibreMap?.getStyle { style ->
                upsertFullscreenHistoryPathLayer(
                    style = style,
                    pathPoints = historyPathPoints
                )
            }
        }
        LaunchedEffect(mapLibreMap, historySelectedPoint) {
            mapLibreMap?.getStyle { style ->
                upsertFullscreenHistorySelectedPointLayer(
                    style = style,
                    selectedPoint = historySelectedPoint
                )
            }
        }
        fun computeIndicatorForLatLon(lat: Double?, lon: Double?): EdgeIndicatorUiState? {
            val map = mapLibreMap
            if (map == null || lat == null || lon == null) {
                return null
            }
            val width = mapView.width.toFloat()
            val height = mapView.height.toFloat()
            if (width <= 0f || height <= 0f) {
                return null
            }
            val targetScreen = map.projection.toScreenLocation(LatLng(lat, lon))
            val centerAreaHalfWidth = width * 0.4f
            val centerAreaHalfHeight = height * 0.4f
            val centerX = width * 0.5f
            val centerY = height * 0.5f
            val inCenterArea =
                targetScreen.x in (centerX - centerAreaHalfWidth)..(centerX + centerAreaHalfWidth) &&
                    targetScreen.y in (centerY - centerAreaHalfHeight)..(centerY + centerAreaHalfHeight)
            if (inCenterArea) {
                return null
            }
            return computeEdgeIndicatorState(
                center = Offset(width * 0.5f, height * 0.5f),
                target = Offset(targetScreen.x, targetScreen.y),
                width = width,
                height = height
            )
        }
        fun refreshEdgeIndicators() {
            edgeIndicatorState = computeIndicatorForLatLon(
                lat = offscreenIndicatorLatitude,
                lon = offscreenIndicatorLongitude
            )
            selectedEdgeIndicatorState = computeIndicatorForLatLon(
                lat = historySelectedPoint?.latitude ?: secondaryOffscreenIndicatorLatitude,
                lon = historySelectedPoint?.longitude ?: secondaryOffscreenIndicatorLongitude
            )
        }
        LaunchedEffect(
            mapLibreMap,
            offscreenIndicatorLatitude,
            offscreenIndicatorLongitude,
            historySelectedPoint,
            secondaryOffscreenIndicatorLatitude,
            secondaryOffscreenIndicatorLongitude
        ) {
            refreshEdgeIndicators()
        }
        DisposableEffect(
            mapLibreMap,
            offscreenIndicatorLatitude,
            offscreenIndicatorLongitude,
            historySelectedPoint,
            secondaryOffscreenIndicatorLatitude,
            secondaryOffscreenIndicatorLongitude
        ) {
            val map = mapLibreMap
            if (map == null) {
                onDispose { }
            } else {
                val listener = org.maplibre.android.maps.MapLibreMap.OnCameraMoveListener {
                    refreshEdgeIndicators()
                }
                map.addOnCameraMoveListener(listener)
                onDispose {
                    map.removeOnCameraMoveListener(listener)
                }
            }
        }
        DisposableEffect(mapLibreMap, context) {
            val map = mapLibreMap
            if (map == null) {
                onDispose { }
            } else {
                val listener = object : MapLibreMap.OnMapLongClickListener {
                    override fun onMapLongClick(point: LatLng): Boolean {
                        val coords = formatLatLon(point.latitude, point.longitude)
                        copyToClipboard(context, "blackbox_map_coords", coords)
                        Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
                map.addOnMapLongClickListener(listener)
                onDispose {
                    map.removeOnMapLongClickListener(listener)
                }
            }
        }
        LaunchedEffect(
            mapLibreMap,
            followHistorySelectedPoint,
            historySelectedPoint
        ) {
            if (!followHistorySelectedPoint || historySelectedPoint == null) {
                return@LaunchedEffect
            }
            mapLibreMap?.let { map ->
                val current = map.cameraPosition
                map.cameraPosition = CameraPosition.Builder(current)
                    .target(LatLng(historySelectedPoint.latitude, historySelectedPoint.longitude))
                    .zoom(current.zoom)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
            }
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val outerShape = RoundedCornerShape(24.dp)
            val innerShape = RoundedCornerShape(FULLSCREEN_MAP_INNER_CORNER_RADIUS)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .clip(outerShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .neomorphicShadow(
                        shape = outerShape,
                        pressed = false,
                        addBorder = false,
                        depth = 3.dp,
                        blurRadius = 6.dp
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
                        .padding(FULLSCREEN_MAP_CONTENT_INSET)
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(fallbackBg),
                        factory = {
                            mapView.apply {
                                setBackgroundColor(fallbackBg.copy(alpha = 1f).toArgb())
                                getMapAsync { map ->
                                    map.uiSettings.setAllGesturesEnabled(true)
                                    map.uiSettings.isCompassEnabled = false
                                    map.uiSettings.isLogoEnabled = false
                                    map.uiSettings.isAttributionEnabled = false
                                    map.uiSettings.isRotateGesturesEnabled = false
                                    map.uiSettings.isTiltGesturesEnabled = false
                                    map.setStyle(FULLSCREEN_MAP_STYLE_URL) {
                                        upsertFullscreenCircleLayers(
                                            style = it,
                                            targetLatitude = latitude,
                                            targetLongitude = longitude,
                                            targetRadiusMeters = radiusMeters.coerceAtLeast(FULLSCREEN_MAP_MIN_RADIUS_M),
                                            targetType = targetType,
                                            showUser = hasUserLocation,
                                            userLatitude = userLatitude,
                                            userLongitude = userLongitude,
                                            userRadiusMeters = userRadiusMeters
                                        )
                                        upsertFullscreenHistoryPathLayer(style = it, pathPoints = historyPathPoints)
                                        upsertFullscreenHistorySelectedPointLayer(
                                            style = it,
                                            selectedPoint = historySelectedPoint
                                        )
                                        map.cameraPosition = CameraPosition.Builder()
                                            .target(LatLng(latitude, longitude))
                                            .zoom(zoomLevel)
                                            .bearing(0.0)
                                            .tilt(0.0)
                                            .build()
                                    }
                                    mapLibreMap = map
                                }
                            }
                        },
                        update = {
                            it.setBackgroundColor(fallbackBg.copy(alpha = 1f).toArgb())
                        }
                    )

                }

                if (showDefaultBackButton) {
                    RoundMapActionButton(
                        iconRes = android.R.drawable.ic_media_previous,
                        contentDescription = "Back",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        onClick = onDismiss
                    )
                }

                if (topOverlay != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        topOverlay()
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RoundMapActionButton(
                        iconRes = if (targetType == MapTargetType.USER) {
                            android.R.drawable.ic_menu_mylocation
                        } else {
                            android.R.drawable.ic_menu_myplaces
                        },
                        contentDescription = if (targetType == MapTargetType.USER) {
                            "Recenter"
                        } else {
                            "Center Target"
                        },
                        onClick = {
                            onHistoryManualCenterAction?.invoke()
                            mapLibreMap?.let { map ->
                                val current = map.cameraPosition
                                map.animateCamera(
                                    CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.Builder(current)
                                            .target(LatLng(latitude, longitude))
                                            .zoom(zoomLevel)
                                            .bearing(0.0)
                                            .tilt(0.0)
                                            .build()
                                    )
                                )
                            }
                        }
                    )
                    if (hasUserLocation) {
                        RoundMapActionButton(
                            iconRes = android.R.drawable.ic_menu_compass,
                            contentDescription = "Show My Location",
                            onClick = {
                                onHistoryManualCenterAction?.invoke()
                                mapLibreMap?.let { map ->
                                    val current = map.cameraPosition
                                    map.animateCamera(
                                        CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.Builder(current)
                                                .target(LatLng(userLatitude!!, userLongitude!!))
                                                .zoom(zoomLevel)
                                                .bearing(0.0)
                                                .tilt(0.0)
                                                .build()
                                        )
                                    )
                                }
                            }
                        )
                    }
                    if (showHistorySelectedCenterButton && historySelectedPoint != null) {
                        RoundMapActionButton(
                            iconRes = android.R.drawable.ic_menu_myplaces,
                            contentDescription = "Center Selected Point",
                            onClick = {
                                onHistoryManualCenterAction?.invoke()
                                mapLibreMap?.let { map ->
                                    val current = map.cameraPosition
                                    map.animateCamera(
                                        CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.Builder(current)
                                                .target(
                                                    LatLng(
                                                        historySelectedPoint.latitude,
                                                        historySelectedPoint.longitude
                                                    )
                                                )
                                                .zoom(zoomLevel)
                                                .bearing(0.0)
                                                .tilt(0.0)
                                                .build()
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
                if (showHistoryPlayButton && onHistoryPlayClick != null) {
                    RoundMapActionButton(
                        iconRes = if (historyPlayRunning) {
                            android.R.drawable.ic_media_pause
                        } else {
                            android.R.drawable.ic_media_play
                        },
                        contentDescription = if (historyPlayRunning) {
                            "Pause Selected Range"
                        } else {
                            "Play Selected Range"
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp),
                        onClick = onHistoryPlayClick
                    )
                }
                edgeIndicatorState?.let { indicator ->
                    OffscreenLocationEdgeGlow(
                        state = indicator,
                        glowColor = Color(0xFF5AA4FF),
                        coreColor = Color(0xFFB7D6FF),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(FULLSCREEN_MAP_CONTENT_INSET)
                            .clip(RoundedCornerShape(FULLSCREEN_MAP_RENDER_CORNER_RADIUS))
                    )
                }
                selectedEdgeIndicatorState?.let { indicator ->
                    OffscreenLocationEdgeGlow(
                        state = indicator,
                        glowColor = Color(0xFF41D97A),
                        coreColor = Color(0xFFB7FFCF),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(FULLSCREEN_MAP_CONTENT_INSET)
                            .clip(RoundedCornerShape(FULLSCREEN_MAP_RENDER_CORNER_RADIUS))
                    )
                }
            }
        }
    }
}

@Composable
private fun OffscreenLocationEdgeGlow(
    state: EdgeIndicatorUiState,
    glowColor: Color,
    coreColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val lineLength = 46.dp.toPx()
        val glowStroke = 9.dp.toPx()
        val coreStroke = 2.2.dp.toPx()
        val halfLen = lineLength * 0.5f
        val cornerRadius = FULLSCREEN_MAP_RENDER_CORNER_RADIUS.toPx()
        val center = state.center
        val nearLeft = center.x <= cornerRadius
        val nearRight = center.x >= (size.width - cornerRadius)
        val nearTop = center.y <= cornerRadius
        val nearBottom = center.y >= (size.height - cornerRadius)
        val inCorner = (nearLeft || nearRight) && (nearTop || nearBottom)

        if (inCorner) {
            val cornerCx = when {
                nearLeft -> cornerRadius
                else -> size.width - cornerRadius
            }
            val cornerCy = when {
                nearTop -> cornerRadius
                else -> size.height - cornerRadius
            }
            val angleRad = kotlin.math.atan2(
                (center.y - cornerCy).toDouble(),
                (center.x - cornerCx).toDouble()
            )
            val angleDeg = Math.toDegrees(angleRad).toFloat()
            val sweepFromLength = (lineLength / cornerRadius).coerceIn(0.35f, 1.4f)
            val sweepDeg = Math.toDegrees(sweepFromLength.toDouble()).toFloat()
            val startAngle = angleDeg - (sweepDeg * 0.5f)
            val arcTopLeft = Offset(cornerCx - cornerRadius, cornerCy - cornerRadius)
            val arcSize = Size(cornerRadius * 2f, cornerRadius * 2f)
            drawArc(
                color = glowColor.copy(alpha = 0.38f),
                startAngle = startAngle,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = glowStroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = coreColor,
                startAngle = startAngle,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = coreStroke, cap = StrokeCap.Round)
            )
        } else {
            val (start, end) = when (state.side) {
                EdgeIndicatorSide.TOP, EdgeIndicatorSide.BOTTOM -> {
                    val clampedCenterX = center.x.coerceIn(cornerRadius, size.width - cornerRadius)
                    Offset(clampedCenterX - halfLen, center.y) to Offset(clampedCenterX + halfLen, center.y)
                }

                EdgeIndicatorSide.LEFT, EdgeIndicatorSide.RIGHT -> {
                    val clampedCenterY = center.y.coerceIn(cornerRadius, size.height - cornerRadius)
                    Offset(center.x, clampedCenterY - halfLen) to Offset(center.x, clampedCenterY + halfLen)
                }
            }
            drawLine(
                color = glowColor.copy(alpha = 0.38f),
                start = start,
                end = end,
                strokeWidth = glowStroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = coreColor,
                start = start,
                end = end,
                strokeWidth = coreStroke,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun computeEdgeIndicatorState(
    center: Offset,
    target: Offset,
    width: Float,
    height: Float
): EdgeIndicatorUiState? {
    val dx = target.x - center.x
    val dy = target.y - center.y
    if (kotlin.math.abs(dx) < 0.0001f && kotlin.math.abs(dy) < 0.0001f) {
        return null
    }
    val halfW = width * 0.5f
    val halfH = height * 0.5f
    val tX = if (kotlin.math.abs(dx) < 0.0001f) Float.POSITIVE_INFINITY else halfW / kotlin.math.abs(dx)
    val tY = if (kotlin.math.abs(dy) < 0.0001f) Float.POSITIVE_INFINITY else halfH / kotlin.math.abs(dy)
    val t = minOf(tX, tY)
    val hitX = center.x + (dx * t)
    val hitY = center.y + (dy * t)
    val margin = 3f
    val clampedX = hitX.coerceIn(margin, width - margin)
    val clampedY = hitY.coerceIn(margin, height - margin)
    val side = when {
        kotlin.math.abs(clampedY - margin) < 1f -> EdgeIndicatorSide.TOP
        kotlin.math.abs(clampedY - (height - margin)) < 1f -> EdgeIndicatorSide.BOTTOM
        kotlin.math.abs(clampedX - margin) < 1f -> EdgeIndicatorSide.LEFT
        else -> EdgeIndicatorSide.RIGHT
    }
    return EdgeIndicatorUiState(
        center = Offset(clampedX, clampedY),
        side = side
    )
}

@Composable
private fun RoundMapActionButton(
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val shape = CircleShape
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(50.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .neomorphicShadow(
                shape = shape,
                pressed = pressed,
                addBorder = false,
                depth = 3.dp,
                blurRadius = 6.dp
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                emitTapHaptic(view)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun rememberInteractiveMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            id = android.view.View.generateViewId()
            onCreate(null)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        var mapDestroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!mapDestroyed) mapView.onStart()
                Lifecycle.Event.ON_RESUME -> if (!mapDestroyed) mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> if (!mapDestroyed) mapView.onPause()
                Lifecycle.Event.ON_STOP -> if (!mapDestroyed) mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    if (!mapDestroyed) {
                        mapView.onDestroy()
                        mapDestroyed = true
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()

        onDispose {
            lifecycle.removeObserver(observer)
            if (!mapDestroyed) {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    return mapView
}

private fun fullscreenMapZoomForRadiusMeters(
    latitude: Double,
    radiusMeters: Double,
    mapHeightPx: Float
): Double {
    val verticalSpanMeters = (radiusMeters * 6.0 * FULLSCREEN_MAP_EXTRA_ZOOM_OUT_FACTOR)
        .coerceAtLeast(FULLSCREEN_MAP_MIN_VERTICAL_SPAN_M)
    val metersPerPixel = verticalSpanMeters / mapHeightPx.coerceAtLeast(1f)
    val latitudeCos = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
    val zoom = log2(
        (latitudeCos * FULLSCREEN_MAP_EARTH_CIRCUMFERENCE_M) /
            (FULLSCREEN_MAP_TILE_SIZE_PX * metersPerPixel)
    )
    return zoom.coerceIn(0.0, FULLSCREEN_MAP_MAX_ZOOM)
}

private fun upsertFullscreenCircleLayers(
    style: Style,
    targetLatitude: Double,
    targetLongitude: Double,
    targetRadiusMeters: Double,
    targetType: MapTargetType,
    showUser: Boolean,
    userLatitude: Double?,
    userLongitude: Double?,
    userRadiusMeters: Double?
) {
    val targetPalette = fullscreenPaletteForTarget(targetType)
    val targetCenter = Point.fromLngLat(targetLongitude, targetLatitude)
    val targetPolygon = buildMapCirclePolygon(
        latitude = targetLatitude,
        longitude = targetLongitude,
        radiusMeters = targetRadiusMeters
    )

    val targetAreaSource = (style.getSource(FsTargetAreaSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsTargetAreaSourceId, Feature.fromGeometry(targetPolygon)).also(style::addSource)
    targetAreaSource.setGeoJson(Feature.fromGeometry(targetPolygon))

    val targetCenterSource = (style.getSource(FsTargetCenterSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsTargetCenterSourceId, Feature.fromGeometry(targetCenter)).also(style::addSource)
    targetCenterSource.setGeoJson(Feature.fromGeometry(targetCenter))

    val targetAreaFillLayer = (style.getLayer(FsTargetAreaFillLayerId) as? FillLayer)
        ?: FillLayer(FsTargetAreaFillLayerId, FsTargetAreaSourceId).also(style::addLayer)
    targetAreaFillLayer.setProperties(
        fillColor(targetPalette.fillHex.toColorInt()),
        fillOpacity(0.20f)
    )

    val targetAreaStrokeLayer = (style.getLayer(FsTargetAreaStrokeLayerId) as? LineLayer)
        ?: LineLayer(FsTargetAreaStrokeLayerId, FsTargetAreaSourceId).also(style::addLayer)
    targetAreaStrokeLayer.setProperties(
        lineColor(targetPalette.strokeHex.toColorInt()),
        lineOpacity(0.90f),
        lineWidth(2f)
    )

    val targetCenterOuterLayer = (style.getLayer(FsTargetCenterOuterLayerId) as? CircleLayer)
        ?: CircleLayer(FsTargetCenterOuterLayerId, FsTargetCenterSourceId).also(style::addLayer)
    targetCenterOuterLayer.setProperties(
        circleColor(targetPalette.centerOuterHex.toColorInt()),
        circleOpacity(1f),
        circleRadius(5f)
    )

    val targetCenterInnerLayer = (style.getLayer(FsTargetCenterInnerLayerId) as? CircleLayer)
        ?: CircleLayer(FsTargetCenterInnerLayerId, FsTargetCenterSourceId).also(style::addLayer)
    targetCenterInnerLayer.setProperties(
        circleColor(android.graphics.Color.WHITE),
        circleOpacity(1f),
        circleRadius(2f)
    )

    val hasUser = showUser && userLatitude != null && userLongitude != null && userRadiusMeters != null
    val userAreaSource = (style.getSource(FsUserAreaSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsUserAreaSourceId, Feature.fromGeometry(targetPolygon)).also(style::addSource)
    val userCenterSource = (style.getSource(FsUserCenterSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsUserCenterSourceId, Feature.fromGeometry(targetCenter)).also(style::addSource)

    if (hasUser) {
        val userCenter = Point.fromLngLat(userLongitude!!, userLatitude!!)
        val userPolygon = buildMapCirclePolygon(
            latitude = userLatitude,
            longitude = userLongitude,
            radiusMeters = userRadiusMeters!!.coerceAtLeast(FULLSCREEN_MAP_MIN_RADIUS_M)
        )
        userAreaSource.setGeoJson(Feature.fromGeometry(userPolygon))
        userCenterSource.setGeoJson(Feature.fromGeometry(userCenter))
    } else {
        userAreaSource.setGeoJson(Feature.fromGeometry(targetPolygon))
        userCenterSource.setGeoJson(Feature.fromGeometry(targetCenter))
    }

    if (style.getLayer(FsUserAreaFillLayerId) == null) {
        style.addLayer(
            FillLayer(FsUserAreaFillLayerId, FsUserAreaSourceId).withProperties(
                fillColor("#4785FF".toColorInt()),
                fillOpacity(if (hasUser) 0.20f else 0f)
            )
        )
    } else {
        (style.getLayer(FsUserAreaFillLayerId) as FillLayer).setProperties(
            fillOpacity(if (hasUser) 0.20f else 0f)
        )
    }
    if (style.getLayer(FsUserAreaStrokeLayerId) == null) {
        style.addLayer(
            LineLayer(FsUserAreaStrokeLayerId, FsUserAreaSourceId).withProperties(
                lineColor("#3B82F6".toColorInt()),
                lineOpacity(if (hasUser) 0.95f else 0f),
                lineWidth(2f)
            )
        )
    } else {
        (style.getLayer(FsUserAreaStrokeLayerId) as LineLayer).setProperties(
            lineOpacity(if (hasUser) 0.95f else 0f)
        )
    }
    if (style.getLayer(FsUserCenterOuterLayerId) == null) {
        style.addLayer(
            CircleLayer(FsUserCenterOuterLayerId, FsUserCenterSourceId).withProperties(
                circleColor("#4DA3FF".toColorInt()),
                circleOpacity(if (hasUser) 1f else 0f),
                circleRadius(5f)
            )
        )
    } else {
        (style.getLayer(FsUserCenterOuterLayerId) as CircleLayer).setProperties(
            circleOpacity(if (hasUser) 1f else 0f)
        )
    }
    if (style.getLayer(FsUserCenterInnerLayerId) == null) {
        style.addLayer(
            CircleLayer(FsUserCenterInnerLayerId, FsUserCenterSourceId).withProperties(
                circleColor(android.graphics.Color.WHITE),
                circleOpacity(if (hasUser) 1f else 0f),
                circleRadius(2f)
            )
        )
    } else {
        (style.getLayer(FsUserCenterInnerLayerId) as CircleLayer).setProperties(
            circleOpacity(if (hasUser) 1f else 0f)
        )
    }
}

private fun upsertFullscreenHistoryPathLayer(
    style: Style,
    pathPoints: List<FullscreenHistoryPathPoint>
) {
    val hasPath = pathPoints.size >= 2
    val pathLineString = if (hasPath) {
        LineString.fromLngLats(
            pathPoints.map { point ->
                Point.fromLngLat(point.longitude, point.latitude)
            }
        )
    } else {
        LineString.fromLngLats(
            listOf(
                Point.fromLngLat(0.0, 0.0),
                Point.fromLngLat(0.0, 0.0)
            )
        )
    }
    val pathSource = (style.getSource(FsHistoryPathSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsHistoryPathSourceId, Feature.fromGeometry(pathLineString)).also(style::addSource)
    pathSource.setGeoJson(Feature.fromGeometry(pathLineString))
    val pathLayer = (style.getLayer(FsHistoryPathLayerId) as? LineLayer)
        ?: LineLayer(FsHistoryPathLayerId, FsHistoryPathSourceId).also(style::addLayer)
    pathLayer.setProperties(
        lineColor("#2D7DFF".toColorInt()),
        lineOpacity(if (hasPath) 0.94f else 0f),
        lineWidth(3.2f)
    )
}

private fun upsertFullscreenHistorySelectedPointLayer(
    style: Style,
    selectedPoint: FullscreenHistorySelectedPoint?
) {
    val hasSelectedPoint = selectedPoint != null
    val fallbackCenter = Point.fromLngLat(0.0, 0.0)
    val fallbackPolygon = buildMapCirclePolygon(
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = FULLSCREEN_MAP_MIN_RADIUS_M
    )
    val selectedCenter = if (selectedPoint != null) {
        Point.fromLngLat(selectedPoint.longitude, selectedPoint.latitude)
    } else {
        fallbackCenter
    }
    val selectedPolygon = if (selectedPoint != null) {
        buildMapCirclePolygon(
            latitude = selectedPoint.latitude,
            longitude = selectedPoint.longitude,
            radiusMeters = selectedPoint.accuracyRadiusMeters.coerceAtLeast(FULLSCREEN_MAP_MIN_RADIUS_M)
        )
    } else {
        fallbackPolygon
    }

    val selectedAreaSource = (style.getSource(FsHistoryPointAreaSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsHistoryPointAreaSourceId, Feature.fromGeometry(selectedPolygon)).also(style::addSource)
    selectedAreaSource.setGeoJson(Feature.fromGeometry(selectedPolygon))
    val selectedCenterSource = (style.getSource(FsHistoryPointCenterSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(FsHistoryPointCenterSourceId, Feature.fromGeometry(selectedCenter)).also(style::addSource)
    selectedCenterSource.setGeoJson(Feature.fromGeometry(selectedCenter))

    val selectedAreaFillLayer = (style.getLayer(FsHistoryPointAreaFillLayerId) as? FillLayer)
        ?: FillLayer(FsHistoryPointAreaFillLayerId, FsHistoryPointAreaSourceId).also(style::addLayer)
    selectedAreaFillLayer.setProperties(
        fillColor("#2ECC71".toColorInt()),
        fillOpacity(if (hasSelectedPoint) 0.24f else 0f)
    )

    val selectedAreaStrokeLayer = (style.getLayer(FsHistoryPointAreaStrokeLayerId) as? LineLayer)
        ?: LineLayer(FsHistoryPointAreaStrokeLayerId, FsHistoryPointAreaSourceId).also(style::addLayer)
    selectedAreaStrokeLayer.setProperties(
        lineColor("#27AE60".toColorInt()),
        lineOpacity(if (hasSelectedPoint) 0.95f else 0f),
        lineWidth(2f)
    )

    val selectedCenterOuterLayer = (style.getLayer(FsHistoryPointCenterOuterLayerId) as? CircleLayer)
        ?: CircleLayer(FsHistoryPointCenterOuterLayerId, FsHistoryPointCenterSourceId).also(style::addLayer)
    selectedCenterOuterLayer.setProperties(
        circleColor("#36D67A".toColorInt()),
        circleOpacity(if (hasSelectedPoint) 1f else 0f),
        circleRadius(5f)
    )

    val selectedCenterInnerLayer = (style.getLayer(FsHistoryPointCenterInnerLayerId) as? CircleLayer)
        ?: CircleLayer(FsHistoryPointCenterInnerLayerId, FsHistoryPointCenterSourceId).also(style::addLayer)
    selectedCenterInnerLayer.setProperties(
        circleColor(android.graphics.Color.WHITE),
        circleOpacity(if (hasSelectedPoint) 1f else 0f),
        circleRadius(2f)
    )
}

private fun buildMapCirclePolygon(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    segments: Int = 72
): Polygon {
    val earthRadius = 6_371_000.0
    val angularDistance = radiusMeters / earthRadius
    val latRad = Math.toRadians(latitude)
    val lonRad = Math.toRadians(longitude)
    val points = ArrayList<Point>(segments + 1)

    for (i in 0..segments) {
        val bearing = (2.0 * Math.PI * i) / segments.toDouble()
        val sinLat = kotlin.math.sin(latRad)
        val cosLat = kotlin.math.cos(latRad)
        val sinAng = kotlin.math.sin(angularDistance)
        val cosAng = kotlin.math.cos(angularDistance)
        val lat2 = kotlin.math.asin(sinLat * cosAng + cosLat * sinAng * kotlin.math.cos(bearing))
        val lon2 = lonRad + kotlin.math.atan2(
            kotlin.math.sin(bearing) * sinAng * cosLat,
            cosAng - sinLat * kotlin.math.sin(lat2)
        )
        points.add(Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2)))
    }
    return Polygon.fromLngLats(listOf(points))
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
