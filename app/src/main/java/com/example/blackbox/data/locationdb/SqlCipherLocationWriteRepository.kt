package com.example.blackbox.data.locationdb

import com.example.blackbox.location.LocationSampleEvent
import java.time.Instant

class SqlCipherLocationWriteRepository(
    private val dailyDbManager: DailyDbManager
) : LocationWriteRepository {
    override suspend fun ingest(event: LocationSampleEvent) {
        val db = dailyDbManager.currentWritableDb(Instant.ofEpochMilli(event.receivedAtMs))
        db.locationSampleDao().insert(event.toEntity())
    }
}
