package com.example.blackbox.data.locationdb

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class LocationArchivePreferences(private val context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getArchiveTreeUri(): Uri? {
        val raw = preferences.getString(KEY_ARCHIVE_TREE_URI, null) ?: return null
        return runCatching { raw.toUri() }.getOrNull()
    }

    fun setArchiveTreeUri(uri: Uri?) {
        if (uri != null) {
            val flags = IntentFlags.READ_WRITE_URI_PERMISSION_FLAGS
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
            }
        }
        preferences.edit {
            putString(KEY_ARCHIVE_TREE_URI, uri?.toString())
        }
    }

    private object IntentFlags {
        const val READ_WRITE_URI_PERMISSION_FLAGS =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private companion object {
        const val PREFS_NAME = "location_archive_preferences"
        const val KEY_ARCHIVE_TREE_URI = "archive_tree_uri"
    }
}
