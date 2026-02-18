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
            val liveIndex = scanLocalIndexByDay(LocationDbPaths.liveRoot(filesDir))
            val pendingIndex = scanLocalIndexByDay(LocationDbPaths.pendingArchiveRoot(filesDir))
            val archivedIndex = scanArchivedIndexByDay()
            val result = mutableListOf<LocationSampleEntity>()

            generateDaySequence(startDay = startDay, endDay = endDay).forEach { day ->
                val localFiles = liveIndex[day].orEmpty() + pendingIndex[day].orEmpty()
                val archivedUris = archivedIndex[day].orEmpty()

                localFiles.forEach { localFile ->
                    result += readFromLocalDb(
                        file = localFile,
                        startInclusiveMs = startInclusiveMs,
                        endInclusiveMs = endInclusiveMs
                    )
                }

                archivedUris.forEachIndexed { index, archivedUri ->
                    result += readFromArchivedDb(
                        archiveUri = archivedUri,
                        day = day,
                        cacheSuffix = index,
                        startInclusiveMs = startInclusiveMs,
                        endInclusiveMs = endInclusiveMs
                    )
                }
            }

            deduplicateSamples(result.sortedBy { it.receivedAtMs })
        }
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
        cacheSuffix: Int,
        startInclusiveMs: Long,
        endInclusiveMs: Long
    ): List<LocationSampleEntity> {
        val cacheFile = File(appContext.cacheDir, "location-read-${day}-$cacheSuffix.db")
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
                    .addMigrations(LocationDbMigrations.MIGRATION_1_2)
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

    private fun scanArchivedIndexByDay(): Map<LocalDate, List<Uri>> {
        val archiveRoot = archiveRepository.getArchiveTreeUri() ?: return emptyMap()
        val rootDoc = DocumentFile.fromTreeUri(appContext, archiveRoot) ?: return emptyMap()
        val output = linkedMapOf<LocalDate, MutableList<Uri>>()

        walkSaf(rootDoc) { file ->
            if (!file.isFile) {
                return@walkSaf
            }
            val name = file.name ?: return@walkSaf
            val day = LocationDbPaths.parseUtcDayFromDbFile(File(name)) ?: return@walkSaf
            output.getOrPut(day) { mutableListOf() } += file.uri
        }

        return output.mapValues { (_, uris) -> uris.sortedBy { it.toString() } }
    }

    private fun scanLocalIndexByDay(root: File): Map<LocalDate, List<File>> {
        if (!root.exists()) {
            return emptyMap()
        }
        val output = linkedMapOf<LocalDate, MutableList<File>>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == LocationDbPaths.DB_EXTENSION }
            .forEach { file ->
                val day = LocationDbPaths.parseUtcDayFromDbFile(file) ?: return@forEach
                output.getOrPut(day) { mutableListOf() } += file
            }
        return output.mapValues { (_, files) -> files.sortedBy { it.absolutePath } }
    }

    private fun deduplicateSamples(samples: List<LocationSampleEntity>): List<LocationSampleEntity> {
        val seen = linkedSetOf<SampleKey>()
        val output = ArrayList<LocationSampleEntity>(samples.size)
        samples.forEach { sample ->
            val key = SampleKey(
                receivedAtMs = sample.receivedAtMs,
                lastSeenAtMs = sample.lastSeenAtMs,
                fixTimeMs = sample.fixTimeMs,
                provider = sample.provider,
                lat = sample.lat,
                lon = sample.lon,
                bestAccuracyM = sample.bestAccuracyM,
                worstAccuracyM = sample.worstAccuracyM,
                samplesMergedCount = sample.samplesMergedCount,
                engineMode = sample.engineMode.name
            )
            if (seen.add(key)) {
                output += sample
            }
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

    private data class SampleKey(
        val receivedAtMs: Long,
        val lastSeenAtMs: Long,
        val fixTimeMs: Long,
        val provider: String,
        val lat: Double,
        val lon: Double,
        val bestAccuracyM: Float,
        val worstAccuracyM: Float,
        val samplesMergedCount: Int,
        val engineMode: String
    )
}
