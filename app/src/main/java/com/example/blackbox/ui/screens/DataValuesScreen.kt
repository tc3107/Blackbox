package com.example.blackbox.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun DataValuesScreen(modifier: Modifier = Modifier) {
    val timestamp by rememberLiveTimestamp()
    val batteryPercentage by rememberBatteryPercentage()

    val readings = listOf(
        DataReading(
            label = "Timestamp",
            value = timestamp
        ),
        DataReading(
            label = "Battery Level",
            value = batteryPercentage?.let { "$it%" } ?: "Unknown"
        )
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items = readings, key = { it.label }) { reading ->
            DataReadingRow(reading = reading)
        }
    }
}

@Composable
private fun DataReadingRow(reading: DataReading) {
    Text(
        text = reading.label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = reading.value,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
    )
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun rememberLiveTimestamp(): State<String> {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    return produceState(initialValue = formatter.format(Date())) {
        while (true) {
            value = formatter.format(Date())
            delay(1_000L)
        }
    }
}

@Composable
private fun rememberBatteryPercentage(): State<Int?> {
    val context = LocalContext.current
    val batteryPercentage = remember { mutableStateOf<Int?>(null) }

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
                batteryPercentage.value = parseBatteryLevel(intent)
            }
        }

        val stickyIntent = context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        batteryPercentage.value = parseBatteryLevel(stickyIntent)

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return batteryPercentage
}

private data class DataReading(
    val label: String,
    val value: String
)
