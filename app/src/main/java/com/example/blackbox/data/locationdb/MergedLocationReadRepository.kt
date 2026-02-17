package com.example.blackbox.data.locationdb

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class MergedLocationReadRepository(
    context: Context,
    private val keyManager: LocationDbKeyManager,
    private val archiveRepository: LocationArchiveRepository
) : LocationReadRepository {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir

    override suspend fun queryRange(startInclusiveMs: Long, endInclusiveMs: Long): List<LocationSampleEntity> {
        return withContext(Dispatchers.IO) {
            if (endInclusiveMs < startInclusiveMs) {
                return@withContext emptyList()
            }

            val startDay = Instant.ofEpochMilli(startInclusiveMs).atZone(ZoneOffset.UTC).toLocalDate()
            val endDay = Instant.ofEpochMilli(endInclusiveMs).atZone(ZoneOffset.UTC).toLocalDate()
            val archivedIndex = scanArchivedIndexByDay()
            val result = mutableListOf<LocationSampleEntity>()

            generateDaySequence(startDay = startDay, endDay = endDay).forEach { day ->
                val source = resolveBestSourceForDay(day, archivedIndex)
                val samples = when (source) {
                    is DaySource.Local -> readFromLocalDb(
                        file = source.file,
                        startInclusiveMs = startInclusiveMs,
                        endInclusiveMs = endInclusiveMs
                    )

                    is DaySource.Archive -> readFromArchivedDb(
                        archiveUri = source.archiveUri,
                        day = day,
                        startInclusiveMs = startInclusiveMs,
                        endInclusiveMs = endInclusiveMs
                    )

                    DaySource.Missing -> emptyList()
                }
                result += samples
            }

            result.sortedBy { it.receivedAtMs }
        }
    }

    private fun resolveBestSourceForDay(
        day: LocalDate,
        archivedIndex: Map<LocalDate, Uri>
    ): DaySource {
        val live = LocationDbPaths.liveDbFile(filesDir, day)
        if (live.exists()) {
            return DaySource.Local(live)
        }

        val pending = LocationDbPaths.pendingArchiveFile(filesDir, day)
        if (pending.exists()) {
            return DaySource.Local(pending)
        }

        val archivedUri = archivedIndex[day]
        if (archivedUri != null) {
            return DaySource.Archive(archivedUri)
        }

        return DaySource.Missing
    }

    private suspend fun readFromLocalDb(
        file: File,
        startInclusiveMs: Long,
        endInclusiveMs: Long
    ): List<LocationSampleEntity> {
        if (!file.exists()) {
            return emptyList()
        }
        return openWithAnyKey(file) { db ->
            db.locationSampleDao().getInRange(startInclusiveMs, endInclusiveMs)
        }
    }

    private suspend fun readFromArchivedDb(
        archiveUri: Uri,
        day: LocalDate,
        startInclusiveMs: Long,
        endInclusiveMs: Long
    ): List<LocationSampleEntity> {
        val cacheFile = File(appContext.cacheDir, "location-read-${day}.db")
        runCatching { cacheFile.delete() }

        val copied = runCatching {
            appContext.contentResolver.openInputStream(archiveUri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: error("Cannot read archived database.")
        }

        if (copied.isFailure || !cacheFile.exists()) {
            return emptyList()
        }

        val data = openWithAnyKey(cacheFile) { db ->
            db.locationSampleDao().getInRange(startInclusiveMs, endInclusiveMs)
        }

        runCatching { cacheFile.delete() }
        runCatching { File(cacheFile.parentFile, "${cacheFile.name}-wal").delete() }
        runCatching { File(cacheFile.parentFile, "${cacheFile.name}-shm").delete() }

        return data
    }

    private suspend fun <T> openWithAnyKey(file: File, block: suspend (BlackboxDayDb) -> T): T {
        val keys = keyManager.allKeys()
        var lastError: Throwable? = null

        keys.forEach { key ->
            val db = runCatching {
                Room.databaseBuilder(appContext, BlackboxDayDb::class.java, file.absolutePath)
                    .openHelperFactory(SupportOpenHelperFactory(key.keyBytes.copyOf()))
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            }.getOrNull() ?: return@forEach

            try {
                db.openHelper.writableDatabase
                return block(db)
            } catch (throwable: Throwable) {
                lastError = throwable
            } finally {
                runCatching { db.close() }
            }
        }

        throw IllegalStateException("Failed to open encrypted database with known keys.", lastError)
    }

    private fun scanArchivedIndexByDay(): Map<LocalDate, Uri> {
        val archiveRoot = archiveRepository.getArchiveTreeUri() ?: return emptyMap()
        val rootDoc = DocumentFile.fromTreeUri(appContext, archiveRoot) ?: return emptyMap()
        val output = linkedMapOf<LocalDate, Uri>()

        walkSaf(rootDoc) { file ->
            if (!file.isFile) {
                return@walkSaf
            }
            val name = file.name ?: return@walkSaf
            val day = LocationDbPaths.parseUtcDayFromDbFile(File(name)) ?: return@walkSaf
            output[day] = file.uri
        }

        return output
    }

    private fun walkSaf(root: DocumentFile, onFile: (DocumentFile) -> Unit) {
        val children = runCatching { root.listFiles() }.getOrDefault(emptyArray())
        children.forEach { child ->
            if (child.isDirectory) {
                walkSaf(child, onFile)
            } else {
                onFile(child)
            }
        }
    }

    private fun generateDaySequence(startDay: LocalDate, endDay: LocalDate): Sequence<LocalDate> {
        return generateSequence(startDay) { day ->
            day.plusDays(1).takeIf { !it.isAfter(endDay) }
        }
    }

    private sealed interface DaySource {
        data class Local(val file: File) : DaySource
        data class Archive(val archiveUri: Uri) : DaySource
        data object Missing : DaySource
    }
}
