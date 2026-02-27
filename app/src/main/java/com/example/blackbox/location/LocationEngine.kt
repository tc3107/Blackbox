package com.example.blackbox.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val ACTIVE_UPDATE_INTERVAL_MS = 1_000L
private const val LOW_POWER_NETWORK_INTERVAL_MS = 3 * 60_000L
private const val FIX_MAX_AGE_ACTIVE_MS = 30_000L
private const val FIX_MAX_AGE_LOW_POWER_MS = 10 * 60_000L
private const val ACCURACY_SIMILARITY_TOLERANCE_METERS = 10f
private const val MOTION_MAX_ACCURACY_METERS = 50f
private const val MOTION_MAX_AGE_MS = 30_000L
private const val PROVIDER_SWITCH_IMPROVEMENT_METERS = 8f
private const val PROVIDER_SWITCH_STABILITY_MS = 5_000L
private const val SIGNIFICANT_MOTION_ACTIVE_WINDOW_MS = 2 * 60_000L
private const val MAX_STATUS_HISTORY = 100
private const val UI_HIGH_DEMAND_CONSUMER_PREFIX = "ui:"
private const val PERSIST_DEBUG_TAG = "BlackboxPersistDebug"
private const val ENABLE_VERBOSE_LOCATION_EVENT_LOGS = false
private const val LOCATION_ENGINE_THREAD_NAME = "bbx-location-engine"

object LocationEngine {
    private data class CandidateFix(
        val provider: String,
        val sample: ProviderSample,
        val accuracyMeters: Float,
        val ageMillis: Long
    )

    private data class ProviderSample(
        val location: Location,
        val receivedAtMillis: Long
    )

    private val _state = MutableStateFlow(LocationEngineState())
    val state: StateFlow<LocationEngineState> = _state.asStateFlow()
    private val _locationEvents = MutableSharedFlow<LocationSampleEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val locationEvents: SharedFlow<LocationSampleEvent> = _locationEvents.asSharedFlow()

    private val engineThreadLock = Any()
    @Volatile
    private var engineHandler: Handler? = null
    @Volatile
    private var engineExecutor: Executor? = null

    private var appContext: Context? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var significantMotionSensor: Sensor? = null
    private var significantMotionListener: TriggerEventListener? = null
    private var significantMotionArmed = false
    private var significantMotionLastTriggeredAtMillis: Long? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var gnssStatusRegistered = false
    private var satelliteSummaryState = SatelliteSummaryState(statusMessage = "GNSS status unavailable.")

    private var engineEnabled = true
    private var allowLowPowerBackground = true
    private var forceActive = false
    private var engineMode = LocationEngineMode.Off
    private val highDemandConsumers = linkedSetOf<String>()

    private val latestFixByProvider = linkedMapOf<String, ProviderSample>()
    private val providerListeners = linkedMapOf<String, LocationListener>()
    private var enabledProviders: Set<String> = emptySet()

    private var motionBoostUntilElapsedRealtimeNanos: Long? = null
    private var bestPositionFix: PositionFix? = null
    private var bestMotionFix: MotionFix? = null
    private var motionStatus: String = "Unavailable: no eligible fix."
    private var pendingSwitchProvider: String? = null
    private var pendingSwitchSinceMillis: Long? = null

    private val statusHistory = ArrayDeque<LocationEngineMessage>(MAX_STATUS_HISTORY)
    private var statusHistorySnapshot: List<LocationEngineMessage> = emptyList()
    private var lastStatusMessage: String = "Engine initializing."
    private var lastErrorMessage: String? = null

    private var tickScheduled = false
    private val tickRunnable = Runnable {
        tickScheduled = false
        onTick()
    }

    fun initialize(context: Context) {
        postToEngine {
            if (appContext != null) {
                return@postToEngine
            }

            appContext = context.applicationContext
            locationManager = appContext?.getSystemService(LocationManager::class.java)
            sensorManager = appContext?.getSystemService(SensorManager::class.java)
            significantMotionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

            if (locationManager == null) {
                recordError("LocationEngine could not access LocationManager.")
            } else {
                recordStatus("LocationEngine initialized.", clearError = true)
            }

            if (significantMotionSensor == null) {
                recordStatus("Significant motion sensor not available on this device.")
            } else {
                recordStatus("Significant motion sensor detected: ${significantMotionSensor?.name}.")
            }

            refreshEnabledProviders()
            reevaluateAndApply("Initialization")
        }
    }

