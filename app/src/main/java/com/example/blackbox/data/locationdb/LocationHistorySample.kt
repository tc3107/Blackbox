package com.example.blackbox.data.locationdb

import androidx.room.ColumnInfo

data class LocationHistorySample(
    @ColumnInfo(name = "received_at_ms")
    val receivedAtMs: Long,
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    @ColumnInfo(name = "best_accuracy_m")
    val bestAccuracyM: Float,
    @ColumnInfo(name = "samples_merged_count")
    val samplesMergedCount: Int
)
