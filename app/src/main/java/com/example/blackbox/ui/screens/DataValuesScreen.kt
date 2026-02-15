package com.example.blackbox.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val LOCATION_API_SOURCE = "android.location.LocationManager + android.location.LocationListener"
private const val BATTERY_API_SOURCE = "Intent.ACTION_BATTERY_CHANGED"
private const val TIME_API_SOURCE = "System clock + kotlinx.coroutines.delay"
private const val TIME_API_SOURCE_UTC = "java.time.DateTimeFormatter.ISO_INSTANT"
private const val GNSS_STATUS_API_SOURCE = "LocationManager.registerGnssStatusCallback + GnssStatus.Callback"
private const val SENSOR_API_SOURCE = "SensorManager.getDefaultSensor(TYPE_SIGNIFICANT_MOTION)"

@Composable
fun DataValuesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var locationPermissionGranted by rememberSaveable { mutableStateOf(context.hasLocationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        locationPermissionGranted = context.hasLocationPermission()
    }

    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val timestamp by rememberLiveTimestamp()
    val battery by rememberBatteryPercentage()
    val chargingState by rememberChargingState()
    val batterySaver by rememberBatterySaverState()
    val locationState by rememberLocationState(locationPermissionGranted)
    val gnss by rememberGnssSatelliteSummary(locationPermissionGranted)
    val significantMotionSensor by rememberSignificantMotionSensorState()

    val timeReadings = listOf(
        DataReading(
            label = "Timestamp",
            value = timestamp.value,
            apiSource = timestamp.apiSource,
            lastRetrievedAtMillis = timestamp.lastUpdatedAtMillis,
            availabilitySummary = "Available",
            detailReason = "Value updates every second while this page is open.",
            isError = false
        ),
        DataReading(
            label = "Absolute Timestamp (UTC)",
            value = timestamp.utcTimestamp ?: "Unavailable",
            apiSource = TIME_API_SOURCE_UTC,
            lastRetrievedAtMillis = timestamp.lastUpdatedAtMillis,
            availabilitySummary = if (timestamp.utcTimestamp == null) "Unavailable" else "Available",
            detailReason = if (timestamp.utcTimestamp == null) {
                "UTC timestamp formatting failed."
            } else {
                "Absolute timestamp formatted in UTC."
            },
            isError = timestamp.utcTimestamp == null
        ),
        DataReading(
            label = "Unix Timestamp",
            value = timestamp.unixEpochSeconds?.toString() ?: "Unavailable",
            apiSource = "System.currentTimeMillis()/1000",
            lastRetrievedAtMillis = timestamp.lastUpdatedAtMillis,
            availabilitySummary = if (timestamp.unixEpochSeconds == null) "Unavailable" else "Available",
            detailReason = if (timestamp.unixEpochSeconds == null) {
                "System time value was unavailable."
            } else {
                "Unix epoch seconds derived from system wall clock."
            },
            isError = timestamp.unixEpochSeconds == null
        )
    )

    val powerReadings = listOf(
        DataReading(
            label = "Battery Level",
            value = battery.value?.let { "$it%" } ?: "Unavailable",
            apiSource = battery.apiSource,
            lastRetrievedAtMillis = battery.lastUpdatedAtMillis,
            availabilitySummary = if (battery.value == null) "Unavailable" else "Available",
            detailReason = if (battery.value == null) {
                battery.lastError ?: "Battery broadcast extras are missing or malformed on this device state."
            } else {
                "Read from ACTION_BATTERY_CHANGED sticky broadcast."
            },
            isError = battery.value == null
        ),
        DataReading(
            label = "Is Charging",
            value = chargingState.value?.let { if (it) "Yes" else "No" } ?: "Unavailable",
            apiSource = chargingState.apiSource,
            lastRetrievedAtMillis = chargingState.lastUpdatedAtMillis,
            availabilitySummary = if (chargingState.value == null) "Unavailable" else "Available",
            detailReason = if (chargingState.value == null) {
                chargingState.lastError ?: "Charging status unavailable from battery broadcast."
            } else {
                "Derived from BatteryManager status extras in ACTION_BATTERY_CHANGED."
            },
            isError = chargingState.value == null
        ),
        DataReading(
            label = "Battery Saver",
            value = batterySaver.value?.let { if (it) "On" else "Off" } ?: "Unavailable",
            apiSource = batterySaver.apiSource,
            lastRetrievedAtMillis = batterySaver.lastUpdatedAtMillis,
            availabilitySummary = if (batterySaver.value == null) "Unavailable" else "Available",
            detailReason = if (batterySaver.value == null) {
                batterySaver.lastError ?: "PowerManager value unavailable."
            } else {
                "Read from PowerManager and updated on power-save mode change broadcasts."
            },
            isError = batterySaver.value == null
        )
    )

    val locationReadings = buildLocationReadings(
        locationState = locationState,
        permissionGranted = locationPermissionGranted,
        gnssSummary = gnss
    )
    val sensorReadings = buildList {
        add(
            DataReading(
                label = "Significant Motion Sensor",
                value = if (significantMotionSensor.available == true) "Available" else "Unavailable",
                apiSource = SENSOR_API_SOURCE,
                lastRetrievedAtMillis = significantMotionSensor.lastUpdatedAtMillis,
                availabilitySummary = if (significantMotionSensor.available == true) "Available" else "Unavailable",
                detailReason = if (significantMotionSensor.available == true) {
                    significantMotionSensor.sensorName?.let { "Device exposes trigger sensor: $it." }
                        ?: "Device exposes TYPE_SIGNIFICANT_MOTION trigger sensor."
                } else {
                    significantMotionSensor.errorMessage
                        ?: "TYPE_SIGNIFICANT_MOTION is not exposed by this device."
                },
                isError = significantMotionSensor.available != true
            )
        )

        if (significantMotionSensor.available == true) {
            val stateValue = significantMotionSensor.sensorState ?: "Unknown"
            add(
                DataReading(
                    label = "Significant Motion State",
                    value = stateValue,
                    apiSource = "TriggerEventListener + requestTriggerSensor/cancelTriggerSensor",
                    lastRetrievedAtMillis = significantMotionSensor.lastUpdatedAtMillis,
                    availabilitySummary = "Available",
                    detailReason = significantMotionSensor.errorMessage ?: when (stateValue) {
                        "Armed (waiting for trigger)" -> "Sensor is armed and waiting for a significant motion event."
                        else -> "Sensor reported at least one significant motion trigger and was re-armed."
                    },
                    isError = stateValue.contains("failed", ignoreCase = true)
                )
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionHeader(title = "Time")
        }
        items(items = timeReadings, key = { "time_${it.label}" }) { reading ->
            DataReadingRow(reading = reading)
        }

        item {
            SectionHeader(title = "Power")
        }
        items(items = powerReadings, key = { "power_${it.label}" }) { reading ->
            DataReadingRow(reading = reading)
        }

        item {
            SectionHeader(title = "Sensors")
        }
        items(items = sensorReadings, key = { "sensor_${it.label}" }) { reading ->
            DataReadingRow(reading = reading)
        }

        item {
            SectionHeader(title = "Location")
        }

        if (!locationPermissionGranted) {
            item {
                Text(
                    text = "Location permission is required to read location values.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                ) {
                    Text(text = "Grant Location Permission")
                }
            }
        }

        items(items = locationReadings, key = { "location_${it.label}" }) { reading ->
            DataReadingRow(reading = reading)
        }
    }
}

@Composable
private fun DataReadingRow(reading: DataReading) {
    var expanded by rememberSaveable(reading.label) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = reading.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = reading.value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = if (reading.isError) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(top = 4.dp)
        )
        if (expanded) {
            Text(
                text = "Status: ${reading.availabilitySummary}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (reading.isError) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
            Text(
                text = "Reason: ${reading.detailReason}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (reading.isError) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun rememberLiveTimestamp(): State<TimedValue<String>> {
    val localFormatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.US)
    }
    val initialNowMs = System.currentTimeMillis()
    val initialInstant = Instant.ofEpochMilli(initialNowMs)
    val initialZoneId = ZoneId.systemDefault()
    val initialLocalTimestamp = initialInstant.atZone(initialZoneId).format(localFormatter)
    val initialUtcTimestamp = DateTimeFormatter.ISO_INSTANT.format(initialInstant)

    return produceState(
        initialValue = TimedValue(
            value = initialLocalTimestamp,
            lastUpdatedAtMillis = initialNowMs,
            apiSource = TIME_API_SOURCE,
            lastError = null,
            unixEpochSeconds = initialNowMs / 1_000L,
            timezoneId = initialZoneId.id,
            utcTimestamp = initialUtcTimestamp
        )
    ) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val instant = Instant.ofEpochMilli(nowMs)
            val zoneId = ZoneId.systemDefault()
            value = TimedValue(
                value = instant.atZone(zoneId).format(localFormatter),
                lastUpdatedAtMillis = nowMs,
                apiSource = TIME_API_SOURCE,
                lastError = null,
                unixEpochSeconds = nowMs / 1_000L,
                timezoneId = zoneId.id,
                utcTimestamp = DateTimeFormatter.ISO_INSTANT.format(instant)
            )
            delay(1_000L)
        }
    }
}

