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
    val createdAtMs: Long
) {
    companion object {
        const val METADATA_ROW_ID = 1
    }
}