    fun setEngineEnabled(enabled: Boolean) {
        postToEngine {
            if (engineEnabled == enabled) return@postToEngine
            engineEnabled = enabled
            if (!enabled) {
                motionBoostUntilElapsedRealtimeNanos = null
                latestFixByProvider.clear()
            }
            recordStatus(if (enabled) "Engine enabled." else "Engine disabled.", clearError = enabled)
            reevaluateAndApply("Engine toggle")
        }
    }

    fun setAllowLowPowerBackground(enabled: Boolean) {
        postToEngine {
            if (allowLowPowerBackground == enabled) return@postToEngine
            allowLowPowerBackground = enabled
            recordStatus(
                if (enabled) {
                    "Low-power background mode enabled."
                } else {
                    "Low-power background mode disabled."
                }
            )
            reevaluateAndApply("Low-power preference changed")
        }
    }

    fun setForceActive(enabled: Boolean) {
        postToEngine {
            if (forceActive == enabled) return@postToEngine
            forceActive = enabled
            recordStatus(if (enabled) "Force Active enabled." else "Force Active disabled.")
            reevaluateAndApply("Force Active changed")
        }
    }

    fun registerHighDemandConsumer(consumerId: String) {
        postToEngine {
            if (consumerId.isBlank()) return@postToEngine
            val changed = highDemandConsumers.add(consumerId)
            if (changed) {
                recordStatus("High-demand consumer added: $consumerId")
                reevaluateAndApply("Demand increased")
            }
        }
    }

    fun unregisterHighDemandConsumer(consumerId: String) {
        postToEngine {
            if (consumerId.isBlank()) return@postToEngine
            val changed = highDemandConsumers.remove(consumerId)
            if (changed) {
                recordStatus("High-demand consumer removed: $consumerId")
                reevaluateAndApply("Demand reduced")
            }
        }
    }

    fun clearUiHighDemandConsumers() {
        postToEngine {
            val changed = highDemandConsumers.removeAll { it.startsWith(UI_HIGH_DEMAND_CONSUMER_PREFIX) }
            if (changed) {
                recordStatus("UI high-demand consumers cleared.")
                reevaluateAndApply("UI demand cleared")
            }
        }
    }

    private fun onTick() {
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val boostUntil = motionBoostUntilElapsedRealtimeNanos
        if (boostUntil != null && nowElapsedNanos >= boostUntil) {
            motionBoostUntilElapsedRealtimeNanos = null
            recordStatus("Significant motion boost elapsed.")
            reevaluateAndApply("Motion boost expired")
            return
        }

        recomputeAndPublishState()
    }

    private fun reevaluateAndApply(reason: String) {
        val newMode = computeTargetMode()
        val modeChanged = newMode != engineMode
        if (modeChanged) {
            engineMode = newMode
            recordStatus("Engine mode -> ${newMode.name} ($reason).", clearError = true)
        }

        refreshEnabledProviders()

        when (engineMode) {
            LocationEngineMode.Off -> {
                stopAllLocationSubscriptions()
                stopGnssStatusUpdates("GNSS stopped: engine is off.")
                disarmSignificantMotion()
                bestPositionFix = null
                bestMotionFix = null
                motionStatus = "Unavailable: engine is off."
                clearPendingSwitch()
            }

            LocationEngineMode.LowPower -> {
                syncLocationSubscriptions()
                syncGnssStatusUpdates()
                armSignificantMotionIfAvailable()
            }

            LocationEngineMode.Active -> {
                syncLocationSubscriptions()
                syncGnssStatusUpdates()
                disarmSignificantMotion()
            }
        }

        recomputeAndPublishState()
    }

    private fun computeTargetMode(): LocationEngineMode {
        if (!engineEnabled) return LocationEngineMode.Off
        if (forceActive) return LocationEngineMode.Active
        if (highDemandConsumers.isNotEmpty()) return LocationEngineMode.Active

        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val boostUntil = motionBoostUntilElapsedRealtimeNanos
        if (boostUntil != null && nowElapsedNanos < boostUntil) {
            return LocationEngineMode.Active
        }

        // Default background mode is always LowPower when engine is enabled.
        return LocationEngineMode.LowPower
    }

