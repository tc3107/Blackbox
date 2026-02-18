package com.example.blackbox.data.locationdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationSampleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sample: LocationSampleEntity)

    @Query(
        """
        SELECT *
        FROM location_samples
        ORDER BY received_at_ms DESC
        LIMIT 1
        """
    )
    suspend fun getLatest(): LocationSampleEntity?

    @Query(
        """
        UPDATE location_samples
        SET
            last_seen_at_ms = :lastSeenAtMs,
            fix_time_ms = :fixTimeMs,
            altitude_m = :altitudeM,
            speed_mps = :speedMps,
            bearing_deg = :bearingDeg,
            speed_accuracy_mps = :speedAccuracyMps,
            bearing_accuracy_deg = :bearingAccuracyDeg,
            best_accuracy_m = :bestAccuracyM,
            worst_accuracy_m = :worstAccuracyM,
            samples_merged_count = :samplesMergedCount
        WHERE id = :id
        """
    )
    suspend fun updateMergedInterval(
        id: Long,
        lastSeenAtMs: Long,
        fixTimeMs: Long,
        altitudeM: Double?,
        speedMps: Float?,
        bearingDeg: Float?,
        speedAccuracyMps: Float?,
        bearingAccuracyDeg: Float?,
        bestAccuracyM: Float,
        worstAccuracyM: Float,
        samplesMergedCount: Int
    )

    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun countAll(): Long

    @Query(
        """
        SELECT *
        FROM location_samples
        WHERE last_seen_at_ms >= :startInclusiveMs
          AND received_at_ms <= :endInclusiveMs
        ORDER BY received_at_ms ASC
        """
    )
    suspend fun getInRange(startInclusiveMs: Long, endInclusiveMs: Long): List<LocationSampleEntity>
}
