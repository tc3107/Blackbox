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

    private fun enqueueDayForArchive(day: LocalDate) {
        val liveDbFile = LocationDbPaths.liveDbFile(filesDir, day)
        if (!liveDbFile.exists()) {
            return
        }

        val pendingDbFile = LocationDbPaths.pendingArchiveFile(filesDir, day)
        LocationDbPaths.ensureParentDir(pendingDbFile)

        moveFile(source = liveDbFile, target = pendingDbFile)
        moveSidecarFiles(sourceDbFile = liveDbFile, targetDbFile = pendingDbFile)
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

            val relativePath = LocationDbPaths.relativePathForDay(day)
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

    private fun moveFile(source: File, target: File) {
        if (!source.exists()) {
            return
        }

        if (target.exists()) {
            runCatching { target.delete() }
        }

        val moved = source.renameTo(target)
        if (moved) {
            return
        }

        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        runCatching { source.delete() }
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
                safRelativePath = LocationDbPaths.relativePathForDay(day),
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
                safRelativePath = LocationDbPaths.relativePathForDay(day),
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

    private data class ArchiveBatchResult(
        val archivedCount: Int
    )

    companion object {
        private const val SQLITE_MIME_TYPE = "application/vnd.sqlite3"
    }
}