    private fun syncLocationSubscriptions() {
        stopAllLocationSubscriptions()

        val context = appContext
        val manager = locationManager
        if (context == null || manager == null) {
            recordError("LocationEngine is missing Context/LocationManager.")
            return
        }

        if (!context.hasAnyLocationPermission()) {
            recordError("Location permission is missing. Grant coarse or fine location.")
            return
        }

        val desiredProviders = when (engineMode) {
            LocationEngineMode.Off -> emptyList()
            LocationEngineMode.LowPower -> listOf(
                LocationManager.PASSIVE_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )

            LocationEngineMode.Active -> listOf(
                LocationManager.PASSIVE_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER
            )
        }

        desiredProviders.forEach { provider ->
            if (provider != LocationManager.PASSIVE_PROVIDER && !enabledProviders.contains(provider)) {
                recordStatus("Provider unavailable in current system settings: $provider")
                return@forEach
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    ingestLocation(provider = provider, location = location)
                }

                override fun onProviderEnabled(provider: String) {
                    recordStatus("Provider enabled: $provider")
                    reevaluateAndApply("Provider enabled")
                }

                override fun onProviderDisabled(provider: String) {
                    recordStatus("Provider disabled: $provider")
                    reevaluateAndApply("Provider disabled")
                }
            }

            val requested = requestProviderUpdates(
                manager = manager,
                provider = provider,
                listener = listener
            )

            if (requested) {
                providerListeners[provider] = listener
            } else {
                recordError("Failed to request updates for provider '$provider'.")
            }
        }

