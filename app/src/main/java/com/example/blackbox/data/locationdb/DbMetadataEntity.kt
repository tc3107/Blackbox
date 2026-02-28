package com.example.blackbox.data.locationdb

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "db_metadata")
data class DbMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = METADATA_ROW_ID,
    @ColumnInfo(name = "key_id")
    val keyId: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "db_uuid")
    val dbUuid: String = "",
    @ColumnInfo(name = "day_utc")
    val dayUtc: String = "",
    @ColumnInfo(name = "row_count")
    val rowCount: Long = 0L,
    @ColumnInfo(name = "min_received_at_ms")
    val minReceivedAtMs: Long? = null,
    @ColumnInfo(name = "max_last_seen_at_ms")
    val maxLastSeenAtMs: Long? = null,
    @ColumnInfo(name = "min_lat")
    val minLat: Double? = null,
    @ColumnInfo(name = "max_lat")
    val maxLat: Double? = null,
    @ColumnInfo(name = "min_lon")
    val minLon: Double? = null,
    @ColumnInfo(name = "max_lon")
    val maxLon: Double? = null,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = 0L
) {
    companion object {
        const val METADATA_ROW_ID = 1
    }
}
