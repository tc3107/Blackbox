package com.example.blackbox.data.locationdb

import android.net.Uri

interface KeyBackupService {
    suspend fun export(passphrase: CharArray, target: Uri): Result<Uri>
    suspend fun import(passphrase: CharArray, source: Uri): Result<Unit>
}
