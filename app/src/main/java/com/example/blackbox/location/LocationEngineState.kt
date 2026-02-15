package com.example.blackbox.location

import android.location.Location

enum class LocationEngineMode {
    Off,
    LowPower,
    Active
}

data class PositionFix(
    val location: Location,
    val provider: String,
    val accuracyMeters: Float,
    val fixTimeMillis: Long,
    val receivedAtMillis: Long,
    val ageMillis: Long
)

data class MotionFix(
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingAccuracyDegrees: Float?,
    val provider: String,
    val fixTimeMillis: Long,
    val ageMillis: Long
)

data class SignificantMotionSummary(
    val available: Boolean,
    val sensorName: String?,
    val armed: Boolean,
    val lastTriggeredAtMillis: Long?
)

data class SatelliteSummaryState(
    val visibleCount: Int? = null,
    val usedInFixCount: Int? = null,
    val avgCn0Used: Float? = null,
    val constellationCounts: Map<String, Int> = emptyMap(),
    val lastUpdatedAtMillis: Long? = null,
    val statusMessage: String = "Satellite summary unavailable."
)

data class LocationEngineMessage(
    val timestampMillis: Long,
    val message: String,
    val isError: Boolean
)

data class LocationEngineState(
    val engineEnabled: Boolean = true,
    val allowLowPowerBackground: Boolean = true,
    val forceActive: Boolean = false,
    val engineMode: LocationEngineMode = LocationEngineMode.Off,
    val bestPositionFix: PositionFix? = null,
    val bestMotionFix: MotionFix? = null,
    val motionStatus: String = "Unavailable: no eligible fix.",
    val enabledProviders: Set<String> = emptySet(),
    val subscribedProviders: Set<String> = emptySet(),
    val highDemandConsumers: Set<String> = emptySet(),
    val significantMotion: SignificantMotionSummary = SignificantMotionSummary(
        available = false,
        sensorName = null,
        armed = false,
        lastTriggeredAtMillis = null
    ),
    val satelliteSummary: SatelliteSummaryState = SatelliteSummaryState(),
    val lastStatusMessage: String = "Engine initializing.",
    val lastErrorMessage: String? = null,
    val statusHistory: List<LocationEngineMessage> = emptyList(),
    val lastUpdatedAtMillis: Long = System.currentTimeMillis()
)
