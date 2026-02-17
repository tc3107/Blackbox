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
}
