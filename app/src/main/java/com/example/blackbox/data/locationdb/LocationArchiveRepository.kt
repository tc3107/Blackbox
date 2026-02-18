package com.example.blackbox.data.locationdb

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class LocationArchiveRepository(
    context: Context,
    private val keyManager: LocationDbKeyManager? = null
) : ArchiveRepository {
    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val archivePreferences = LocationArchivePreferences(appContext)

    override suspend fun queueAndArchive(day: LocalDate): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                enqueueDayForArchive(day)
                archivePendingInternal()
                Unit
            }
        }
    }

    suspend fun archivePendingNow(): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                archivePendingInternal().archivedCount
            }
        }
    }

    fun setArchiveTreeUri(uri: Uri?) {
        archivePreferences.setArchiveTreeUri(uri)
    }

    fun getArchiveTreeUri(): Uri? {
        return archivePreferences.getArchiveTreeUri()
    }

    fun getArchiveRecords(): List<ArchiveRecord> {
        val todayUtc = LocationDbPaths.utcToday()
        val records = mutableListOf<ArchiveRecord>()
        records += listLiveCandidateRecords(todayUtc)
        records += listPendingRecords()
        records += listArchivedRecordsFromSaf()
        return records.sortedByDescending { it.dayUtc }
    }

    fun pendingArchiveCount(): Int {
        val todayUtc = LocationDbPaths.utcToday()
        val liveCandidates = listLiveArchiveCandidates(todayUtc)
        val pending = listPendingArchiveFiles()
        return (liveCandidates.size + pending.size)
    }

    suspend fun verifyArchivedIntegrity(): Result<ArchiveIntegrityResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val archiveRoot = archivePreferences.getArchiveTreeUri()
                    ?: return@runCatching ArchiveIntegrityResult(
                        totalFiles = 0,
                        succeededFiles = 0,
                        failedFiles = 0,
                        detailMessage = "Archive folder is not configured."
                    )

                val rootDoc = DocumentFile.fromTreeUri(appContext, archiveRoot)
                    ?: return@runCatching ArchiveIntegrityResult(
                        totalFiles = 0,
                        succeededFiles = 0,
                        failedFiles = 0,
                        detailMessage = "Archive folder could not be opened."
                    )

                val archiveFiles = listArchivedDbFiles(rootDoc)
                if (archiveFiles.isEmpty()) {
                    return@runCatching ArchiveIntegrityResult(
                        totalFiles = 0,
                        succeededFiles = 0,
                        failedFiles = 0,
                        detailMessage = "No archived database files found."
                    )
                }

                val cacheRoot = File(appContext.cacheDir, "archive_integrity_check")
                if (!cacheRoot.exists()) {
                    cacheRoot.mkdirs()
                }

                var succeeded = 0
                var failed = 0

                archiveFiles.forEachIndexed { index, file ->
                    val cacheFile = File(cacheRoot, "arch-check-$index.db")
                    runCatching { cacheFile.delete() }
                    runCatching { File(cacheFile.parentFile, "${cacheFile.name}-wal").delete() }
                    runCatching { File(cacheFile.parentFile, "${cacheFile.name}-shm").delete() }

                    val copied = runCatching {
                        appContext.contentResolver.openInputStream(file.uri)?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                                output.flush()
                            }
                        } ?: error("Could not open archived file stream.")
                    }.isSuccess

                    if (!copied || !cacheFile.exists()) {
                        failed += 1
                        return@forEachIndexed
                    }

                    if (canOpenWithKnownKeys(cacheFile)) {
                        succeeded += 1
                    } else {
                        failed += 1
                    }

                    runCatching { cacheFile.delete() }
                    runCatching { File(cacheFile.parentFile, "${cacheFile.name}-wal").delete() }
                    runCatching { File(cacheFile.parentFile, "${cacheFile.name}-shm").delete() }
                }

                runCatching { cacheRoot.deleteRecursively() }

                ArchiveIntegrityResult(
                    totalFiles = archiveFiles.size,
                    succeededFiles = succeeded,
                    failedFiles = failed,
                    detailMessage = if (failed == 0) {
                        "Integrity check passed for all archived files."
                    } else {
                        "Integrity check failed for $failed file(s)."
                    }
                )
            }
        }
    }

    private fun enqueueDayForArchive(day: LocalDate) {
        val liveDbFile = LocationDbPaths.liveDbFile(filesDir, day)
        if (!liveDbFile.exists()) {
            return
        }

        val pendingDbFile = uniquePendingArchiveTarget(day = day, preferredFileName = liveDbFile.name)
        LocationDbPaths.ensureParentDir(pendingDbFile)

        moveFile(source = liveDbFile, target = pendingDbFile)
        moveSidecarFiles(sourceDbFile = liveDbFile, targetDbFile = pendingDbFile)
    }

    fun snapshotLiveDayToPending(day: LocalDate): Boolean {
        val liveDbFile = LocationDbPaths.liveDbFile(filesDir, day)
        if (!liveDbFile.exists()) {
            return false
        }

        val pendingDbFile = uniquePendingArchiveTarget(
            day = day,
            preferredFileName = LocationDbPaths.fileNameForSnapshot(day, snapshotId = System.currentTimeMillis().toString())
        )
        LocationDbPaths.ensureParentDir(pendingDbFile)

        moveFile(source = liveDbFile, target = pendingDbFile, deleteSource = false)
        copySidecarFiles(sourceDbFile = liveDbFile, targetDbFile = pendingDbFile)
        return true
    }

    private fun archivePendingInternal(): ArchiveBatchResult {
        enqueueStaleLiveDays(todayUtc = LocationDbPaths.utcToday())

        val pendingFiles = listPendingArchiveFiles()
        if (pendingFiles.isEmpty()) {
            return ArchiveBatchResult(archivedCount = 0)
        }

        val archiveTreeUri = archivePreferences.getArchiveTreeUri() ?: return ArchiveBatchResult(archivedCount = 0)

        var archivedCount = 0
        pendingFiles.forEach { localFile ->
            val day = LocationDbPaths.parseUtcDayFromDbFile(localFile)
            if (day == null) {
                runCatching { localFile.delete() }
                return@forEach
            }

            val relativePath = LocationDbPaths.relativePathForFile(dayUtc = day, fileName = localFile.name)
            val archiveResult = runCatching {
                checkpointPendingDbIfNeeded(localFile)
                copyFileToSaf(
                    source = localFile,
                    archiveTreeUri = archiveTreeUri,
                    relativePath = relativePath
                )
            }

            if (archiveResult.isSuccess) {
                runCatching { localFile.delete() }
                removeSidecarFiles(localFile)
                archivedCount += 1
            }
        }

        return ArchiveBatchResult(archivedCount = archivedCount)
    }

    private fun enqueueStaleLiveDays(todayUtc: LocalDate) {
        listLiveArchiveCandidates(todayUtc).forEach { candidate ->
            val day = LocationDbPaths.parseUtcDayFromDbFile(candidate) ?: return@forEach
            enqueueDayForArchive(day)
        }
    }

    private fun listLiveArchiveCandidates(todayUtc: LocalDate): List<File> {
        val root = LocationDbPaths.liveRoot(filesDir)
        if (!root.exists()) {
            return emptyList()
        }

        return root.walkTopDown()
            .filter { it.isFile && it.extension == LocationDbPaths.DB_EXTENSION }
            .filter { file ->
                val day = LocationDbPaths.parseUtcDayFromDbFile(file)
                day != null && day.isBefore(todayUtc)
            }
            .sortedBy { it.absolutePath }
            .toList()
    }

    private fun listPendingArchiveFiles(): List<File> {
        val root = LocationDbPaths.pendingArchiveRoot(filesDir)
        if (!root.exists()) {
            return emptyList()
        }

        return root.walkTopDown()
            .filter { it.isFile && it.extension == LocationDbPaths.DB_EXTENSION }
            .sortedBy { it.absolutePath }
            .toList()
    }

    private fun copyFileToSaf(source: File, archiveTreeUri: Uri, relativePath: String) {
        val tree = DocumentFile.fromTreeUri(appContext, archiveTreeUri)
            ?: error("Failed to resolve archive folder.")

        val pathParts = relativePath.split('/')
        require(pathParts.isNotEmpty()) { "Invalid archive path '$relativePath'." }

        var currentDir = tree
        pathParts.dropLast(1).forEach { segment ->
            val nextDir = currentDir.findFile(segment)?.takeIf { it.isDirectory }
                ?: currentDir.createDirectory(segment)
                ?: error("Failed to create archive directory '$segment'.")
            currentDir = nextDir
        }

        val fileName = pathParts.last()
        currentDir.findFile(fileName)?.let { existing ->
            runCatching { existing.delete() }
        }

        val targetFile = currentDir.createFile(SQLITE_MIME_TYPE, fileName)
            ?: error("Failed to create archive file '$fileName'.")

        appContext.contentResolver.openOutputStream(targetFile.uri, "w")?.use { output ->
            source.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        } ?: error("Failed to open archive output stream.")
    }

    private fun moveFile(source: File, target: File, deleteSource: Boolean = true) {
        if (!source.exists()) {
            return
        }

        if (target.exists()) {
            runCatching { target.delete() }
        }

        val moved = deleteSource && source.renameTo(target)
        if (moved) {
            return
        }

        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        if (deleteSource) {
            runCatching { source.delete() }
        }
    }

    private fun removeSidecarFiles(dbFile: File) {
        val wal = File(dbFile.parentFile, "${dbFile.name}-wal")
        val shm = File(dbFile.parentFile, "${dbFile.name}-shm")
        runCatching { wal.delete() }
        runCatching { shm.delete() }
    }

    private fun moveSidecarFiles(sourceDbFile: File, targetDbFile: File) {
        val sourceWal = File(sourceDbFile.parentFile, "${sourceDbFile.name}-wal")
        val sourceShm = File(sourceDbFile.parentFile, "${sourceDbFile.name}-shm")
        val targetWal = File(targetDbFile.parentFile, "${targetDbFile.name}-wal")
        val targetShm = File(targetDbFile.parentFile, "${targetDbFile.name}-shm")

        if (sourceWal.exists()) {
            moveFile(source = sourceWal, target = targetWal)
        }
        if (sourceShm.exists()) {
            moveFile(source = sourceShm, target = targetShm)
        }
    }

    private fun copySidecarFiles(sourceDbFile: File, targetDbFile: File) {
        val sourceWal = File(sourceDbFile.parentFile, "${sourceDbFile.name}-wal")
        val sourceShm = File(sourceDbFile.parentFile, "${sourceDbFile.name}-shm")
        val targetWal = File(targetDbFile.parentFile, "${targetDbFile.name}-wal")
        val targetShm = File(targetDbFile.parentFile, "${targetDbFile.name}-shm")

        if (sourceWal.exists()) {
            moveFile(source = sourceWal, target = targetWal, deleteSource = false)
        }
        if (sourceShm.exists()) {
            moveFile(source = sourceShm, target = targetShm, deleteSource = false)
        }
    }

    private fun checkpointPendingDbIfNeeded(dbFile: File) {
        val walFile = File(dbFile.parentFile, "${dbFile.name}-wal")
        val shmFile = File(dbFile.parentFile, "${dbFile.name}-shm")
        if (!walFile.exists() && !shmFile.exists()) {
            return
        }

        val manager = keyManager ?: return
        val keys = manager.allKeys()

        keys.forEach { key ->
            val db = runCatching {
                Room.databaseBuilder(appContext, BlackboxDayDb::class.java, dbFile.absolutePath)
                    .openHelperFactory(SupportOpenHelperFactory(key.keyBytes.copyOf()))
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            }.getOrNull() ?: return@forEach

            val success = runCatching {
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { }
            }.isSuccess
            runCatching { db.close() }
            if (success) {
                return
            }
        }
    }

    private fun listPendingRecords(): List<ArchiveRecord> {
        return listPendingArchiveFiles().mapNotNull { file ->
            val day = LocationDbPaths.parseUtcDayFromDbFile(file) ?: return@mapNotNull null
            ArchiveRecord(
                dayUtc = day,
                localPath = file.absolutePath,
                safRelativePath = LocationDbPaths.relativePathForFile(dayUtc = day, fileName = file.name),
                status = ArchiveStatus.Pending,
                retryCount = 0,
                lastError = null,
                updatedAtMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
    }

    private fun listLiveCandidateRecords(todayUtc: LocalDate): List<ArchiveRecord> {
        return listLiveArchiveCandidates(todayUtc).mapNotNull { file ->
            val day = LocationDbPaths.parseUtcDayFromDbFile(file) ?: return@mapNotNull null
            ArchiveRecord(
                dayUtc = day,
                localPath = file.absolutePath,
                safRelativePath = LocationDbPaths.relativePathForFile(dayUtc = day, fileName = file.name),
                status = ArchiveStatus.Pending,
                retryCount = 0,
                lastError = null,
                updatedAtMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
    }

    private fun listArchivedRecordsFromSaf(): List<ArchiveRecord> {
        val archiveRoot = archivePreferences.getArchiveTreeUri() ?: return emptyList()
        val rootDoc = DocumentFile.fromTreeUri(appContext, archiveRoot) ?: return emptyList()
        val now = System.currentTimeMillis()

        val output = mutableListOf<ArchiveRecord>()
        walkSaf(rootDoc, currentPath = "") { file, relativePath ->
            if (!file.isFile) {
                return@walkSaf
            }
            val day = file.name?.let { LocationDbPaths.parseUtcDayFromDbFile(File(it)) } ?: return@walkSaf
            output += ArchiveRecord(
                dayUtc = day,
                localPath = file.uri.toString(),
                safRelativePath = relativePath,
                status = ArchiveStatus.Archived,
                retryCount = 0,
                lastError = null,
                updatedAtMs = now
            )
        }
        return output
    }

    private fun walkSaf(
        root: DocumentFile,
        currentPath: String,
        onFile: (file: DocumentFile, relativePath: String) -> Unit
    ) {
        val children = runCatching { root.listFiles() }.getOrDefault(emptyArray())
        children.forEach { child ->
            val childName = child.name ?: return@forEach
            val nextPath = if (currentPath.isBlank()) childName else "$currentPath/$childName"
            if (child.isDirectory) {
                walkSaf(child, nextPath, onFile)
            } else {
                onFile(child, nextPath)
            }
        }
    }

    private fun listArchivedDbFiles(rootDoc: DocumentFile): List<DocumentFile> {
        val output = mutableListOf<DocumentFile>()
        walkSaf(rootDoc, currentPath = "") { file, _ ->
            if (!file.isFile) {
                return@walkSaf
            }
            val name = file.name ?: return@walkSaf
            if (name.endsWith(".${LocationDbPaths.DB_EXTENSION}", ignoreCase = true)) {
                output += file
            }
        }
        return output.sortedBy { it.uri.toString() }
    }

    private fun canOpenWithKnownKeys(dbFile: File): Boolean {
        val manager = keyManager ?: return false
        val keys = manager.allKeys()
        if (keys.isEmpty()) {
            return false
        }

        keys.forEach { key ->
            val db = runCatching {
                Room.databaseBuilder(appContext, BlackboxDayDb::class.java, dbFile.absolutePath)
                    .openHelperFactory(SupportOpenHelperFactory(key.keyBytes.copyOf()))
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            }.getOrNull() ?: return@forEach

            val opened = runCatching {
                db.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM sqlite_master")
                    .use { }
            }.isSuccess
            runCatching { db.close() }
            if (opened) {
                return true
            }
        }
        return false
    }

    private data class ArchiveBatchResult(
        val archivedCount: Int
    )

    private fun uniquePendingArchiveTarget(day: LocalDate, preferredFileName: String): File {
        val year = day.year.toString()
        val month = String.format(java.util.Locale.US, "%02d", day.monthValue)
        val baseDir = File(LocationDbPaths.pendingArchiveRoot(filesDir), "$year/$month")
        var candidate = File(baseDir, preferredFileName)
        if (!candidate.exists()) {
            return candidate
        }

        val extension = ".${LocationDbPaths.DB_EXTENSION}"
        val stem = preferredFileName.removeSuffix(extension)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(baseDir, "$stem-copy$suffix$extension")
            suffix += 1
        }
        return candidate
    }

    companion object {
        private const val SQLITE_MIME_TYPE = "application/vnd.sqlite3"
    }
}

data class ArchiveIntegrityResult(
    val totalFiles: Int,
    val succeededFiles: Int,
    val failedFiles: Int,
    val detailMessage: String
)
