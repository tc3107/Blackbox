package com.example.blackbox.data.locationdb

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class DailyRoomDbManager(
    context: Context,
    private val keyManager: LocationDbKeyManager,
    private val archiveRepository: ArchiveRepository
) : DailyDbManager {
    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val mutex = Mutex()

    private var openDbState: OpenDbState? = null
    private var startupSweepDone = false

    override suspend fun currentWritableDb(nowUtc: Instant): BlackboxDayDb {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val targetDay = nowUtc.atZone(ZoneOffset.UTC).toLocalDate()
                if (!startupSweepDone) {
                    startupSweepDone = true
                    queueStaleLiveDays(todayUtc = targetDay)
                }

                val existing = openDbState
                if (existing != null && existing.dayUtc == targetDay) {
                    return@withLock existing.db
                }

                if (existing != null) {
                    checkpointAndClose(existing.db)
                    archiveRepository.queueAndArchive(existing.dayUtc)
                    openDbState = null
                }

                val opened = openWritableDbForDay(targetDay)
                openDbState = opened
                opened.db
            }
        }
    }

    suspend fun currentDayEntryCount(nowUtc: Instant): Long {
        val db = currentWritableDb(nowUtc)
        return db.locationSampleDao().countAll()
    }

    suspend fun checkpointCurrentDay(nowUtc: Instant): LocalDate? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val targetDay = nowUtc.atZone(ZoneOffset.UTC).toLocalDate()
                if (!startupSweepDone) {
                    startupSweepDone = true
                    queueStaleLiveDays(todayUtc = targetDay)
                }

                val existing = openDbState
                val stateForDay = if (existing != null && existing.dayUtc == targetDay) {
                    existing
                } else {
                    existing?.let {
                        checkpointAndClose(it.db)
                        archiveRepository.queueAndArchive(it.dayUtc)
                        openDbState = null
                    }
                    openWritableDbForDay(targetDay).also { opened ->
                        openDbState = opened
                    }
                }

                checkpoint(stateForDay.db)
                stateForDay.dayUtc
            }
        }
    }

    suspend fun clearAllLocalDatabases(): LocalClearResult {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                openDbState?.let { state ->
                    checkpointAndClose(state.db)
                    openDbState = null
                }

                val liveRoot = LocationDbPaths.liveRoot(filesDir)
                val pendingRoot = LocationDbPaths.pendingArchiveRoot(filesDir)
                val liveFileCount = countFilesUnder(liveRoot)
                val pendingFileCount = countFilesUnder(pendingRoot)

                val liveDeleteSuccess = runCatching {
                    if (liveRoot.exists()) {
                        liveRoot.deleteRecursively()
                    } else {
                        true
                    }
                }.getOrDefault(false)
                val pendingDeleteSuccess = runCatching {
                    if (pendingRoot.exists()) {
                        pendingRoot.deleteRecursively()
                    } else {
                        true
                    }
                }.getOrDefault(false)

                startupSweepDone = false

                LocalClearResult(
                    deletedLiveFiles = if (liveDeleteSuccess) liveFileCount else 0,
                    deletedPendingFiles = if (pendingDeleteSuccess) pendingFileCount else 0,
                    success = liveDeleteSuccess && pendingDeleteSuccess
                )
            }
        }
    }

    private suspend fun queueStaleLiveDays(todayUtc: LocalDate) {
        val liveRoot = LocationDbPaths.liveRoot(filesDir)
        if (!liveRoot.exists()) {
            return
        }

        val staleDays = liveRoot
            .walkTopDown()
            .maxDepth(5)
            .filter { it.isFile && it.extension == LocationDbPaths.DB_EXTENSION }
            .mapNotNull { LocationDbPaths.parseUtcDayFromDbFile(it) }
            .filter { it.isBefore(todayUtc) }
            .distinct()
            .sorted()
            .toList()

        staleDays.forEach { day ->
            archiveRepository.queueAndArchive(day)
        }
    }

    private suspend fun openWritableDbForDay(dayUtc: LocalDate): OpenDbState {
        val dbFile = LocationDbPaths.liveDbFile(filesDir, dayUtc)
        LocationDbPaths.ensureParentDir(dbFile)

        val dbAlreadyExists = dbFile.exists()
        val keyCandidates = resolveKeyCandidates(dbAlreadyExists)

        val opened = keyCandidates.firstNotNullOfOrNull { keyMaterial ->
            openWithKey(dbFile = dbFile, keyMaterial = keyMaterial)
        } ?: error("Unable to open daily database for $dayUtc with available key material.")

        val existingMetadata = opened.db.dbMetadataDao().getById()
        if (existingMetadata == null) {
            opened.db.dbMetadataDao().upsert(
                DbMetadataEntity(
                    keyId = opened.keyId,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }

        return opened.copy(dayUtc = dayUtc, file = dbFile)
    }

    private fun resolveKeyCandidates(dbAlreadyExists: Boolean): List<DbKeyMaterial> {
        val active = keyManager.activeKey()
        if (!dbAlreadyExists) {
            return listOf(active)
        }

        val all = keyManager.allKeys()
        val nonActive = all.filterNot { it.keyId == active.keyId }
        return listOf(active) + nonActive
    }

    private fun openWithKey(dbFile: File, keyMaterial: DbKeyMaterial): OpenDbState? {
        val db = runCatching {
            Room.databaseBuilder(appContext, BlackboxDayDb::class.java, dbFile.absolutePath)
                .openHelperFactory(SupportOpenHelperFactory(keyMaterial.keyBytes.copyOf()))
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }.getOrNull() ?: return null

        val opened = runCatching {
            db.openHelper.writableDatabase
            db
        }.getOrElse {
            runCatching { db.close() }
            return null
        }

        return OpenDbState(
            dayUtc = LocationDbPaths.parseUtcDayFromDbFile(dbFile) ?: LocationDbPaths.utcToday(),
            file = dbFile,
            db = opened,
            keyId = keyMaterial.keyId
        )
    }

    private fun checkpointAndClose(db: BlackboxDayDb) {
        checkpoint(db)
        runCatching { db.close() }
    }

    private fun checkpoint(db: BlackboxDayDb) {
        runCatching {
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { }
        }
    }

    private fun countFilesUnder(root: File): Int {
        if (!root.exists()) {
            return 0
        }
        return root.walkTopDown().count { it.isFile }
    }

    private data class OpenDbState(
        val dayUtc: LocalDate,
        val file: File,
        val db: BlackboxDayDb,
        val keyId: String
    )
}

data class LocalClearResult(
    val deletedLiveFiles: Int,
    val deletedPendingFiles: Int,
    val success: Boolean
)
