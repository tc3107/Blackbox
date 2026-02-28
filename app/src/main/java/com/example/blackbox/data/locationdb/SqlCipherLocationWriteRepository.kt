package com.example.blackbox.data.locationdb

import android.location.Location
import androidx.room.withTransaction
import com.example.blackbox.location.LocationEngineMode
import com.example.blackbox.location.LocationSampleEvent
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class SqlCipherLocationWriteRepository(
    private val dailyDbManager: DailyDbManager
) : LocationWriteRepository {
    override suspend fun ingest(event: LocationSampleEvent) {
        val sanitized = sanitizeEvent(event) ?: return
        if (sanitized.accuracyM > MAX_PERSISTED_ACCURACY_M) {
            return
        }
        val db = dailyDbManager.currentWritableDb(Instant.ofEpochMilli(sanitized.receivedAtMs))

        db.withTransaction {
            val locationDao = db.locationSampleDao()
            val metadataDao = db.dbMetadataDao()
            val latest = locationDao.getLatest()
            val nowMs = System.currentTimeMillis()

            if (latest == null || shouldAppendNewRow(previous = latest, event = sanitized)) {
                locationDao.insert(sanitized.toEntity())
                val updatedRows = metadataDao.recordInsert(
                    receivedAtMs = sanitized.receivedAtMs,
                    lastSeenAtMs = sanitized.receivedAtMs,
                    lat = sanitized.lat,
                    lon = sanitized.lon,
                    updatedAtMs = nowMs
                )
                if (updatedRows == 0) {
                    ensureMetadataRow(metadataDao = metadataDao, referenceEvent = sanitized, nowMs = nowMs)
                    metadataDao.recordInsert(
                        receivedAtMs = sanitized.receivedAtMs,
                        lastSeenAtMs = sanitized.receivedAtMs,
                        lat = sanitized.lat,
                        lon = sanitized.lon,
                        updatedAtMs = nowMs
                    )
                }
                return@withTransaction
            }

            val mergedLastSeenAtMs = max(latest.lastSeenAtMs, sanitized.receivedAtMs)
            locationDao.updateMergedInterval(
                id = latest.id,
                lastSeenAtMs = mergedLastSeenAtMs,
                fixTimeMs = max(latest.fixTimeMs, sanitized.fixTimeMs),
                altitudeM = sanitized.altitudeM ?: latest.altitudeM,
                speedMps = sanitized.speedMps ?: latest.speedMps,
                bearingDeg = sanitized.bearingDeg ?: latest.bearingDeg,
                speedAccuracyMps = sanitized.speedAccuracyMps ?: latest.speedAccuracyMps,
                bearingAccuracyDeg = sanitized.bearingAccuracyDeg ?: latest.bearingAccuracyDeg,
                bestAccuracyM = min(latest.bestAccuracyM, sanitized.accuracyM),
                worstAccuracyM = max(latest.worstAccuracyM, sanitized.accuracyM),
                samplesMergedCount = latest.samplesMergedCount + 1
            )
            val updatedRows = metadataDao.recordMerge(
                lastSeenAtMs = mergedLastSeenAtMs,
                updatedAtMs = nowMs
            )
            if (updatedRows == 0) {
                ensureMetadataRow(metadataDao = metadataDao, referenceEvent = sanitized, nowMs = nowMs)
                metadataDao.recordMerge(
                    lastSeenAtMs = mergedLastSeenAtMs,
                    updatedAtMs = nowMs
                )
            }
        }
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

    private suspend fun ensureMetadataRow(
        metadataDao: DbMetadataDao,
        referenceEvent: LocationSampleEvent,
        nowMs: Long
    ) {
        val dayUtc = Instant.ofEpochMilli(referenceEvent.receivedAtMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        metadataDao.upsert(
            DbMetadataEntity(
                keyId = UNKNOWN_METADATA_KEY_ID,
                createdAtMs = nowMs,
                dbUuid = UUID.randomUUID().toString(),
                dayUtc = dayUtc,
                updatedAtMs = nowMs
            )
        )
    }

    private fun sanitizeEvent(event: LocationSampleEvent): LocationSampleEvent? {
        val lat = event.lat.takeIf { it.isFinite() }?.coerceIn(-90.0, 90.0) ?: return null
        val lon = event.lon.takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0) ?: return null
        val altitude = event.altitudeM?.takeIf { it.isFinite() }
        val speed = event.speedMps?.takeIf { it.isFinite() }?.coerceAtLeast(0f)
        val bearing = event.bearingDeg?.takeIf { it.isFinite() }?.let { normalizeDegrees(it) }
        val speedAccuracy = event.speedAccuracyMps?.takeIf { it.isFinite() }?.coerceAtLeast(0f)
        val bearingAccuracy = event.bearingAccuracyDeg?.takeIf { it.isFinite() }?.coerceAtLeast(0f)

        return event.copy(
            receivedAtMs = event.receivedAtMs.coerceAtLeast(0L),
            fixTimeMs = event.fixTimeMs.coerceAtLeast(0L),
            lat = lat,
            lon = lon,
            accuracyM = event.accuracyM.coerceAtLeast(0f),
            altitudeM = altitude,
            speedMps = speed,
            bearingDeg = bearing,
            speedAccuracyMps = speedAccuracy,
            bearingAccuracyDeg = bearingAccuracy
        )
    }

    private fun normalizeDegrees(value: Float): Float {
        val normalized = value % 360f
        return if (normalized < 0f) normalized + 360f else normalized
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
        private const val UNKNOWN_METADATA_KEY_ID = "unknown"
    }
}
