package com.example.blackbox.sharing

import android.content.Context
import kotlinx.serialization.json.Json

class SharingStateStore(context: Context) {
    private val storage = SharingSecureStorage(context)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    @Volatile
    private var cached: SharingStateSnapshot? = null

    fun read(): SharingStateSnapshot {
        return synchronized(lock) {
            ensureLoaded()
        }
    }

    fun replace(snapshot: SharingStateSnapshot): SharingStateSnapshot {
        return synchronized(lock) {
            persist(snapshot)
            cached = snapshot
            snapshot
        }
    }

    fun update(transform: (SharingStateSnapshot) -> SharingStateSnapshot): SharingStateSnapshot {
        return synchronized(lock) {
            val current = ensureLoaded()
            val updated = transform(current)
            persist(updated)
            cached = updated
            updated
        }
    }

    private fun ensureLoaded(): SharingStateSnapshot {
        cached?.let { return it }

        val snapshot = storage.readPlaintextState()
            ?.let { bytes ->
                runCatching {
                    json.decodeFromString<SharingStateSnapshot>(bytes.toString(Charsets.UTF_8))
                }.getOrNull()
            }
            ?.takeIf { it.schemaVersion == SHARING_SCHEMA_VERSION }
            ?: SharingStateSnapshot()

        cached = snapshot
        return snapshot
    }

    private fun persist(snapshot: SharingStateSnapshot) {
        val normalized = snapshot.copy(schemaVersion = SHARING_SCHEMA_VERSION)
        val payload = json.encodeToString(SharingStateSnapshot.serializer(), normalized)
        storage.writePlaintextState(payload.toByteArray(Charsets.UTF_8))
    }
}
