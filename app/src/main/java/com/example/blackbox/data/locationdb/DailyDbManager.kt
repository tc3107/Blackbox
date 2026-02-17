package com.example.blackbox.data.locationdb

import java.time.Instant

interface DailyDbManager {
    suspend fun currentWritableDb(nowUtc: Instant): BlackboxDayDb
}
