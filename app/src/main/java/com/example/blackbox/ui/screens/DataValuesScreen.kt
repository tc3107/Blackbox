package com.example.blackbox.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val BATTERY_API_SOURCE = "Intent.ACTION_BATTERY_CHANGED"
private const val TIME_API_SOURCE = "System clock + kotlinx.coroutines.delay"

@Composable
fun DataValuesScreen(modifier: Modifier = Modifier) {
    val timestamp by rememberLiveTimestamp()
    val battery by rememberBatteryPercentage()
    val chargingState by rememberChargingState()
    val batterySaver by rememberBatterySaverState()

    val timeReadings = listOf(
        DataReading(
            label = "Timestamp",
            value = timestamp.value,
            availabilitySummary = "Available"
        ),
        DataReading(
            label = "Absolute Timestamp (UTC)",
            value = timestamp.utcTimestamp ?: "Unavailable",
            availabilitySummary = if (timestamp.utcTimestamp == null) "Unavailable" else "Available",
            isError = timestamp.utcTimestamp == null
        ),
        DataReading(
            label = "Unix Timestamp",
            value = timestamp.unixEpochSeconds?.toString() ?: "Unavailable",
            availabilitySummary = if (timestamp.unixEpochSeconds == null) "Unavailable" else "Available",
            isError = timestamp.unixEpochSeconds == null
        ),
        DataReading(
            label = "Timezone",
            value = timestamp.timezoneId ?: "Unavailable",
            availabilitySummary = if (timestamp.timezoneId == null) "Unavailable" else "Available"
        )
    )

    val powerReadings = listOf(
        DataReading(
            label = "Battery Level",
            value = battery.value?.let { "$it%" } ?: "Unavailable",
            availabilitySummary = if (battery.value == null) "Unavailable" else "Available",
            isError = battery.value == null
        ),
        DataReading(
            label = "Is Charging",
            value = chargingState.value?.let { if (it) "Yes" else "No" } ?: "Unavailable",
            availabilitySummary = if (chargingState.value == null) "Unavailable" else "Available",
            isError = chargingState.value == null
        ),
        DataReading(
            label = "Battery Saver",
            value = batterySaver.value?.let { if (it) "On" else "Off" } ?: "Unavailable",
            availabilitySummary = if (batterySaver.value == null) "Unavailable" else "Available",
            isError = batterySaver.value == null
        )
    )

    val groups = listOf(
        DataGroup(
            title = "Time",
            summary = summarizeAvailability(timeReadings),
            rows = timeReadings
        ),
        DataGroup(
            title = "Power",
            summary = summarizeAvailability(powerReadings),
            rows = powerReadings
        )
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle(title = "Data Values") }

        items(items = groups, key = { it.title }) { group ->
            ExpandableDataBox(
                title = group.title,
                summary = group.summary,
                rows = group.rows,
                initiallyExpanded = group.initiallyExpanded
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ExpandableDataBox(
    title: String,
    summary: String,
    rows: List<DataReading>,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (expanded) "Collapse" else "Expand",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                if (rows.isEmpty()) {
                    Text(
                        text = "No readings yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    rows.forEachIndexed { index, row ->
                        DataReadingRow(reading = row)
                        if (index != rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataReadingRow(reading: DataReading) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = reading.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = reading.value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (reading.isError) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            text = "Status: ${reading.availabilitySummary}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (reading.isError) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp)
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
    val availabilitySummary: String,
    val isError: Boolean = false
)

private data class DataGroup(
    val title: String,
    val summary: String,
    val rows: List<DataReading>,
    val initiallyExpanded: Boolean = false
)

private fun summarizeAvailability(readings: List<DataReading>): String {
    val total = readings.size
    if (total == 0) return "No readings."
    val available = readings.count { !it.isError && it.availabilitySummary.equals("Available", ignoreCase = true) }
    return "$available/$total available"
}