@Composable
private fun rememberGnssSatelliteSummary(permissionGranted: Boolean): State<GnssSatelliteSummaryState> {
    val context = LocalContext.current
    val gnssState = remember {
        mutableStateOf(
            GnssSatelliteSummaryState(
                statusMessage = "Waiting for GNSS status.",
                errorMessage = "No GNSS status callback registered yet."
            )
        )
    }

    DisposableEffect(context, permissionGranted) {
        if (!permissionGranted) {
            gnssState.value = GnssSatelliteSummaryState(
                statusMessage = "Permission denied.",
                errorMessage = "Location permission is required for GNSS status."
            )
            onDispose { }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            gnssState.value = GnssSatelliteSummaryState(
                statusMessage = "GNSS status unavailable.",
                errorMessage = "Requires Android API 24+."
            )
            onDispose { }
        } else {
            val locationManager = context.getSystemService(LocationManager::class.java)
            if (locationManager == null) {
                gnssState.value = GnssSatelliteSummaryState(
                    statusMessage = "GNSS service unavailable.",
                    errorMessage = "LocationManager service was null."
                )
                onDispose { }
            } else {
                val callback = object : GnssStatus.Callback() {
                    override fun onStarted() {
                        gnssState.value = gnssState.value.copy(
                            statusMessage = "GNSS started.",
                            errorMessage = null,
                            lastUpdatedAtMillis = System.currentTimeMillis()
                        )
                    }

                    override fun onStopped() {
                        gnssState.value = gnssState.value.copy(
                            statusMessage = "GNSS stopped.",
                            lastUpdatedAtMillis = System.currentTimeMillis()
                        )
                    }

                    override fun onFirstFix(ttffMillis: Int) {
                        gnssState.value = gnssState.value.copy(
                            statusMessage = "First fix in ${ttffMillis}ms.",
                            errorMessage = null,
                            lastUpdatedAtMillis = System.currentTimeMillis()
                        )
                    }

                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        val visibleCount = status.satelliteCount
                        var usedInFixCount = 0
                        var usedCn0Sum = 0f

                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) {
                                usedInFixCount += 1
                                usedCn0Sum += status.getCn0DbHz(i)
                            }
                        }

                        val avgCn0Used = if (usedInFixCount > 0) {
                            usedCn0Sum / usedInFixCount.toFloat()
                        } else {
                            null
                        }

                        gnssState.value = GnssSatelliteSummaryState(
                            visibleCount = visibleCount,
                            usedInFixCount = usedInFixCount,
                            avgCn0Used = avgCn0Used,
                            lastUpdatedAtMillis = System.currentTimeMillis(),
                            statusMessage = "Live GNSS satellite status.",
                            errorMessage = null
                        )
                    }
                }

                val registered = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        locationManager.registerGnssStatusCallback(
                            ContextCompat.getMainExecutor(context),
                            callback
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        locationManager.registerGnssStatusCallback(
                            callback,
                            Handler(Looper.getMainLooper())
                        )
                    }
                }.getOrElse {
                    gnssState.value = gnssState.value.copy(
                        statusMessage = "GNSS registration failed.",
                        errorMessage = it.message ?: "Unable to register GNSS callback.",
                        lastUpdatedAtMillis = System.currentTimeMillis()
                    )
                    false
                }

                if (!registered) {
                    gnssState.value = gnssState.value.copy(
                        statusMessage = "GNSS registration failed.",
                        errorMessage = gnssState.value.errorMessage
                            ?: "registerGnssStatusCallback returned false.",
                        lastUpdatedAtMillis = System.currentTimeMillis()
                    )
                } else {
                    gnssState.value = gnssState.value.copy(
                        statusMessage = "Listening for GNSS status.",
                        errorMessage = null,
                        lastUpdatedAtMillis = System.currentTimeMillis()
                    )
                }

                onDispose {
                    if (registered) {
                        runCatching { locationManager.unregisterGnssStatusCallback(callback) }
                    }
                }
            }
        }
    }

    return gnssState
}