        if (providerListeners.isEmpty() && engineMode != LocationEngineMode.Off) {
            recordError("No providers are currently subscribed.")
        } else if (providerListeners.isNotEmpty()) {
            recordStatus("Subscribed providers: ${providerListeners.keys.joinToString(", ")}", clearError = true)
            publishBestLastKnownLocations()
        }
    }

    private fun requestProviderUpdates(
        manager: LocationManager,
        provider: String,
        listener: LocationListener
    ): Boolean {
        val handler = ensureEngineHandler()
        val executor = ensureEngineExecutor()
        val (intervalMillis, quality) = when {
            provider == LocationManager.PASSIVE_PROVIDER -> 0L to LocationRequest.QUALITY_LOW_POWER
            engineMode == LocationEngineMode.LowPower && provider == LocationManager.NETWORK_PROVIDER -> {
                LOW_POWER_NETWORK_INTERVAL_MS to LocationRequest.QUALITY_LOW_POWER
            }

            provider == LocationManager.GPS_PROVIDER -> ACTIVE_UPDATE_INTERVAL_MS to LocationRequest.QUALITY_HIGH_ACCURACY
            else -> ACTIVE_UPDATE_INTERVAL_MS to LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
        }

        val requestResult = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = LocationRequest.Builder(intervalMillis)
                    .setMinUpdateDistanceMeters(0f)
                    .setMinUpdateIntervalMillis(intervalMillis)
                    .setQuality(quality)
                    .build()
                manager.requestLocationUpdates(
                    provider,
                    request,
                    executor,
                    listener
                )
            } else {
                @Suppress("DEPRECATION")
                manager.requestLocationUpdates(
                    provider,
                    intervalMillis,
                    0f,
                    listener,
                    handler.looper
                )
            }
        }

        if (requestResult.isSuccess) {
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fallback = runCatching {
                @Suppress("DEPRECATION")
                manager.requestLocationUpdates(
                    provider,
                    intervalMillis,
                    0f,
                    listener,
                    handler.looper
                )
            }
            return fallback.isSuccess
        }

        return false
    }

    private fun publishBestLastKnownLocations() {
        val manager = locationManager ?: return
        val nowMillis = System.currentTimeMillis()
        providerListeners.keys.forEach { provider ->
            val lastKnown = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: return@forEach
            latestFixByProvider[provider] = ProviderSample(
                location = Location(lastKnown),
                receivedAtMillis = nowMillis
            )
        }
    }

    private fun stopAllLocationSubscriptions() {
        val manager = locationManager ?: return
        providerListeners.values.forEach { listener ->
            runCatching { manager.removeUpdates(listener) }
        }
        providerListeners.clear()
    }

    private fun ingestLocation(provider: String, location: Location) {
        val copiedLocation = Location(location)
        val receivedAtMillis = System.currentTimeMillis()
        latestFixByProvider[provider] = ProviderSample(
            location = copiedLocation,
            receivedAtMillis = receivedAtMillis
        )
        val event = buildLocationSampleEvent(
            provider = provider,
            location = copiedLocation,
            receivedAtMillis = receivedAtMillis
        )
        val emitted = _locationEvents.tryEmit(event)
        if (ENABLE_VERBOSE_LOCATION_EVENT_LOGS) {
            Log.d(
                PERSIST_DEBUG_TAG,
                "Location update pushed emitted=$emitted provider=${event.provider} " +
                    "lat=${event.lat} lon=${event.lon} acc=${event.accuracyM} " +
                    "receivedAtMs=${event.receivedAtMs} mode=${event.engineMode}"
            )
        }
        recomputeAndPublishState()
    }

    private fun recomputeAndPublishState() {
        recomputeBestFixes()
        val previous = _state.value
        val candidate = LocationEngineState(
            engineEnabled = engineEnabled,
            allowLowPowerBackground = allowLowPowerBackground,
            forceActive = forceActive,
            engineMode = engineMode,
            bestPositionFix = bestPositionFix,
            bestMotionFix = bestMotionFix,
            motionStatus = motionStatus,
            enabledProviders = enabledProviders,
            subscribedProviders = providerListeners.keys.toSet(),
            highDemandConsumers = highDemandConsumers.toSet(),
            significantMotion = SignificantMotionSummary(
                available = significantMotionSensor != null,
                sensorName = significantMotionSensor?.name,
                armed = significantMotionArmed,
                lastTriggeredAtMillis = significantMotionLastTriggeredAtMillis
            ),
            satelliteSummary = satelliteSummaryState,
            lastStatusMessage = lastStatusMessage,
            lastErrorMessage = lastErrorMessage,
            statusHistory = statusHistorySnapshot,
            lastUpdatedAtMillis = previous.lastUpdatedAtMillis
        )
        if (candidate != previous) {
            _state.value = candidate.copy(lastUpdatedAtMillis = System.currentTimeMillis())
        }
        scheduleTickIfNeeded()
    }

    private fun recomputeBestFixes() {
        if (!engineEnabled || engineMode == LocationEngineMode.Off) {
            bestPositionFix = null
            bestMotionFix = null
            motionStatus = "Unavailable: engine is off."
            clearPendingSwitch()
            return
        }

        val nowWallMillis = System.currentTimeMillis()
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()

        val maxFixAgeMillis = when (engineMode) {
            LocationEngineMode.Active -> FIX_MAX_AGE_ACTIVE_MS
            LocationEngineMode.LowPower -> FIX_MAX_AGE_LOW_POWER_MS
            LocationEngineMode.Off -> FIX_MAX_AGE_ACTIVE_MS
        }

        var candidate = chooseBestCandidate(
            nowWallMillis = nowWallMillis,
            nowElapsedNanos = nowElapsedNanos,
            maxFixAgeMillis = maxFixAgeMillis
        )
        if (candidate == null) {
            bestPositionFix = null
            bestMotionFix = null
            motionStatus = "Unavailable: no valid fix (accuracy <= 0 or age above mode threshold)."
            clearPendingSwitch()
            return
        }

        if (engineMode == LocationEngineMode.Active) {
            candidate = applyProviderSwitchStickiness(
                candidate = candidate,
                nowWallMillis = nowWallMillis,
                nowElapsedNanos = nowElapsedNanos,
                maxFixAgeMillis = maxFixAgeMillis
            )
        } else {
            clearPendingSwitch()
        }

        val chosenLocation = Location(candidate.sample.location)
        val resolvedProvider = chosenLocation.provider ?: candidate.provider
        val positionFix = PositionFix(
            location = chosenLocation,
            provider = resolvedProvider,
            accuracyMeters = candidate.accuracyMeters,
            fixTimeMillis = chosenLocation.time,
            receivedAtMillis = candidate.sample.receivedAtMillis,
            ageMillis = candidate.ageMillis
        )
        bestPositionFix = positionFix

        if (positionFix.accuracyMeters > MOTION_MAX_ACCURACY_METERS) {
            bestMotionFix = null
            motionStatus = "Unavailable: motion accuracy gate failed (${formatOneDecimal(positionFix.accuracyMeters)}m > ${formatOneDecimal(MOTION_MAX_ACCURACY_METERS)}m)."
            return
        }

        if (positionFix.ageMillis > MOTION_MAX_AGE_MS) {
            bestMotionFix = null
            motionStatus = "Unavailable: motion fix is stale (${positionFix.ageMillis}ms > ${MOTION_MAX_AGE_MS}ms)."
            return
        }

        if (!chosenLocation.hasSpeed() || !chosenLocation.hasBearing()) {
            bestMotionFix = null
            motionStatus = "Unavailable: speed/bearing are not present in the current fix."
            return
        }

        bestMotionFix = MotionFix(
            speedMetersPerSecond = chosenLocation.speed,
            bearingDegrees = chosenLocation.bearing,
            speedAccuracyMetersPerSecond = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                chosenLocation.hasSpeedAccuracy()
            ) {
                chosenLocation.speedAccuracyMetersPerSecond
            } else {
                null
            },
            bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                chosenLocation.hasBearingAccuracy()
            ) {
                chosenLocation.bearingAccuracyDegrees
            } else {
                null
            },
            provider = resolvedProvider,
            fixTimeMillis = chosenLocation.time,
            ageMillis = candidate.ageMillis
        )
        motionStatus = "Available"
    }

    private fun chooseBestCandidate(
        nowWallMillis: Long,
        nowElapsedNanos: Long,
        maxFixAgeMillis: Long
    ): CandidateFix? {
        var selected: CandidateFix? = null

        latestFixByProvider.forEach { (provider, sample) ->
            val candidate = buildCandidateFix(
                provider = provider,
                sample = sample,
                nowWallMillis = nowWallMillis,
                nowElapsedNanos = nowElapsedNanos,
                maxFixAgeMillis = maxFixAgeMillis
            ) ?: return@forEach

            if (selected == null) {
                selected = candidate
                return@forEach
            }

            val current = selected!!
            val accuracyDelta = abs(candidate.accuracyMeters - current.accuracyMeters)
            val isMuchMoreAccurate =
                candidate.accuracyMeters + ACCURACY_SIMILARITY_TOLERANCE_METERS < current.accuracyMeters
            val isSimilarAccuracy = accuracyDelta <= ACCURACY_SIMILARITY_TOLERANCE_METERS

            when {
                isMuchMoreAccurate -> selected = candidate
                isSimilarAccuracy && isNewer(candidate.sample.location, current.sample.location) -> {
                    selected = candidate
                }
            }
        }

        return selected
    }

    private fun buildCandidateFix(
        provider: String,
        sample: ProviderSample,
        nowWallMillis: Long,
        nowElapsedNanos: Long,
        maxFixAgeMillis: Long
    ): CandidateFix? {
        val location = sample.location
        if (!location.hasAccuracy()) return null
        if (location.accuracy <= 0f) return null

        val ageMillis = computeAgeMillis(
            location = location,
            nowWallMillis = nowWallMillis,
            nowElapsedNanos = nowElapsedNanos
        )
        if (ageMillis > maxFixAgeMillis) return null

        return CandidateFix(
            provider = provider,
            sample = sample,
            accuracyMeters = location.accuracy,
            ageMillis = ageMillis
        )
    }

    private fun applyProviderSwitchStickiness(
        candidate: CandidateFix,
        nowWallMillis: Long,
        nowElapsedNanos: Long,
        maxFixAgeMillis: Long
    ): CandidateFix {
        val previousProvider = bestPositionFix?.provider
        if (previousProvider == null || previousProvider == candidate.provider) {
            clearPendingSwitch()
            return candidate
        }

        val previousSample = latestFixByProvider[previousProvider]
            ?.let {
                buildCandidateFix(
                    provider = previousProvider,
                    sample = it,
                    nowWallMillis = nowWallMillis,
                    nowElapsedNanos = nowElapsedNanos,
                    maxFixAgeMillis = maxFixAgeMillis
                )
            }

        if (previousSample == null) {
            clearPendingSwitch()
            return candidate
        }

        val shouldSwitchImmediately =
            candidate.accuracyMeters + PROVIDER_SWITCH_IMPROVEMENT_METERS < previousSample.accuracyMeters
        if (shouldSwitchImmediately) {
            clearPendingSwitch()
            return candidate
        }

        val now = nowWallMillis
        if (pendingSwitchProvider == candidate.provider) {
            val pendingSince = pendingSwitchSinceMillis ?: now
            return if (now - pendingSince >= PROVIDER_SWITCH_STABILITY_MS) {
                clearPendingSwitch()
                candidate
            } else {
                previousSample
            }
        }

        pendingSwitchProvider = candidate.provider
        pendingSwitchSinceMillis = now
        return previousSample
    }

    private fun clearPendingSwitch() {
        pendingSwitchProvider = null
        pendingSwitchSinceMillis = null
    }

    private fun armSignificantMotionIfAvailable() {
        val manager = sensorManager
        val sensor = significantMotionSensor
        if (manager == null || sensor == null) {
            significantMotionArmed = false
            return
        }
        if (significantMotionArmed) return

        val listener = significantMotionListener ?: object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                postToEngine {
                    significantMotionArmed = false
                    significantMotionLastTriggeredAtMillis = System.currentTimeMillis()
                    motionBoostUntilElapsedRealtimeNanos =
                        SystemClock.elapsedRealtimeNanos() + SIGNIFICANT_MOTION_ACTIVE_WINDOW_MS * 1_000_000L
                    recordStatus("Significant motion triggered. Switching to Active mode.")
                    reevaluateAndApply("Significant motion trigger")
                }
            }
        }.also { significantMotionListener = it }

        val armed = runCatching {
            manager.requestTriggerSensor(listener, sensor)
        }.getOrDefault(false)

        significantMotionArmed = armed
        if (armed) {
            recordStatus("Significant motion armed.", clearError = true)
        } else {
            recordError("Failed to arm significant motion trigger sensor.")
        }
    }

    private fun disarmSignificantMotion() {
        val manager = sensorManager ?: return
        val sensor = significantMotionSensor ?: return
        val listener = significantMotionListener ?: return

        runCatching { manager.cancelTriggerSensor(listener, sensor) }
        significantMotionArmed = false
    }

    private fun syncGnssStatusUpdates() {
        val context = appContext
        val manager = locationManager
        if (context == null || manager == null) {
            satelliteSummaryState = SatelliteSummaryState(
                statusMessage = "GNSS unavailable: LocationManager not ready."
            )
            return
        }

        if (!context.hasAnyLocationPermission()) {
            stopGnssStatusUpdates("GNSS unavailable: location permission denied.")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            stopGnssStatusUpdates("GNSS status callbacks require Android API 24+.")
            return
        }

        if (gnssStatusRegistered) return

        val handler = ensureEngineHandler()
        val executor = ensureEngineExecutor()
        val callback = createGnssStatusCallback()
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.registerGnssStatusCallback(executor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerGnssStatusCallback(
                    callback,
                    handler
                )
            }
        }.getOrDefault(false)

        if (!registered) {
            satelliteSummaryState = SatelliteSummaryState(
                statusMessage = "GNSS callback registration failed."
            )
            recordError("Failed to register GNSS status callback.")
            return
        }

        gnssStatusCallback = callback
        gnssStatusRegistered = true
        satelliteSummaryState = satelliteSummaryState.copy(
            statusMessage = "Listening for GNSS status."
        )
        recordStatus("GNSS status callback registered.", clearError = true)
    }

    private fun stopGnssStatusUpdates(statusMessage: String) {
        val manager = locationManager
        val callback = gnssStatusCallback
        if (manager != null && callback != null && gnssStatusRegistered) {
            runCatching { manager.unregisterGnssStatusCallback(callback) }
        }
        gnssStatusRegistered = false
        gnssStatusCallback = null
        satelliteSummaryState = SatelliteSummaryState(statusMessage = statusMessage)
    }

    private fun createGnssStatusCallback(): GnssStatus.Callback {
        return object : GnssStatus.Callback() {
            override fun onStarted() {
                satelliteSummaryState = satelliteSummaryState.copy(
                    statusMessage = "GNSS started.",
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
                recomputeAndPublishState()
            }

            override fun onStopped() {
                satelliteSummaryState = satelliteSummaryState.copy(
                    statusMessage = "GNSS stopped.",
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
                recomputeAndPublishState()
            }

            override fun onFirstFix(ttffMillis: Int) {
                satelliteSummaryState = satelliteSummaryState.copy(
                    statusMessage = "GNSS first fix in ${ttffMillis}ms.",
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
                recomputeAndPublishState()
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val visibleCount = status.satelliteCount
                var usedInFixCount = 0
                var usedCn0Sum = 0f
                val constellationCounts = linkedMapOf<String, Int>()

                for (index in 0 until status.satelliteCount) {
                    val constellationName = constellationName(status.getConstellationType(index))
                    constellationCounts[constellationName] =
                        (constellationCounts[constellationName] ?: 0) + 1

                    if (status.usedInFix(index)) {
                        usedInFixCount += 1
                        usedCn0Sum += status.getCn0DbHz(index)
                    }
                }

                satelliteSummaryState = SatelliteSummaryState(
                    visibleCount = visibleCount,
                    usedInFixCount = usedInFixCount,
                    avgCn0Used = if (usedInFixCount > 0) {
                        usedCn0Sum / usedInFixCount.toFloat()
                    } else {
                        null
                    },
                    constellationCounts = constellationCounts,
                    lastUpdatedAtMillis = System.currentTimeMillis(),
                    statusMessage = "Live GNSS satellite status."
                )
                recomputeAndPublishState()
            }
        }
    }

    private fun constellationName(constellationType: Int): String {
        return when (constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
            GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "UNKNOWN"
        }
    }

    private fun refreshEnabledProviders() {
        val manager = locationManager ?: return
        enabledProviders = runCatching {
            manager.getProviders(true).toSet()
        }.getOrElse {
            recordError("Failed to query enabled providers: ${it.message ?: "unknown error"}")
            emptySet()
        }
    }

    private fun scheduleTickIfNeeded() {
        val nowWallMillis = System.currentTimeMillis()
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val delaysMillis = mutableListOf<Long>()

        motionBoostUntilElapsedRealtimeNanos?.let { boostUntil ->
            val remainingNanos = boostUntil - nowElapsedNanos
            val remainingMillis = if (remainingNanos <= 0L) 1L else (remainingNanos + 999_999L) / 1_000_000L
            delaysMillis += remainingMillis
        }

        if (engineMode != LocationEngineMode.Off) {
            val maxFixAgeMillis = when (engineMode) {
                LocationEngineMode.Active -> FIX_MAX_AGE_ACTIVE_MS
                LocationEngineMode.LowPower -> FIX_MAX_AGE_LOW_POWER_MS
                LocationEngineMode.Off -> FIX_MAX_AGE_ACTIVE_MS
            }

            bestPositionFix?.let { positionFix ->
                val remaining = maxFixAgeMillis - positionFix.ageMillis
                delaysMillis += if (remaining <= 0L) 1L else remaining + 1L
            }

            bestMotionFix?.let { motionFix ->
                val remaining = MOTION_MAX_AGE_MS - motionFix.ageMillis
                delaysMillis += if (remaining <= 0L) 1L else remaining + 1L
            }

            if (engineMode == LocationEngineMode.Active &&
                pendingSwitchProvider != null &&
                pendingSwitchSinceMillis != null
            ) {
                val pendingSince = pendingSwitchSinceMillis!!
                val remaining = PROVIDER_SWITCH_STABILITY_MS - (nowWallMillis - pendingSince)
                delaysMillis += if (remaining <= 0L) 1L else remaining + 1L
            }
        }

        val nextDelayMillis = delaysMillis.minOrNull()
        val handler = ensureEngineHandler()
        if (nextDelayMillis == null) {
            if (tickScheduled) {
                handler.removeCallbacks(tickRunnable)
                tickScheduled = false
            }
            return
        }

        handler.removeCallbacks(tickRunnable)
        tickScheduled = true
        handler.postDelayed(tickRunnable, nextDelayMillis)
    }

    private fun recordStatus(message: String, clearError: Boolean = false) {
        if (message.isBlank()) return
        val previous = statusHistory.lastOrNull()
        if (previous?.message == message && !previous.isError) {
            if (clearError) {
                lastErrorMessage = null
            }
            lastStatusMessage = message
            return
        }

        appendHistoryEntry(
            LocationEngineMessage(
                timestampMillis = System.currentTimeMillis(),
                message = message,
                isError = false
            )
        )
        lastStatusMessage = message
        if (clearError) {
            lastErrorMessage = null
        }
    }

    private fun recordError(message: String) {
        if (message.isBlank()) return
        val previous = statusHistory.lastOrNull()
        if (previous?.message == message && previous.isError) {
            lastStatusMessage = message
            lastErrorMessage = message
            return
        }

        appendHistoryEntry(
            LocationEngineMessage(
                timestampMillis = System.currentTimeMillis(),
                message = message,
                isError = true
            )
        )
        lastStatusMessage = message
        lastErrorMessage = message
    }

    private fun appendHistoryEntry(entry: LocationEngineMessage) {
        statusHistory.addLast(entry)
        while (statusHistory.size > MAX_STATUS_HISTORY) {
            statusHistory.removeFirst()
        }
        statusHistorySnapshot = statusHistory.toList()
    }

    private fun postToEngine(block: () -> Unit) {
        val handler = ensureEngineHandler()
        if (Looper.myLooper() == handler.looper) {
            block()
        } else {
            handler.post(block)
        }
    }

    private fun ensureEngineHandler(): Handler {
        val current = engineHandler
        if (current != null && current.looper.thread.isAlive) {
            return current
        }
        synchronized(engineThreadLock) {
            val existing = engineHandler
            if (existing != null && existing.looper.thread.isAlive) {
                return existing
            }
            val thread = HandlerThread(LOCATION_ENGINE_THREAD_NAME)
            thread.start()
            return Handler(thread.looper).also { created ->
                engineHandler = created
                engineExecutor = Executor { runnable ->
                    if (!created.post(runnable)) {
                        runnable.run()
                    }
                }
            }
        }
    }

    private fun ensureEngineExecutor(): Executor {
        ensureEngineHandler()
        return engineExecutor ?: Executor { runnable -> runnable.run() }
    }

    private fun computeAgeMillis(
        location: Location,
        nowWallMillis: Long,
        nowElapsedNanos: Long
    ): Long {
        val elapsedRealtimeNanos = location.elapsedRealtimeNanos
        if (elapsedRealtimeNanos > 0L && nowElapsedNanos >= elapsedRealtimeNanos) {
            return (nowElapsedNanos - elapsedRealtimeNanos) / 1_000_000L
        }

        val wallAge = nowWallMillis - location.time
        return if (wallAge < 0L) 0L else wallAge
    }

    private fun isNewer(left: Location, right: Location): Boolean {
        val leftElapsed = left.elapsedRealtimeNanos
        val rightElapsed = right.elapsedRealtimeNanos
        return when {
            leftElapsed > 0L && rightElapsed > 0L && leftElapsed != rightElapsed -> leftElapsed > rightElapsed
            else -> left.time > right.time
        }
    }

    private fun formatOneDecimal(value: Float): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private fun buildLocationSampleEvent(
        provider: String,
        location: Location,
        receivedAtMillis: Long
    ): LocationSampleEvent {
        val fixTimeMillis = location.time.takeIf { it > 0L } ?: receivedAtMillis
        return LocationSampleEvent(
            receivedAtMs = receivedAtMillis,
            fixTimeMs = fixTimeMillis,
            provider = location.provider ?: provider,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond
            } else {
                null
            },
            bearingAccuracyDeg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees
            } else {
                null
            },
            engineMode = engineMode
        )
    }
}
