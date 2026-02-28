package com.example.blackbox.data.locationdb

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.blackbox.logging.AppLog as Log
import androidx.core.database.sqlite.transaction
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationSampleEvent
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_WRITE_RATE_MS = 1_000L
private const val MAX_PERSISTED_ACCURACY_M = 100f
private const val ARCHIVE_RETRY_INTERVAL_MS = 60_000L
private const val STARTUP_ARCHIVE_DELAY_MS = 45_000L
private const val PERSIST_DEBUG_TAG = "BlackboxPersistDebug"
private const val ENABLE_VERBOSE_PERSIST_LOGS = false

data class LocationPersistenceState(
    val initialized: Boolean = false,
    val loggingEnabled: Boolean = false,
    val archiveRootUri: Uri? = null,
    val pendingArchiveCount: Int = 0,
    val liveDayEntryCount: Long = 0L,
    val totalPersistedWrites: Long = 0L,
    val lastWriteAtMs: Long? = null,
    val lastArchiveAtMs: Long? = null,
    val lastArchiveMessage: String = "Archive idle.",
    val integrityTotalFiles: Int = 0,
    val integritySucceededFiles: Int = 0,
    val integrityFailedFiles: Int = 0,
    val integrityLastCheckedAtMs: Long? = null,
    val integrityMessage: String = "Integrity check pending.",
    val integrityCheckRunning: Boolean = false,
    val lastError: String? = null
)

data class ClearAllResult(
    val deletedLiveFiles: Int,
    val deletedPendingFiles: Int,
    val deletedArchivedFiles: Int,
    val success: Boolean
)