@Composable
private fun rememberBatteryPercentage(): State<TimedValue<Int?>> {
    val context = LocalContext.current
    val battery = remember {
        mutableStateOf(
            TimedValue<Int?>(
                value = null,
                lastUpdatedAtMillis = null,
                apiSource = BATTERY_API_SOURCE,
                lastError = "Waiting for battery broadcast."
            )
        )
    }

    DisposableEffect(context) {
        fun parseBatteryLevel(intent: Intent?): Int? {
            if (intent == null) return null

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null

            return (level.toFloat() / scale.toFloat() * 100f).roundToInt()
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = parseBatteryLevel(intent)
                battery.value = TimedValue(
                    value = level,
                    lastUpdatedAtMillis = System.currentTimeMillis(),
                    apiSource = BATTERY_API_SOURCE,
                    lastError = if (level == null) {
                        "Battery intent delivered but level/scale extras were unavailable or invalid."
                    } else {
                        null
                    }
                )
            }
        }

        val stickyIntent = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val stickyLevel = parseBatteryLevel(stickyIntent)
        battery.value = TimedValue(
            value = stickyLevel,
            lastUpdatedAtMillis = System.currentTimeMillis(),
            apiSource = BATTERY_API_SOURCE,
            lastError = if (stickyLevel == null) "No valid sticky battery value was returned." else null
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return battery
}

@Composable
private fun rememberChargingState(): State<TimedValue<Boolean?>> {
    val context = LocalContext.current
    val charging = remember {
        mutableStateOf(
            TimedValue<Boolean?>(
                value = null,
                lastUpdatedAtMillis = null,
                apiSource = BATTERY_API_SOURCE,
                lastError = "Waiting for battery status broadcast."
            )
        )
    }

    DisposableEffect(context) {
        fun parseCharging(intent: Intent?): Boolean? {
            if (intent == null) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status == -1) return null
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val value = parseCharging(intent)
                charging.value = TimedValue(
                    value = value,
                    lastUpdatedAtMillis = System.currentTimeMillis(),
                    apiSource = BATTERY_API_SOURCE,
                    lastError = if (value == null) {
                        "Battery status extra was missing or invalid."
                    } else {
                        null
                    }
                )
            }
        }

        val stickyIntent = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val stickyCharging = parseCharging(stickyIntent)
        charging.value = TimedValue(
            value = stickyCharging,
            lastUpdatedAtMillis = System.currentTimeMillis(),
            apiSource = BATTERY_API_SOURCE,
            lastError = if (stickyCharging == null) "No valid sticky charging status was returned." else null
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return charging
}

@Composable
private fun rememberBatterySaverState(): State<TimedValue<Boolean?>> {
    val context = LocalContext.current
    val batterySaver = remember {
        mutableStateOf(
            TimedValue<Boolean?>(
                value = null,
                lastUpdatedAtMillis = null,
                apiSource = "PowerManager.isPowerSaveMode + ACTION_POWER_SAVE_MODE_CHANGED",
                lastError = "Waiting for power manager state."
            )
        )
    }

    DisposableEffect(context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager == null) {
            batterySaver.value = TimedValue(
                value = null,
                lastUpdatedAtMillis = System.currentTimeMillis(),
                apiSource = "PowerManager.isPowerSaveMode + ACTION_POWER_SAVE_MODE_CHANGED",
                lastError = "PowerManager service unavailable."
            )
            onDispose { }
        } else {
            fun updateFromPowerManager() {
                batterySaver.value = TimedValue(
                    value = powerManager.isPowerSaveMode,
                    lastUpdatedAtMillis = System.currentTimeMillis(),
                    apiSource = "PowerManager.isPowerSaveMode + ACTION_POWER_SAVE_MODE_CHANGED",
                    lastError = null
                )
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateFromPowerManager()
                }
            }

            context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            updateFromPowerManager()

            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    return batterySaver
}

@Composable
private fun rememberSignificantMotionSensorState(): State<SignificantMotionSensorState> {
    val context = LocalContext.current
    val sensorState = remember {
        mutableStateOf(
            SignificantMotionSensorState(
                available = null,
                lastUpdatedAtMillis = null,
                sensorName = null,
                sensorState = null,
                errorMessage = "Checking sensor availability."
            )
        )
    }

    DisposableEffect(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val now = System.currentTimeMillis()
        if (manager == null) {
            sensorState.value = SignificantMotionSensorState(
                available = false,
                lastUpdatedAtMillis = now,
                sensorName = null,
                sensorState = null,
                errorMessage = "SensorManager service unavailable."
            )
            onDispose { }
        } else {
            val significantMotion = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            if (significantMotion == null) {
                sensorState.value = SignificantMotionSensorState(
                    available = false,
                    lastUpdatedAtMillis = now,
                    sensorName = null,
                    sensorState = null,
                    errorMessage = "TYPE_SIGNIFICANT_MOTION is not available on this device."
                )
                onDispose { }
            } else {
                lateinit var triggerListener: TriggerEventListener
                triggerListener = object : TriggerEventListener() {
                    override fun onTrigger(event: TriggerEvent?) {
                        val triggeredAt = System.currentTimeMillis()
                        val rearmSuccess = runCatching {
                            manager.requestTriggerSensor(this, significantMotion)
                        }.getOrElse { false }

                        sensorState.value = sensorState.value.copy(
                            available = true,
                            sensorName = significantMotion.name,
                            sensorState = if (rearmSuccess) "Triggered (re-armed)" else "Triggered (re-arm failed)",
                            lastUpdatedAtMillis = triggeredAt,
                            errorMessage = if (rearmSuccess) {
                                null
                            } else {
                                "Significant motion triggered, but re-arming failed."
                            }
                        )
                    }
                }

                val armed = runCatching {
                    manager.requestTriggerSensor(triggerListener, significantMotion)
                }.getOrElse { false }

                sensorState.value = SignificantMotionSensorState(
                    available = true,
                    lastUpdatedAtMillis = now,
                    sensorName = significantMotion.name,
                    sensorState = if (armed) "Armed (waiting for trigger)" else "Not armed",
                    errorMessage = if (armed) null else "Failed to arm significant motion trigger sensor."
                )

                onDispose {
                    runCatching { manager.cancelTriggerSensor(triggerListener, significantMotion) }
                }
            }
        }
    }

    return sensorState
}

@Composable
private fun rememberLocationState(permissionGranted: Boolean): State<LocationState> {
    val context = LocalContext.current
    val locationState = remember { mutableStateOf(LocationState()) }

    DisposableEffect(context, permissionGranted) {
        if (!permissionGranted) {
            locationState.value = LocationState(
                statusMessage = "Permission denied.",
                errorMessage = "ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION not granted.",
                lastUpdatedAtMillis = System.currentTimeMillis()
            )
            onDispose { }
        } else {
            val manager = context.getSystemService(LocationManager::class.java)
            if (manager == null) {
                locationState.value = LocationState(
                    statusMessage = "Location service unavailable.",
                    errorMessage = "LocationManager service was null.",
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
                onDispose { }
            } else {
                val enabledProviders = runCatching { manager.getProviders(true) }
                    .getOrElse {
                        locationState.value = LocationState(
                            statusMessage = "Provider query failed.",
                            errorMessage = it.message ?: "Unknown provider query error.",
                            lastUpdatedAtMillis = System.currentTimeMillis()
                        )
                        emptyList()
                    }

                if (enabledProviders.isEmpty()) {
                    locationState.value = LocationState(
                        providers = emptyList(),
                        statusMessage = "No enabled location providers.",
                        errorMessage = "GPS/network providers are disabled in system settings.",
                        lastUpdatedAtMillis = System.currentTimeMillis()
                    )
                    onDispose { }
                } else {
                    var registered = false
                    val selectedProvider = when {
                        enabledProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                        enabledProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> enabledProviders.first()
                    }

                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            locationState.value = LocationState(
                                location = location,
                                providers = runCatching { manager.getProviders(true) }.getOrDefault(enabledProviders),
                                statusMessage = "Live updates active on ${location.provider ?: selectedProvider}.",
                                errorMessage = null,
                                lastUpdatedAtMillis = System.currentTimeMillis()
                            )
                        }

                        override fun onProviderDisabled(provider: String) {
                            locationState.value = locationState.value.copy(
                                providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList()),
                                statusMessage = "Provider disabled: $provider",
                                errorMessage = "Provider '$provider' was disabled while logging.",
                                lastUpdatedAtMillis = System.currentTimeMillis()
                            )
                        }

                        override fun onProviderEnabled(provider: String) {
                            locationState.value = locationState.value.copy(
                                providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList()),
                                statusMessage = "Provider enabled: $provider",
                                errorMessage = null,
                                lastUpdatedAtMillis = System.currentTimeMillis()
                            )
                        }
                    }

                    val registrationResult = runCatching {
                        manager.requestLocationUpdates(
                            selectedProvider,
                            1_000L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                    if (registrationResult.isSuccess) {
                        registered = true
                    } else {
                        locationState.value = locationState.value.copy(
                            providers = enabledProviders,
                            statusMessage = "Update registration issue.",
                            errorMessage = registrationResult.exceptionOrNull()?.message
                                ?: "Unable to register listener for provider '$selectedProvider'.",
                            lastUpdatedAtMillis = System.currentTimeMillis()
                        )
                    }

                    val bestLastKnown = enabledProviders
                        .mapNotNull { provider ->
                            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                        }
                        .maxByOrNull { it.time }

                    locationState.value = locationState.value.copy(
                        location = bestLastKnown ?: locationState.value.location,
                        providers = enabledProviders,
                        statusMessage = if (registered) {
                            "Listening on $selectedProvider."
                        } else {
                            "No active listener."
                        },
                        errorMessage = if (!registered) {
                            "Listener registration failed for provider '$selectedProvider'."
                        } else {
                            locationState.value.errorMessage
                        },
                        lastUpdatedAtMillis = System.currentTimeMillis()
                    )

                    onDispose {
                        runCatching { manager.removeUpdates(listener) }
                    }
                }
            }
        }
    }

    return locationState
}

