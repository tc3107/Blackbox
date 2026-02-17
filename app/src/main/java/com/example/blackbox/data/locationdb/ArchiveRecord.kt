package com.example.blackbox.data.locationdb

import java.time.LocalDate

enum class ArchiveStatus {
    Pending,
    Archived,
    Failed
}

data class ArchiveRecord(
    val dayUtc: LocalDate,
    val localPath: String,
    val safRelativePath: String,
    val status: ArchiveStatus,
    val retryCount: Int,
    val lastError: String?,
    val updatedAtMs: Long
)
