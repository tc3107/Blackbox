package com.example.blackbox.data.locationdb

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.blackbox.location.LocationEngine
import com.example.blackbox.location.LocationSampleEvent
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

private const val MAX_WRITE_RATE_MS = 1_000L
private const val ARCHIVE_RETRY_INTERVAL_MS = 60_000L
private const val PERSIST_DEBUG_TAG = "BlackboxPersistDebug"

data class LocationPersistenceState(
    val initialized: Boolean = false,
    val archiveRootUri: Uri? = null,
    val pendingArchiveCount: Int = 0,
    val liveDayEntryCount: Long = 0L,
    val totalPersistedWrites: Long = 0L,
    val lastWriteAtMs: Long? = null,
    val lastArchiveAtMs: Long? = null,
    val lastArchiveMessage: String = "Archive idle.",
    val lastError: String? = null
)

object LocationPersistenceController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeThrottleMutex = Mutex()

    private val _state = MutableStateFlow(LocationPersistenceState())
    val state: StateFlow<LocationPersistenceState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    private var keyManager: LocationDbKeyManager? = null
    private var archiveRepository: LocationArchiveRepository? = null
    private var dailyDbManager: DailyRoomDbManager? = null
    private var writeRepository: LocationWriteRepository? = null
    private var readRepository: LocationReadRepository? = null

    private var eventCollectorJob: Job? = null
    private var cooldownJob: Job? = null
    private var pendingEvent: LocationSampleEvent? = null
    private var archiveRetryJob: Job? = null

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
            readRepository = MergedLocationReadRepository(
                context = appContext,
                keyManager = localKeyManager,
                archiveRepository = localArchiveRepository
            )

            _state.value = _state.value.copy(
                initialized = true,
                archiveRootUri = localArchiveRepository.getArchiveTreeUri(),
                pendingArchiveCount = localArchiveRepository.pendingArchiveCount(),
                lastArchiveMessage = "Persistence initialized."
            )

            startEventCollector()
            startArchiveRetryLoop()
            scope.launch { refreshLiveDayEntryCount(Instant.now()) }
            scope.launch { runArchiveNowInternal(reason = "startup") }

            initialized = true
        }
    }

    fun setArchiveTreeUri(uri: Uri?) {
        val repository = archiveRepository ?: return
        repository.setArchiveTreeUri(uri)
        _state.update {
            it.copy(
                archiveRootUri = repository.getArchiveTreeUri(),
                pendingArchiveCount = repository.pendingArchiveCount(),
                lastArchiveMessage = if (uri == null) {
                    "Archive folder cleared."
                } else {
                    "Archive folder updated."
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

    fun getArchiveRecords(): List<ArchiveRecord> {
        return archiveRepository?.getArchiveRecords().orEmpty()
    }

    private fun startEventCollector() {
        if (eventCollectorJob != null) {
            return
        }

        Log.d(PERSIST_DEBUG_TAG, "Starting location event collector.")
        eventCollectorJob = scope.launch {
            LocationEngine.locationEvents.collect { event ->
                Log.d(
                    PERSIST_DEBUG_TAG,
                    "Location event collected provider=${event.provider} " +
                        "receivedAtMs=${event.receivedAtMs} acc=${event.accuracyM}"
                )
                handleLocationEvent(event)
            }
        }
    }

    private suspend fun handleLocationEvent(event: LocationSampleEvent) {
        var shouldPersistImmediately = false

        writeThrottleMutex.withLock {
            if (cooldownJob == null) {
                shouldPersistImmediately = true
                Log.d(PERSIST_DEBUG_TAG, "Write throttle open; persisting immediately.")
                cooldownJob = scope.launch {
                    runCooldownLoop()
                }
            } else {
                pendingEvent = event
                Log.d(
                    PERSIST_DEBUG_TAG,
                    "Write throttled; queued pending event receivedAtMs=${event.receivedAtMs}."
                )
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
                Log.d(PERSIST_DEBUG_TAG, "Cooldown loop idle; stopping.")
                return
            }
            Log.d(
                PERSIST_DEBUG_TAG,
                "Cooldown tick persisting queued event receivedAtMs=${eventToPersist.receivedAtMs}."
            )
            persistEvent(eventToPersist)
        }
    }

    private suspend fun persistEvent(event: LocationSampleEvent) {
        val writer = writeRepository ?: return
        Log.d(
            PERSIST_DEBUG_TAG,
            "DB write start provider=${event.provider} receivedAtMs=${event.receivedAtMs}."
        )
        val result = runCatching {
            writer.ingest(event)
        }

        if (result.isSuccess) {
            val liveDayCount = refreshLiveDayEntryCount(Instant.ofEpochMilli(event.receivedAtMs))
            Log.d(
                PERSIST_DEBUG_TAG,
                "DB write success receivedAtMs=${event.receivedAtMs} liveDayCount=$liveDayCount."
            )
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
            Log.d(PERSIST_DEBUG_TAG, "Live day entry recount success count=$count nowUtc=$nowUtc")
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
}
