package com.example.blackbox.data.locationdb

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DB_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

object LocationDbPaths {
    private const val ROOT_DIR = "location"
    private const val LIVE_DIR = "live"
    private const val PENDING_ARCHIVE_DIR = "pending_archive"
    private const val DATABASES_DIR = "databases"
    const val DB_EXTENSION = "db"

    fun utcToday(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    fun liveRoot(filesDir: File): File {
        return File(filesDir, "$ROOT_DIR/$LIVE_DIR/$DATABASES_DIR")
    }

    fun pendingArchiveRoot(filesDir: File): File {
        return File(filesDir, "$ROOT_DIR/$PENDING_ARCHIVE_DIR/$DATABASES_DIR")
    }

    fun relativePathForDay(dayUtc: LocalDate): String {
        return buildString {
            append(DATABASES_DIR)
            append('/')
            append(dayUtc.year)
            append('/')
            append(String.format(Locale.US, "%02d", dayUtc.monthValue))
            append('/')
            append(fileNameForDay(dayUtc))
        }
    }

    fun fileNameForDay(dayUtc: LocalDate): String {
        return "blackbox-${dayUtc.format(DB_DATE_FORMATTER)}.$DB_EXTENSION"
    }

    fun liveDbFile(filesDir: File, dayUtc: LocalDate): File {
        val year = dayUtc.year.toString()
        val month = String.format(Locale.US, "%02d", dayUtc.monthValue)
        return File(liveRoot(filesDir), "$year/$month/${fileNameForDay(dayUtc)}")
    }

    fun pendingArchiveFile(filesDir: File, dayUtc: LocalDate): File {
        val year = dayUtc.year.toString()
        val month = String.format(Locale.US, "%02d", dayUtc.monthValue)
        return File(pendingArchiveRoot(filesDir), "$year/$month/${fileNameForDay(dayUtc)}")
    }

    fun parseUtcDayFromDbFile(file: File): LocalDate? {
        val name = file.name
        if (!name.startsWith("blackbox-") || !name.endsWith(".$DB_EXTENSION")) {
            return null
        }
        val rawDate = name.removePrefix("blackbox-").removeSuffix(".$DB_EXTENSION")
        return runCatching { LocalDate.parse(rawDate, DB_DATE_FORMATTER) }.getOrNull()
    }

    fun ensureParentDir(file: File) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
    }
}
