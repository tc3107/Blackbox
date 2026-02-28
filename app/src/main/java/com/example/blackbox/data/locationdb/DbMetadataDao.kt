package com.example.blackbox.data.locationdb

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DbMetadataDao {
    @Upsert
    suspend fun upsert(metadata: DbMetadataEntity)

    @Query("SELECT * FROM db_metadata WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int = DbMetadataEntity.METADATA_ROW_ID): DbMetadataEntity?

    @Query(
        """
        UPDATE db_metadata
        SET
            row_count = row_count + 1,
            min_received_at_ms = CASE
                WHEN min_received_at_ms IS NULL OR :receivedAtMs < min_received_at_ms THEN :receivedAtMs
                ELSE min_received_at_ms
            END,
            max_last_seen_at_ms = CASE
                WHEN max_last_seen_at_ms IS NULL OR :lastSeenAtMs > max_last_seen_at_ms THEN :lastSeenAtMs
                ELSE max_last_seen_at_ms
            END,
            min_lat = CASE
                WHEN min_lat IS NULL OR :lat < min_lat THEN :lat
                ELSE min_lat
            END,
            max_lat = CASE
                WHEN max_lat IS NULL OR :lat > max_lat THEN :lat
                ELSE max_lat
            END,
            min_lon = CASE
                WHEN min_lon IS NULL OR :lon < min_lon THEN :lon
                ELSE min_lon
            END,
            max_lon = CASE
                WHEN max_lon IS NULL OR :lon > max_lon THEN :lon
                ELSE max_lon
            END,
            updated_at_ms = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun recordInsert(
        receivedAtMs: Long,
        lastSeenAtMs: Long,
        lat: Double,
        lon: Double,
        updatedAtMs: Long,
        id: Int = DbMetadataEntity.METADATA_ROW_ID
    ): Int

    @Query(
        """
        UPDATE db_metadata
        SET
            max_last_seen_at_ms = CASE
                WHEN max_last_seen_at_ms IS NULL OR :lastSeenAtMs > max_last_seen_at_ms THEN :lastSeenAtMs
                ELSE max_last_seen_at_ms
            END,
            updated_at_ms = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun recordMerge(
        lastSeenAtMs: Long,
        updatedAtMs: Long,
        id: Int = DbMetadataEntity.METADATA_ROW_ID
    ): Int
}