object LocationPersistenceController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeThrottleMutex = Mutex()
    private val integrityCheckMutex = Mutex()

    private val _state = MutableStateFlow(LocationPersistenceState())
    val state: StateFlow<LocationPersistenceState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    private var keyManager: LocationDbKeyManager? = null
    private var archiveRepository: LocationArchiveRepository? = null
    private var dailyDbManager: DailyRoomDbManager? = null
    private var writeRepository: LocationWriteRepository? = null
    private var readRepository: LocationReadRepository? = null
    private var appContext: Context? = null

    private var eventCollectorJob: Job? = null
    private var cooldownJob: Job? = null
    private var pendingEvent: LocationSampleEvent? = null
    private var archiveRetryJob: Job? = null
    @Volatile
    private var loggingEnabled: Boolean = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val appContext = context.applicationContext
            // SQLCipher requires explicit native library loading before first DB open.
            System.loadLibrary("sqlcipher")
            val localKeyManager = LocationDbKeyManager(appContext)
            val localArchiveRepository = LocationArchiveRepository(
                context = appContext,
                keyManager = localKeyManager
            )
            val localDailyManager = DailyRoomDbManager(
                context = appContext,
                keyManager = localKeyManager,
                archiveRepository = localArchiveRepository
            )

            keyManager = localKeyManager
            archiveRepository = localArchiveRepository
            dailyDbManager = localDailyManager
            writeRepository = SqlCipherLocationWriteRepository(localDailyManager)
            this.appContext = appContext
            readRepository = MergedLocationReadRepository(
                context = appContext,
                keyManager = localKeyManager,
                archiveRepository = localArchiveRepository
            )

            _state.value = _state.value.copy(
                initialized = true,
                loggingEnabled = loggingEnabled,
                archiveRootUri = localArchiveRepository.getArchiveTreeUri(),
                pendingArchiveCount = localArchiveRepository.pendingArchiveCount(),
                lastArchiveMessage = "Persistence initialized."
            )

            startEventCollector()
            startArchiveRetryLoop()
            scope.launch { refreshLiveDayEntryCount(Instant.now()) }
            scope.launch {
                delay(STARTUP_ARCHIVE_DELAY_MS)
                runArchiveNowInternal(reason = "startup delayed")
            }

            initialized = true
        }
    }

    fun setLoggingEnabled(enabled: Boolean) {
        if (enabled && _state.value.archiveRootUri == null) {
            loggingEnabled = false
            _state.update {
                it.copy(
                    loggingEnabled = false,
                    lastArchiveMessage = "Choose archive folder before enabling location logging."
                )
            }
            return
        }
        loggingEnabled = enabled
        _state.update {
            it.copy(
                loggingEnabled = enabled,
                lastArchiveMessage = if (enabled) {
                    "Location logging enabled."
                } else {
                    "Location logging disabled."
                }
            )
        }
        if (!enabled) {
            scope.launch {
                writeThrottleMutex.withLock {
                    pendingEvent = null
                    cooldownJob?.cancel()
                    cooldownJob = null
                }
            }
        }
    }

    fun setArchiveTreeUri(uri: Uri?) {
        val repository = archiveRepository ?: return
        repository.setArchiveTreeUri(uri)
        if (uri == null) {
            loggingEnabled = false
        }
        _state.update {
            it.copy(
                loggingEnabled = if (uri == null) false else it.loggingEnabled,
                archiveRootUri = repository.getArchiveTreeUri(),
                pendingArchiveCount = repository.pendingArchiveCount(),
                lastArchiveMessage = if (uri == null) {
                    "Archive folder cleared. Location logging disabled."
                } else {
                    "Archive folder updated."
                },
                integrityMessage = if (uri == null) {
                    "Integrity check pending."
                } else {
                    "Archive updated. Run integrity check manually."
                }
            )
        }
    }

    fun getArchiveTreeUri(): Uri? {
        return archiveRepository?.getArchiveTreeUri()
    }

    suspend fun archiveNow(): Result<Int> {
        val repository = archiveRepository ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        val dayManager = dailyDbManager ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        val nowUtc = Instant.now()
        val liveDayCount = refreshLiveDayEntryCount(nowUtc) ?: 0L

        if (liveDayCount > 0L) {
            dayManager.checkpointCurrentDay(nowUtc)?.let { day ->
                repository.snapshotLiveDayToPending(day)
            }
        }

        return repository.archivePendingNow()
            .onSuccess { archivedCount ->
                _state.update {
                    it.copy(
                        pendingArchiveCount = repository.pendingArchiveCount(),
                        lastArchiveAtMs = System.currentTimeMillis(),
                        lastArchiveMessage = "Manual archive completed ($archivedCount file(s)).",
                        lastError = null
                    )
                }
            }
            .onFailure { throwable ->
                _state.update {
                    it.copy(
                        pendingArchiveCount = repository.pendingArchiveCount(),
                        lastArchiveAtMs = System.currentTimeMillis(),
                        lastArchiveMessage = "Manual archive failed.",
                        lastError = throwable.message ?: "Unknown archive error"
                    )
                }
            }
    }

    suspend fun exportMergedPlaintextDatabase(target: Uri): Result<Int> {
        val context = appContext ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        val repository = readRepository ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        val dayManager = dailyDbManager ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        val nowUtc = Instant.now()

        dayManager.checkpointCurrentDay(nowUtc)

        val exportResult = withContext(Dispatchers.IO) {
            runCatching {
                val endInclusiveMs = System.currentTimeMillis().plus(86_400_000L)
                val mergedRows = repository.queryRange(
                    startInclusiveMs = 0L,
                    endInclusiveMs = endInclusiveMs
                )

                val tempDb = File(context.cacheDir, "blackbox-merged-plaintext-export.db")
                runCatching { tempDb.delete() }
                runCatching { File(tempDb.parentFile, "${tempDb.name}-wal").delete() }
                runCatching { File(tempDb.parentFile, "${tempDb.name}-shm").delete() }

                writePlaintextMergedDb(file = tempDb, rows = mergedRows)

                context.contentResolver.openOutputStream(target, "w")?.use { output ->
                    tempDb.inputStream().use { input ->
                        input.copyTo(output)
                        output.flush()
                    }
                } ?: error("Failed to open export destination.")

                runCatching { tempDb.delete() }
                runCatching { File(tempDb.parentFile, "${tempDb.name}-wal").delete() }
                runCatching { File(tempDb.parentFile, "${tempDb.name}-shm").delete() }

                mergedRows.size
            }
        }

        return exportResult
            .onSuccess { exportedRows ->
                _state.update {
                    it.copy(
                        lastArchiveMessage = "Plaintext merged export completed ($exportedRows rows).",
                        lastError = null
                    )
                }
            }
            .onFailure { throwable ->
                _state.update {
                    it.copy(lastError = throwable.message ?: "Plaintext export failed")
                }
            }
    }

    suspend fun clearAllDatabases(): Result<ClearAllResult> {
        val repository = archiveRepository ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        val dayManager = dailyDbManager ?: return Result.failure(IllegalStateException("Persistence not initialized."))

        writeThrottleMutex.withLock {
            pendingEvent = null
            cooldownJob?.cancel()
            cooldownJob = null
        }

        val clearResult = runCatching {
            val localResult = dayManager.clearAllLocalDatabases()
            val archivedResult = repository.clearArchivedDatabases().getOrElse { throw it }
            val nowMs = System.currentTimeMillis()

            val combined = ClearAllResult(
                deletedLiveFiles = localResult.deletedLiveFiles,
                deletedPendingFiles = localResult.deletedPendingFiles,
                deletedArchivedFiles = archivedResult.deleted,
                success = localResult.success && archivedResult.deleted == archivedResult.attempted
            )

            _state.update {
                it.copy(
                    pendingArchiveCount = repository.pendingArchiveCount(),
                    liveDayEntryCount = 0L,
                    lastWriteAtMs = null,
                    lastArchiveAtMs = nowMs,
                    integrityTotalFiles = 0,
                    integritySucceededFiles = 0,
                    integrityFailedFiles = 0,
                    integrityLastCheckedAtMs = nowMs,
                    integrityMessage = "No archived database files found.",
                    integrityCheckRunning = false,
                    lastArchiveMessage = if (combined.success) {
                        "Clear all completed."
                    } else {
                        "Clear all completed with issues."
                    },
                    lastError = if (combined.success) null else "Some files could not be deleted."
                )
            }

            combined
        }

        return clearResult.onFailure { throwable ->
            _state.update {
                it.copy(lastError = throwable.message ?: "Clear all failed")
            }
        }
    }

    suspend fun exportKeyBundle(passphrase: CharArray, target: Uri): Result<Uri> {
        val manager = keyManager ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        return manager.export(passphrase = passphrase, target = target)
    }

    suspend fun importKeyBundle(passphrase: CharArray, source: Uri): Result<Unit> {
        val manager = keyManager ?: return Result.failure(IllegalStateException("Persistence not initialized."))
        return manager.import(passphrase = passphrase, source = source)
            .onSuccess {
                _state.update {
                    it.copy(
                        lastArchiveMessage = "Key bundle imported.",
                        lastError = null
                    )
                }
            }
            .onFailure { throwable ->
                _state.update {
                    it.copy(
                        lastError = throwable.message ?: "Key import failed"
                    )
                }
            }
    }

    suspend fun readRange(startInclusiveMs: Long, endInclusiveMs: Long): List<LocationSampleEntity> {
        val repository = readRepository ?: return emptyList()
        return repository.queryRange(startInclusiveMs, endInclusiveMs)
    }

    suspend fun readHistoryRange(startInclusiveMs: Long, endInclusiveMs: Long): List<LocationHistorySample> {
        val repository = readRepository ?: return emptyList()
        return repository.queryHistoryRange(startInclusiveMs, endInclusiveMs)
    }

    suspend fun getArchiveRecords(): List<ArchiveRecord> {
        return withContext(Dispatchers.IO) {
            archiveRepository?.getArchiveRecords().orEmpty()
        }
    }

    suspend fun runArchiveIntegrityCheck(): Result<ArchiveIntegrityResult> {
        val repository = archiveRepository ?: return Result.failure(IllegalStateException("Persistence not initialized."))

        _state.update {
            it.copy(
                integrityCheckRunning = true,
                integrityMessage = "Integrity check running..."
            )
        }

        return integrityCheckMutex.withLock {
            val result = repository.verifyArchivedIntegrity()
            val checkedAtMs = System.currentTimeMillis()

            result
                .onSuccess { integrity ->
                    _state.update {
                        it.copy(
                            integrityTotalFiles = integrity.totalFiles,
                            integritySucceededFiles = integrity.succeededFiles,
                            integrityFailedFiles = integrity.failedFiles,
                            integrityLastCheckedAtMs = checkedAtMs,
                            integrityMessage = integrity.detailMessage,
                            integrityCheckRunning = false
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            integrityLastCheckedAtMs = checkedAtMs,
                            integrityMessage = "Integrity check failed.",
                            integrityCheckRunning = false,
                            lastError = throwable.message ?: "Integrity check failed"
                        )
                    }
                }

            result
        }
    }

    private fun startEventCollector() {
        if (eventCollectorJob != null) {
            return
        }

        debugPersistLog { "Starting location event collector." }
        eventCollectorJob = scope.launch {
            LocationEngine.locationEvents.collect { event ->
                debugPersistLog {
                    "Location event collected provider=${event.provider} " +
                        "receivedAtMs=${event.receivedAtMs} acc=${event.accuracyM}"
                }
                handleLocationEvent(event)
            }
        }
    }

    private fun writePlaintextMergedDb(file: File, rows: List<LocationSampleEntity>) {
        if (file.exists()) {
            runCatching { file.delete() }
        }
        LocationDbPaths.ensureParentDir(file)

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS location_samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    received_at_ms INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    fix_time_ms INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    best_accuracy_m REAL NOT NULL,
                    worst_accuracy_m REAL NOT NULL,
                    samples_merged_count INTEGER NOT NULL,
                    altitude_m REAL,
                    speed_mps REAL,
                    bearing_deg REAL,
                    speed_accuracy_mps REAL,
                    bearing_accuracy_deg REAL,
                    engine_mode TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_location_samples_received_at_ms ON location_samples(received_at_ms)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_location_samples_fix_time_ms ON location_samples(fix_time_ms)"
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_location_samples_dedupe
                ON location_samples(
                    received_at_ms,
                    last_seen_at_ms,
                    fix_time_ms,
                    provider,
                    lat,
                    lon,
                    best_accuracy_m,
                    worst_accuracy_m,
                    samples_merged_count,
                    engine_mode
                )
                """.trimIndent()
            )

            db.transaction {
                rows.forEach { row ->
                    val values = ContentValues().apply {
                        put("received_at_ms", row.receivedAtMs)
                        put("last_seen_at_ms", row.lastSeenAtMs)
                        put("fix_time_ms", row.fixTimeMs)
                        put("provider", row.provider)
                        put("lat", row.lat)
                        put("lon", row.lon)
                        put("best_accuracy_m", row.bestAccuracyM)
                        put("worst_accuracy_m", row.worstAccuracyM)
                        put("samples_merged_count", row.samplesMergedCount)
                        put("altitude_m", row.altitudeM)
                        put("speed_mps", row.speedMps)
                        put("bearing_deg", row.bearingDeg)
                        put("speed_accuracy_mps", row.speedAccuracyMps)
                        put("bearing_accuracy_deg", row.bearingAccuracyDeg)
                        put("engine_mode", row.engineMode.name)
                    }
                    db.insertWithOnConflict(
                        "location_samples",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    private suspend fun handleLocationEvent(event: LocationSampleEvent) {
        if (!loggingEnabled) return
        if (event.accuracyM > MAX_PERSISTED_ACCURACY_M) {
            debugPersistLog {
                "Dropping location event due to low accuracy acc=${event.accuracyM}m > ${MAX_PERSISTED_ACCURACY_M}m."
            }
            return
        }
        var shouldPersistImmediately = false

        writeThrottleMutex.withLock {
            if (cooldownJob == null) {
                shouldPersistImmediately = true
                debugPersistLog { "Write throttle open; persisting immediately." }
                cooldownJob = scope.launch {
                    runCooldownLoop()
                }
            } else {
                pendingEvent = event
                debugPersistLog {
                    "Write throttled; queued pending event receivedAtMs=${event.receivedAtMs}."
                }
            }
        }

        if (shouldPersistImmediately) {
            persistEvent(event)
        }
    }

    private suspend fun runCooldownLoop() {
        while (true) {
            delay(MAX_WRITE_RATE_MS)

            val eventToPersist: LocationSampleEvent? = writeThrottleMutex.withLock {
                val pending = pendingEvent
                if (pending == null) {
                    cooldownJob = null
                    null
                } else {
                    pendingEvent = null
                    pending
                }
            }

            if (eventToPersist == null) {
                debugPersistLog { "Cooldown loop idle; stopping." }
                return
            }
            debugPersistLog {
                "Cooldown tick persisting queued event receivedAtMs=${eventToPersist.receivedAtMs}."
            }
            persistEvent(eventToPersist)
        }
    }

    private suspend fun persistEvent(event: LocationSampleEvent) {
        val writer = writeRepository ?: return
        debugPersistLog {
            "DB write start provider=${event.provider} receivedAtMs=${event.receivedAtMs}."
        }
        val result = runCatching {
            writer.ingest(event)
        }

        if (result.isSuccess) {
            val liveDayCount = refreshLiveDayEntryCount(Instant.ofEpochMilli(event.receivedAtMs))
            debugPersistLog {
                "DB write success receivedAtMs=${event.receivedAtMs} liveDayCount=$liveDayCount."
            }
            _state.update {
                it.copy(
                    liveDayEntryCount = liveDayCount ?: it.liveDayEntryCount,
                    totalPersistedWrites = it.totalPersistedWrites + 1,
                    lastWriteAtMs = System.currentTimeMillis(),
                    lastError = null
                )
            }
        } else {
            val throwable = result.exceptionOrNull()
            Log.e(
                PERSIST_DEBUG_TAG,
                "DB write failed receivedAtMs=${event.receivedAtMs}: ${throwable?.message ?: "unknown error"}",
                throwable
            )
            _state.update {
                it.copy(
                    lastError = throwable?.message ?: "Location write failed"
                )
            }
        }
    }

    private suspend fun refreshLiveDayEntryCount(nowUtc: Instant): Long? {
        val manager = dailyDbManager ?: return null
        return runCatching {
            manager.currentDayEntryCount(nowUtc)
        }.onSuccess { count ->
            debugPersistLog { "Live day entry recount success count=$count nowUtc=$nowUtc" }
        }.onFailure { throwable ->
            Log.e(
                PERSIST_DEBUG_TAG,
                "Live day entry recount failed: ${throwable.message ?: "unknown error"}",
                throwable
            )
            _state.update {
                it.copy(lastError = throwable.message ?: "Failed to read live entry count")
            }
        }.getOrNull()
    }

    private fun startArchiveRetryLoop() {
        if (archiveRetryJob != null) {
            return
        }

        archiveRetryJob = scope.launch {
            while (isActive) {
                delay(ARCHIVE_RETRY_INTERVAL_MS)
                runArchiveNowInternal(reason = "auto retry")
            }
        }
    }

    private suspend fun runArchiveNowInternal(reason: String) {
        val repository = archiveRepository ?: return
        val result = repository.archivePendingNow()

        result
            .onSuccess { count ->
                _state.update {
                    it.copy(
                        pendingArchiveCount = repository.pendingArchiveCount(),
                        lastArchiveAtMs = System.currentTimeMillis(),
                        lastArchiveMessage = "Archive $reason completed ($count file(s)).",
                        lastError = null
                    )
                }
            }
            .onFailure { throwable ->
                _state.update {
                    it.copy(
                        pendingArchiveCount = repository.pendingArchiveCount(),
                        lastArchiveAtMs = System.currentTimeMillis(),
                        lastArchiveMessage = "Archive $reason failed.",
                        lastError = throwable.message ?: "Archive failed"
                    )
                }
            }
    }

    private inline fun debugPersistLog(message: () -> String) {
        if (!ENABLE_VERBOSE_PERSIST_LOGS) return
        Log.d(PERSIST_DEBUG_TAG, message())
    }
}
