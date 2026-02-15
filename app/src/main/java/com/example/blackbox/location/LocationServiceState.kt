package com.example.blackbox.location

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val bearingDegrees: Float?,
    val accuracyMeters: Float?,
    val provider: String?,
    val fixTimeMillis: Long
) {
    companion object {
        fun from(location: Location): LocationSnapshot {
            return LocationSnapshot(
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                provider = location.provider,
                fixTimeMillis = location.time
            )
        }
    }
}

data class LocationServiceState(
    val isRunning: Boolean = false,
    val activeProviders: Set<String> = emptySet(),
    val lastLocation: LocationSnapshot? = null,
    val statusMessage: String = "Location service stopped.",
    val errorMessage: String? = null,
    val lastUpdatedAtMillis: Long? = null
)

object LocationServiceStateStore {
    private val _state = MutableStateFlow(LocationServiceState())
    val state: StateFlow<LocationServiceState> = _state.asStateFlow()

    fun markStarting() {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                statusMessage = "Starting location service.",
                errorMessage = null,
                lastUpdatedAtMillis = now
            )
        }
    }

    fun markRunning(activeProviders: Set<String>, statusMessage: String) {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                isRunning = true,
                activeProviders = activeProviders,
                statusMessage = statusMessage,
                errorMessage = null,
                lastUpdatedAtMillis = now
            )
        }
    }

    fun updateLocation(location: Location, activeProviders: Set<String>) {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                isRunning = true,
                activeProviders = activeProviders,
                lastLocation = LocationSnapshot.from(location),
                statusMessage = "Receiving live location updates.",
                errorMessage = null,
                lastUpdatedAtMillis = now
            )
        }
    }

    fun updateError(message: String, activeProviders: Set<String> = emptySet()) {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                isRunning = true,
                activeProviders = activeProviders,
                statusMessage = "Location service running with issues.",
                errorMessage = message,
                lastUpdatedAtMillis = now
            )
        }
    }

    fun markStopped() {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                isRunning = false,
                activeProviders = emptySet(),
                statusMessage = "Location service stopped.",
                errorMessage = null,
                lastUpdatedAtMillis = now
            )
        }
    }
}