private fun buildLocationReadings(
    locationState: LocationState,
    permissionGranted: Boolean,
    gnssSummary: GnssSatelliteSummaryState
): List<DataReading> {
    val location = locationState.location
    val hasLocation = location != null

    fun noFixReason(): String {
        return when {
            !permissionGranted -> "Location permission is denied. Android blocks LocationManager updates until at least coarse location is granted."
            locationState.providers.isEmpty() -> "No providers are enabled. Turn on device location (GPS/network) in system settings."
            locationState.errorMessage != null -> "Location pipeline reported: ${locationState.errorMessage}"
            else -> "Providers are active but no location fix has been delivered yet. Device may still be searching for satellites/network triangulation."
        }
    }

    fun readingWithLocation(
        label: String,
        valueBuilder: (Location) -> String,
        unavailableReason: String,
        api: String = LOCATION_API_SOURCE
    ): DataReading {
        return if (hasLocation) {
            DataReading(
                label = label,
                value = valueBuilder(location!!),
                apiSource = api,
                lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
                availabilitySummary = "Available",
                detailReason = "Derived from latest Location fix delivered by provider '${location.provider ?: "unknown"}'."
            )
        } else {
            DataReading(
                label = label,
                value = "Unavailable",
                apiSource = api,
                lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
                availabilitySummary = "Unavailable",
                detailReason = unavailableReason,
                isError = true
            )
        }
    }

    val rows = mutableListOf<DataReading>()

    rows += DataReading(
        label = "Permission",
        value = if (permissionGranted) "Granted" else "Denied",
        apiSource = "Runtime permission APIs",
        lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
        availabilitySummary = if (permissionGranted) "Available" else "Unavailable",
        detailReason = if (permissionGranted) {
            "At least one location permission (fine/coarse) is granted."
        } else {
            "ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION are both denied."
        },
        isError = !permissionGranted
    )

    rows += DataReading(
        label = "Status",
        value = locationState.statusMessage ?: "Unknown",
        apiSource = LOCATION_API_SOURCE,
        lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
        availabilitySummary = "Diagnostic",
        detailReason = "Current state of listener/provider pipeline."
    )

    rows += DataReading(
        label = "Error",
        value = locationState.errorMessage ?: "None",
        apiSource = LOCATION_API_SOURCE,
        lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
        availabilitySummary = if (locationState.errorMessage == null) "No error" else "Error",
        detailReason = if (locationState.errorMessage == null) {
            "No pipeline error currently tracked."
        } else {
            "Latest location pipeline error message captured by app logic."
        },
        isError = locationState.errorMessage != null
    )

    rows += DataReading(
        label = "Satellites Visible",
        value = gnssSummary.visibleCount?.toString() ?: "Unavailable",
        apiSource = GNSS_STATUS_API_SOURCE,
        lastRetrievedAtMillis = gnssSummary.lastUpdatedAtMillis ?: locationState.lastUpdatedAtMillis,
        availabilitySummary = if (gnssSummary.visibleCount == null) "Unavailable" else "Available",
        detailReason = if (gnssSummary.visibleCount == null) {
            gnssSummary.errorMessage ?: "GNSS satellite status has not been delivered yet."
        } else {
            "Total satellites currently reported by GnssStatus."
        },
        isError = gnssSummary.visibleCount == null
    )

    rows += DataReading(
        label = "Satellites Used In Fix",
        value = gnssSummary.usedInFixCount?.toString() ?: "Unavailable",
        apiSource = GNSS_STATUS_API_SOURCE,
        lastRetrievedAtMillis = gnssSummary.lastUpdatedAtMillis ?: locationState.lastUpdatedAtMillis,
        availabilitySummary = if (gnssSummary.usedInFixCount == null) "Unavailable" else "Available",
        detailReason = if (gnssSummary.usedInFixCount == null) {
            gnssSummary.errorMessage ?: "GNSS fix usage data is not available yet."
        } else {
            "Count of satellites flagged by GnssStatus as used in the current fix."
        },
        isError = gnssSummary.usedInFixCount == null
    )

    rows += DataReading(
        label = "Avg C/N0 Used (dB-Hz)",
        value = gnssSummary.avgCn0Used?.let { formatDecimal(it.toDouble(), 2) } ?: "Unavailable",
        apiSource = GNSS_STATUS_API_SOURCE,
        lastRetrievedAtMillis = gnssSummary.lastUpdatedAtMillis ?: locationState.lastUpdatedAtMillis,
        availabilitySummary = if (gnssSummary.avgCn0Used == null) "Unavailable" else "Available",
        detailReason = if (gnssSummary.avgCn0Used == null) {
            gnssSummary.errorMessage
                ?: "No satellites are currently marked used in fix, so average C/N0 is undefined."
        } else {
            "Average carrier-to-noise density (C/N0) over satellites used in fix."
        },
        isError = gnssSummary.avgCn0Used == null
    )

    val availableProviders = locationState.providers.distinct()
    rows += DataReading(
        label = "Available Providers",
        value = if (availableProviders.isEmpty()) {
            "Unavailable"
        } else {
            availableProviders.joinToString(", ")
        },
        apiSource = LOCATION_API_SOURCE,
        lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
        availabilitySummary = if (availableProviders.isEmpty()) "Unavailable" else "Available",
        detailReason = if (availableProviders.isEmpty()) {
            "No location providers are currently enabled."
        } else {
            "Providers currently enabled by Android location settings."
        },
        isError = availableProviders.isEmpty()
    )

    rows += readingWithLocation("Provider (Fix)", { it.provider ?: "Unknown" }, noFixReason())
    rows += readingWithLocation("Latitude", { formatDecimal(it.latitude, 6) }, noFixReason())
    rows += readingWithLocation("Longitude", { formatDecimal(it.longitude, 6) }, noFixReason())
    rows += readingWithLocation("Accuracy (m)", { formatDecimal(it.accuracy.toDouble(), 2) }, noFixReason())
    rows += readingWithLocation(
        "Altitude (m)",
        valueBuilder = {
            if (it.hasAltitude()) formatDecimal(it.altitude, 2) else "Unavailable"
        },
        unavailableReason = noFixReason()
    ).withFieldAvailabilityReason(
        location = location,
        unavailableReason = "Altitude not present in current fix. Provider did not include altitude for this measurement."
    )

    rows += readingWithLocation(
        "Speed (m/s)",
        valueBuilder = {
            if (it.hasSpeed()) formatDecimal(it.speed.toDouble(), 3) else "Unavailable"
        },
        unavailableReason = noFixReason()
    ).withFieldAvailabilityReason(
        location = location,
        unavailableReason = "Speed not present in current fix. Provider did not include speed for this measurement."
    )

    rows += readingWithLocation(
        "Bearing (deg)",
        valueBuilder = {
            if (it.hasBearing()) formatDecimal(it.bearing.toDouble(), 2) else "Unavailable"
        },
        unavailableReason = noFixReason()
    ).withFieldAvailabilityReason(
        location = location,
        unavailableReason = "Bearing not present in current fix. Provider did not include course heading."
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        rows += readingWithLocation(
            "Vertical Accuracy (m)",
            valueBuilder = {
                if (it.hasVerticalAccuracy()) formatDecimal(it.verticalAccuracyMeters.toDouble(), 2) else "Unavailable"
            },
            unavailableReason = noFixReason(),
            api = "Location.hasVerticalAccuracy()/verticalAccuracyMeters"
        ).withFieldAvailabilityReason(
            location = location,
            unavailableReason = "Vertical accuracy flag is false in this fix."
        )

        rows += readingWithLocation(
            "Speed Accuracy (m/s)",
            valueBuilder = {
                if (it.hasSpeedAccuracy()) formatDecimal(it.speedAccuracyMetersPerSecond.toDouble(), 3) else "Unavailable"
            },
            unavailableReason = noFixReason(),
            api = "Location.hasSpeedAccuracy()/speedAccuracyMetersPerSecond"
        ).withFieldAvailabilityReason(
            location = location,
            unavailableReason = "Speed accuracy flag is false in this fix."
        )

        rows += readingWithLocation(
            "Bearing Accuracy (deg)",
            valueBuilder = {
                if (it.hasBearingAccuracy()) formatDecimal(it.bearingAccuracyDegrees.toDouble(), 2) else "Unavailable"
            },
            unavailableReason = noFixReason(),
            api = "Location.hasBearingAccuracy()/bearingAccuracyDegrees"
        ).withFieldAvailabilityReason(
            location = location,
            unavailableReason = "Bearing accuracy flag is false in this fix."
        )
    } else {
        rows += DataReading(
            label = "Vertical Accuracy (m)",
            value = "Unavailable",
            apiSource = "Location.hasVerticalAccuracy()/verticalAccuracyMeters",
            lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
            availabilitySummary = "Unavailable",
            detailReason = "Requires Android API 26+, current API is ${Build.VERSION.SDK_INT}.",
            isError = true
        )
        rows += DataReading(
            label = "Speed Accuracy (m/s)",
            value = "Unavailable",
            apiSource = "Location.hasSpeedAccuracy()/speedAccuracyMetersPerSecond",
            lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
            availabilitySummary = "Unavailable",
            detailReason = "Requires Android API 26+, current API is ${Build.VERSION.SDK_INT}.",
            isError = true
        )
        rows += DataReading(
            label = "Bearing Accuracy (deg)",
            value = "Unavailable",
            apiSource = "Location.hasBearingAccuracy()/bearingAccuracyDegrees",
            lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
            availabilitySummary = "Unavailable",
            detailReason = "Requires Android API 26+, current API is ${Build.VERSION.SDK_INT}.",
            isError = true
        )
    }

    if (Build.VERSION.SDK_INT >= 34) {
        rows += readingWithLocation(
            "MSL Altitude (m)",
            valueBuilder = {
                if (it.hasMslAltitude()) formatDecimal(it.mslAltitudeMeters, 2) else "Unavailable"
            },
            unavailableReason = noFixReason(),
            api = "Location.hasMslAltitude()/mslAltitudeMeters"
        ).withFieldAvailabilityReason(
            location = location,
            unavailableReason = "MSL altitude is not present in current fix."
        )

        rows += readingWithLocation(
            "MSL Altitude Accuracy (m)",
            valueBuilder = {
                if (it.hasMslAltitudeAccuracy()) formatDecimal(it.mslAltitudeAccuracyMeters.toDouble(), 2) else "Unavailable"
            },
            unavailableReason = noFixReason(),
            api = "Location.hasMslAltitudeAccuracy()/mslAltitudeAccuracyMeters"
        ).withFieldAvailabilityReason(
            location = location,
            unavailableReason = "MSL altitude accuracy is not present in current fix."
        )
    } else {
        rows += DataReading(
            label = "MSL Altitude (m)",
            value = "Unavailable",
            apiSource = "Location.hasMslAltitude()/mslAltitudeMeters",
            lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
            availabilitySummary = "Unavailable",
            detailReason = "Requires Android API 34+, current API is ${Build.VERSION.SDK_INT}.",
            isError = true
        )
        rows += DataReading(
            label = "MSL Altitude Accuracy (m)",
            value = "Unavailable",
            apiSource = "Location.hasMslAltitudeAccuracy()/mslAltitudeAccuracyMeters",
            lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
            availabilitySummary = "Unavailable",
            detailReason = "Requires Android API 34+, current API is ${Build.VERSION.SDK_INT}.",
            isError = true
        )
    }

    val mockValue = if (!hasLocation) {
        "Unavailable"
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        location!!.isMock.toString()
    } else {
        @Suppress("DEPRECATION")
        location!!.isFromMockProvider.toString()
    }

    rows += DataReading(
        label = "Is Mock",
        value = mockValue,
        apiSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Location.isMock" else "Location.isFromMockProvider",
        lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
        availabilitySummary = if (!hasLocation) "Unavailable" else "Available",
        detailReason = if (!hasLocation) {
            noFixReason()
        } else {
            "True means fix appears to originate from a mock provider (developer/test source)."
        },
        isError = mockValue == "true" || !hasLocation
    )

    return rows
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun formatDecimal(value: Double, digits: Int): String {
    return "%.${digits}f".format(Locale.US, value)
}

private fun DataReading.withFieldAvailabilityReason(
    location: Location?,
    unavailableReason: String
): DataReading {
    if (location == null || value != "Unavailable") return this
    return copy(
        availabilitySummary = "Unavailable",
        detailReason = unavailableReason,
        isError = true
    )
}

private data class TimedValue<T>(
    val value: T,
    val lastUpdatedAtMillis: Long?,
    val apiSource: String,
    val lastError: String?,
    val unixEpochSeconds: Long? = null,
    val timezoneId: String? = null,
    val utcTimestamp: String? = null
)

private data class DataReading(
    val label: String,
    val value: String,
    val apiSource: String,
    val lastRetrievedAtMillis: Long?,
    val availabilitySummary: String,
    val detailReason: String,
    val isError: Boolean = false
)

private data class LocationState(
    val location: Location? = null,
    val providers: List<String> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastUpdatedAtMillis: Long? = null
)

private data class GnssSatelliteSummaryState(
    val visibleCount: Int? = null,
    val usedInFixCount: Int? = null,
    val avgCn0Used: Float? = null,
    val lastUpdatedAtMillis: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private data class SignificantMotionSensorState(
    val available: Boolean? = null,
    val lastUpdatedAtMillis: Long? = null,
    val sensorName: String? = null,
    val sensorState: String? = null,
    val errorMessage: String? = null
)
