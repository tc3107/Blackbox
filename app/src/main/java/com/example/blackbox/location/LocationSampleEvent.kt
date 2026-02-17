package com.example.blackbox.location

data class LocationSampleEvent(
    val receivedAtMs: Long,
    val fixTimeMs: Long,
    val provider: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val speedAccuracyMps: Float?,
    val bearingAccuracyDeg: Float?,
    val engineMode: LocationEngineMode
)
