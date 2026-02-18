package com.example.blackbox.data.locationdb

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LocationSampleEntity::class,
        DbMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(LocationDbConverters::class)
abstract class BlackboxDayDb : RoomDatabase() {
    abstract fun locationSampleDao(): LocationSampleDao
    abstract fun dbMetadataDao(): DbMetadataDao
}
