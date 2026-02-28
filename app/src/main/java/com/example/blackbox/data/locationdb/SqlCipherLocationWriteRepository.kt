package com.example.blackbox.data.locationdb

import android.location.Location
import com.example.blackbox.location.LocationEngineMode
import com.example.blackbox.location.LocationSampleEvent
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

class SqlCipherLocationWriteRepository(
    private val dailyDbManager: DailyDbManager
) : LocationWriteRepository {
    override suspend fun ingest(event: LocationSampleEvent) {
        if (event.accuracyM > MAX_PERSISTED_ACCURACY_M) {
            return
        }
        val db = dailyDbManager.currentWritableDb(Instant.ofEpochMilli(event.receivedAtMs))
        val dao = db.locationSampleDao()
        val latest = dao.getLatest()

        if (latest == null || shouldAppendNewRow(previous = latest, event = event)) {
            dao.insert(event.toEntity())
            return
        }

        dao.updateMergedInterval(
            id = latest.id,
            lastSeenAtMs = max(latest.lastSeenAtMs, event.receivedAtMs),
            fixTimeMs = max(latest.fixTimeMs, event.fixTimeMs),
            altitudeM = event.altitudeM ?: latest.altitudeM,
            speedMps = event.speedMps ?: latest.speedMps,
            bearingDeg = event.bearingDeg ?: latest.bearingDeg,
            speedAccuracyMps = event.speedAccuracyMps ?: latest.speedAccuracyMps,
            bearingAccuracyDeg = event.bearingAccuracyDeg ?: latest.bearingAccuracyDeg,
            bestAccuracyM = min(latest.bestAccuracyM, event.accuracyM),
            worstAccuracyM = max(latest.worstAccuracyM, event.accuracyM),
            samplesMergedCount = latest.samplesMergedCount + 1
        )
    }

    private fun shouldAppendNewRow(previous: LocationSampleEntity, event: LocationSampleEvent): Boolean {
        if (event.engineMode != previous.engineMode) {
            return true
        }

        return when (event.engineMode) {
            LocationEngineMode.LowPower -> shouldAppendInLowPower(previous = previous, event = event)
            LocationEngineMode.Active -> shouldAppendInActive(previous = previous, event = event)
            LocationEngineMode.Off -> shouldAppendInLowPower(previous = previous, event = event)
        }
    }

    private fun shouldAppendInLowPower(previous: LocationSampleEntity, event: LocationSampleEvent): Boolean {
        val elapsedMs = event.receivedAtMs - previous.receivedAtMs
        if (elapsedMs >= LOW_POWER_MAX_INTERVAL_MS) {
            return true
        }
        if (event.provider != previous.provider) {
            return true
        }

        val distanceMeters = distanceMeters(
            startLat = previous.lat,
            startLon = previous.lon,
            endLat = event.lat,
            endLon = event.lon
        )
        if (distanceMeters > LOW_POWER_DISTANCE_METERS) {
            return true
        }

        val improvedAccuracyMeters = previous.bestAccuracyM - event.accuracyM
        return improvedAccuracyMeters >= LOW_POWER_BETTER_ACCURACY_METERS
    }

    private fun shouldAppendInActive(previous: LocationSampleEntity, event: LocationSampleEvent): Boolean {
        val distanceMeters = distanceMeters(
            startLat = previous.lat,
            startLon = previous.lon,
            endLat = event.lat,
            endLon = event.lon
        )
        if (distanceMeters > ACTIVE_DISTANCE_TRIGGER_METERS) {
            return true
        }

        val speedKph = (event.speedMps ?: 0f) * METERS_PER_SECOND_TO_KPH
        val minIntervalMs = if (speedKph > ACTIVE_HIGH_SPEED_THRESHOLD_KPH) {
            ACTIVE_HIGH_SPEED_INTERVAL_MS
        } else {
            ACTIVE_BASE_INTERVAL_MS
        }
        return (event.receivedAtMs - previous.receivedAtMs) >= minIntervalMs
    }

    private fun distanceMeters(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLon, endLat, endLon, results)
        return results[0]
    }

    companion object {
        private const val MAX_PERSISTED_ACCURACY_M = 100f
        private const val LOW_POWER_MAX_INTERVAL_MS = 30L * 60L * 1_000L
        private const val LOW_POWER_DISTANCE_METERS = 30f
        private const val LOW_POWER_BETTER_ACCURACY_METERS = 20f

        private const val ACTIVE_BASE_INTERVAL_MS = 5_000L
        private const val ACTIVE_HIGH_SPEED_INTERVAL_MS = 1_000L
        private const val ACTIVE_HIGH_SPEED_THRESHOLD_KPH = 50f
        private const val ACTIVE_DISTANCE_TRIGGER_METERS = 25f
        private const val METERS_PER_SECOND_TO_KPH = 3.6f
    }
}
