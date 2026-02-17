package com.example.blackbox.data.locationdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationSampleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sample: LocationSampleEntity)

    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun countAll(): Long

    @Query(
        """
        SELECT *
        FROM location_samples
        WHERE received_at_ms BETWEEN :startInclusiveMs AND :endInclusiveMs
        ORDER BY received_at_ms ASC
        """
    )
    suspend fun getInRange(startInclusiveMs: Long, endInclusiveMs: Long): List<LocationSampleEntity>
}
