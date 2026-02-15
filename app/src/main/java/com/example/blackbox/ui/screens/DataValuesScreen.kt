package com.example.blackbox.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.BatteryManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val LOCATION_API_SOURCE = "android.location.LocationManager + android.location.LocationListener"
private const val BATTERY_API_SOURCE = "Intent.ACTION_BATTERY_CHANGED"
private const val TIME_API_SOURCE = "System clock + kotlinx.coroutines.delay"
private const val TIME_API_SOURCE_UTC = "java.text.SimpleDateFormat (UTC)"

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
        permissionGranted = locationPermissionGranted
    )

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
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val utcFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    val initialNow = System.currentTimeMillis()

    return produceState(
        initialValue = TimedValue(
            value = formatter.format(Date()),
            lastUpdatedAtMillis = initialNow,
            apiSource = TIME_API_SOURCE,
            lastError = null,
            unixEpochSeconds = initialNow / 1_000L,
            timezoneId = java.util.TimeZone.getDefault().id,
            utcTimestamp = utcFormatter.format(Date(initialNow))
        )
    ) {
        while (true) {
            val now = System.currentTimeMillis()
            val timeZone = java.util.TimeZone.getDefault()
            value = TimedValue(
                value = formatter.format(Date()),
                lastUpdatedAtMillis = now,
                apiSource = TIME_API_SOURCE,
                lastError = null,
                unixEpochSeconds = now / 1_000L,
                timezoneId = timeZone.id,
                utcTimestamp = utcFormatter.format(Date(now))
            )
            delay(1_000L)
        }
    }
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
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            locationState.value = LocationState(
                                location = location,
                                providers = runCatching { manager.getProviders(true) }.getOrDefault(enabledProviders),
                                statusMessage = "Live updates active.",
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

                    enabledProviders.forEach { provider ->
                        val result = runCatching {
                            manager.requestLocationUpdates(
                                provider,
                                1_000L,
                                0f,
                                listener,
                                Looper.getMainLooper()
                            )
                        }
                        if (result.isSuccess) {
                            registered = true
                        } else {
                            locationState.value = locationState.value.copy(
                                providers = enabledProviders,
                                statusMessage = "Update registration issue.",
                                errorMessage = result.exceptionOrNull()?.message
                                    ?: "Unable to register listener for provider '$provider'.",
                                lastUpdatedAtMillis = System.currentTimeMillis()
                            )
                        }
                    }

                    val bestLastKnown = enabledProviders
                        .mapNotNull { provider ->
                            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                        }
                        .maxByOrNull { it.time }

                    locationState.value = locationState.value.copy(
                        location = bestLastKnown ?: locationState.value.location,
                        providers = enabledProviders,
                        statusMessage = if (registered) "Listening for updates." else "No active listeners.",
                        errorMessage = if (!registered) {
                            "Listeners failed to register for every enabled provider."
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
    permissionGranted: Boolean
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

    val satellitesValue = if (!hasLocation) {
        "Unavailable"
    } else {
        val extras = location!!.extras
        if (extras?.containsKey("satellites") == true) {
            extras.getInt("satellites", -1).takeIf { it >= 0 }?.toString() ?: "Unavailable"
        } else {
            "Unavailable"
        }
    }

    rows += DataReading(
        label = "Satellites",
        value = satellitesValue,
        apiSource = "Location.extras[\"satellites\"] (provider-specific)",
        lastRetrievedAtMillis = locationState.lastUpdatedAtMillis,
        availabilitySummary = if (satellitesValue == "Unavailable") "Unavailable" else "Available",
        detailReason = when {
            !hasLocation -> noFixReason()
            satellitesValue == "Unavailable" -> "Provider extras do not expose satellite count for this fix/device."
            else -> "Satellite count exposed in location extras by provider implementation."
        },
        isError = satellitesValue == "Unavailable"
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

private fun formatEpoch(epochMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
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
