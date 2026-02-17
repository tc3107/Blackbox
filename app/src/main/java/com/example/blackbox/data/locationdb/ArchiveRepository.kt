package com.example.blackbox.data.locationdb

import java.time.LocalDate

interface ArchiveRepository {
    suspend fun queueAndArchive(day: LocalDate): Result<Unit>
}
