package com.example.blackbox.data.locationdb

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.blackbox.location.LocationEngineMode

@Entity(
    tableName = "location_samples",
    indices = [
        Index(value = ["received_at_ms"]),
        Index(value = ["fix_time_ms"])
    ]
)
data class LocationSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "received_at_ms")
    val receivedAtMs: Long,
    @ColumnInfo(name = "last_seen_at_ms")
    val lastSeenAtMs: Long,
    @ColumnInfo(name = "fix_time_ms")
    val fixTimeMs: Long,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    @ColumnInfo(name = "best_accuracy_m")
    val bestAccuracyM: Float,
    @ColumnInfo(name = "worst_accuracy_m")
    val worstAccuracyM: Float,
    @ColumnInfo(name = "samples_merged_count")
    val samplesMergedCount: Int,
    @ColumnInfo(name = "altitude_m")
    val altitudeM: Double?,
    @ColumnInfo(name = "speed_mps")
    val speedMps: Float?,
    @ColumnInfo(name = "bearing_deg")
    val bearingDeg: Float?,
    @ColumnInfo(name = "speed_accuracy_mps")
    val speedAccuracyMps: Float?,
    @ColumnInfo(name = "bearing_accuracy_deg")
    val bearingAccuracyDeg: Float?,
    @ColumnInfo(name = "engine_mode")
    val engineMode: LocationEngineMode
)
