package com.example.blackbox.data.locationdb

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.blackbox.BuildConfig
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DbKeyMaterial(
    val keyId: String,
    val keyBytes: ByteArray,
    val createdAtMs: Long
)

class LocationDbKeyManager(context: Context) : KeyBackupService {
    private val appContext = context.applicationContext
    private val keyFile = File(appContext.filesDir, KEY_FILE_RELATIVE_PATH)
    private val keyFileLock = Any()

    @Volatile
    private var cachedKeyRing: KeyRing? = null

    fun activeKey(): DbKeyMaterial {
        return synchronized(keyFileLock) {
            val active = ensureLoaded().activeKey
            active.copy(keyBytes = active.keyBytes.copyOf())
        }
    }

    fun allKeys(): List<DbKeyMaterial> {
        return synchronized(keyFileLock) {
            ensureLoaded().allKeys
                .map { it.copy(keyBytes = it.keyBytes.copyOf()) }
        }
    }

    override suspend fun export(passphrase: CharArray, target: Uri): Result<Uri> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = synchronized(keyFileLock) {
                    val keyRing = ensureLoaded()
                    buildExportPayloadJson(keyRing).toString().toByteArray(Charsets.UTF_8)
                }
                val bundleJson = encryptBundlePayload(passphrase = passphrase, payload = payload)
                appContext.contentResolver.openOutputStream(target, "w")?.use { output ->
                    output.write(bundleJson.toString().toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: error("Could not open export target for writing.")
                target
            }
        }
    }

    override suspend fun import(passphrase: CharArray, source: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val rawJson = appContext.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not open key bundle for reading.")
                val payload = decryptBundlePayload(passphrase = passphrase, bundle = JSONObject(rawJson))
                val imported = parseExportPayloadJson(JSONObject(payload.toString(Charsets.UTF_8)))

                synchronized(keyFileLock) {
                    val existing = ensureLoaded()
                    val mergedById = linkedMapOf<String, DbKeyMaterial>()
                    existing.allKeys.forEach { mergedById[it.keyId] = it }
                    imported.allKeys.forEach { importedKey ->
                        val existingKey = mergedById[importedKey.keyId]
                        if (existingKey == null) {
                            mergedById[importedKey.keyId] = importedKey
                        } else if (!MessageDigest.isEqual(existingKey.keyBytes, importedKey.keyBytes)) {
                            error(
                                "Key ID collision detected for '${importedKey.keyId}'. " +
                                    "Bundle is inconsistent with local key material."
                            )
                        }
                    }

                    val mergedActive = existing.activeKey.keyId
                        .takeIf { mergedById.containsKey(it) }
                        ?: imported.activeKey.keyId

                    val mergedRing = KeyRing(
                        activeKey = mergedById.getValue(mergedActive),
                        allKeys = mergedById.values.toList()
                    )
                    persistKeyRing(mergedRing)
                    cachedKeyRing = mergedRing
                }
            }
        }
    }

    private fun ensureLoaded(): KeyRing {
        cachedKeyRing?.let { return it }

        val keyRing = if (!keyFile.exists()) {
            val generated = generateInitialKeyRing()
            persistKeyRing(generated)
            generated
        } else {
            loadKeyRingFromDisk()
        }

        cachedKeyRing = keyRing
        return keyRing
    }

    private fun generateInitialKeyRing(): KeyRing {
        val keyId = UUID.randomUUID().toString()
        val rawKey = ByteArray(DB_KEY_SIZE_BYTES)
        secureRandom.nextBytes(rawKey)
        val key = DbKeyMaterial(
            keyId = keyId,
            keyBytes = rawKey,
            createdAtMs = System.currentTimeMillis()
        )
        return KeyRing(activeKey = key, allKeys = listOf(key))
    }

    private fun loadKeyRingFromDisk(): KeyRing {
        val json = JSONObject(keyFile.readText())
        val keysArray = json.optJSONArray(JSON_KEYS) ?: JSONArray()
        val keys = buildList {
            for (index in 0 until keysArray.length()) {
                val entry = keysArray.getJSONObject(index)
                val keyId = entry.getString(JSON_KEY_ID)
                val createdAtMs = entry.optLong(JSON_CREATED_AT_MS, System.currentTimeMillis())
                val iv = entry.getString(JSON_WRAPPED_IV_B64).base64Decode()
                val ciphertext = entry.getString(JSON_WRAPPED_KEY_B64).base64Decode()
                val raw = unwrapWithKeystore(iv = iv, ciphertext = ciphertext)
                add(
                    DbKeyMaterial(
                        keyId = keyId,
                        keyBytes = raw,
                        createdAtMs = createdAtMs
                    )
                )
            }
        }

        require(keys.isNotEmpty()) { "No keys found in local key store." }

        val activeKeyId = json.optString(JSON_ACTIVE_KEY_ID)
        val active = keys.firstOrNull { it.keyId == activeKeyId } ?: keys.first()
        return KeyRing(activeKey = active, allKeys = keys)
    }

    private fun persistKeyRing(keyRing: KeyRing) {
        LocationDbPaths.ensureParentDir(keyFile)
        val keysJson = JSONArray()

        keyRing.allKeys.forEach { keyMaterial ->
            val wrapped = wrapWithKeystore(keyMaterial.keyBytes)
            val entry = JSONObject()
                .put(JSON_KEY_ID, keyMaterial.keyId)
                .put(JSON_CREATED_AT_MS, keyMaterial.createdAtMs)
                .put(JSON_WRAPPED_IV_B64, wrapped.iv.base64Encode())
                .put(JSON_WRAPPED_KEY_B64, wrapped.ciphertext.base64Encode())
            keysJson.put(entry)
        }

        val root = JSONObject()
            .put(JSON_VERSION, LOCAL_STATE_VERSION)
            .put(JSON_ACTIVE_KEY_ID, keyRing.activeKey.keyId)
            .put(JSON_KEYS, keysJson)

        val tempFile = File(keyFile.parentFile, "${keyFile.name}.tmp")
        tempFile.writeText(root.toString())
        if (!tempFile.renameTo(keyFile)) {
            error("Failed to persist key file atomically.")
        }
    }

    private fun buildExportPayloadJson(keyRing: KeyRing): JSONObject {
        val keysJson = JSONArray()
        keyRing.allKeys.forEach { key ->
            keysJson.put(
                JSONObject()
                    .put(JSON_KEY_ID, key.keyId)
                    .put(JSON_CREATED_AT_MS, key.createdAtMs)
                    .put(JSON_KEY_FINGERPRINT_SHA256, key.keyBytes.sha256Hex())
                    .put(JSON_RAW_KEY_B64, key.keyBytes.base64Encode())
            )
        }
        return JSONObject()
            .put(JSON_VERSION, EXPORT_PAYLOAD_VERSION)
            .put(JSON_CREATED_AT_MS, System.currentTimeMillis())
            .put(JSON_SOURCE_PACKAGE_NAME, appContext.packageName)
            .put(JSON_SOURCE_APP_VERSION, appVersionName())
            .put(JSON_SOURCE_DEVICE_LABEL, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put(JSON_KEY_COUNT, keyRing.allKeys.size)
            .put(JSON_ACTIVE_KEY_ID, keyRing.activeKey.keyId)
            .put(JSON_KEYS, keysJson)
    }

    private fun parseExportPayloadJson(payload: JSONObject): KeyRing {
        require(payload.optInt(JSON_VERSION) == EXPORT_PAYLOAD_VERSION) {
            "Unsupported key payload version."
        }

        val keysJson = payload.optJSONArray(JSON_KEYS) ?: JSONArray()
        val keys = buildList {
            for (index in 0 until keysJson.length()) {
                val entry = keysJson.getJSONObject(index)
                add(
                    entry.getString(JSON_RAW_KEY_B64).base64Decode().let { raw ->
                        val fingerprint = entry.optString(JSON_KEY_FINGERPRINT_SHA256)
                        if (fingerprint.isNotBlank() && !fingerprint.equals(raw.sha256Hex(), ignoreCase = true)) {
                            error("Key fingerprint mismatch in imported bundle.")
                        }
                        DbKeyMaterial(
                            keyId = entry.getString(JSON_KEY_ID),
                            keyBytes = raw,
                            createdAtMs = entry.optLong(JSON_CREATED_AT_MS, System.currentTimeMillis())
                        )
                    }
                )
            }
        }

        require(keys.isNotEmpty()) { "Imported bundle does not contain any key material." }

        val importedActiveKey = payload.optString(JSON_ACTIVE_KEY_ID)
        val active = keys.firstOrNull { it.keyId == importedActiveKey } ?: keys.first()
        return KeyRing(activeKey = active, allKeys = keys)
    }

    private fun encryptBundlePayload(passphrase: CharArray, payload: ByteArray): JSONObject {
        val salt = ByteArray(PBKDF2_SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(AES_GCM_NONCE_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveBundleKey(passphrase = passphrase, salt = salt, iterations = PBKDF2_ITERATIONS)
        val ciphertext = aesGcmEncrypt(key = key, nonce = nonce, plaintext = payload, aad = bundleAad())
        return JSONObject()
            .put(JSON_BUNDLE_VERSION, KEY_BUNDLE_VERSION)
            .put(JSON_KDF, KDF_NAME)
            .put(JSON_ITERATIONS, PBKDF2_ITERATIONS)
            .put(JSON_SALT_B64, salt.base64Encode())
            .put(JSON_NONCE_B64, nonce.base64Encode())
            .put(JSON_CIPHERTEXT_B64, ciphertext.base64Encode())
            .put(JSON_CREATED_AT_MS, System.currentTimeMillis())
    }

    private fun decryptBundlePayload(passphrase: CharArray, bundle: JSONObject): ByteArray {
        val version = bundle.optString(JSON_BUNDLE_VERSION)
        require(version == KEY_BUNDLE_VERSION) { "Unsupported key bundle format: $version" }

        val iterations = bundle.optInt(JSON_ITERATIONS)
        require(iterations > 0) { "Invalid key bundle iteration count." }

        val salt = bundle.getString(JSON_SALT_B64).base64Decode()
        val nonce = bundle.getString(JSON_NONCE_B64).base64Decode()
        val ciphertext = bundle.getString(JSON_CIPHERTEXT_B64).base64Decode()

        val key = deriveBundleKey(passphrase = passphrase, salt = salt, iterations = iterations)
        return aesGcmDecrypt(key = key, nonce = nonce, ciphertext = ciphertext, aad = bundleAad())
    }

    private fun deriveBundleKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val keyFactory = SecretKeyFactory.getInstance(KDF_NAME)
        val encoded = keyFactory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(encoded, AES_ALGORITHM)
    }

    private fun aesGcmEncrypt(
        key: SecretKeySpec,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: SecretKeySpec,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun wrapWithKeystore(rawKey: ByteArray): WrappedKey {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val ciphertext = cipher.doFinal(rawKey)
        return WrappedKey(iv = cipher.iv, ciphertext = ciphertext)
    }

    private fun unwrapWithKeystore(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            WRAPPING_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private data class WrappedKey(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    private data class KeyRing(
        val activeKey: DbKeyMaterial,
        val allKeys: List<DbKeyMaterial>
    )

    private fun ByteArray.base64Encode(): String {
        return Base64.encodeToString(this, Base64.NO_WRAP)
    }

    private fun String.base64Decode(): ByteArray {
        return Base64.decode(this, Base64.DEFAULT)
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun bundleAad(): ByteArray {
        return "$KEY_BUNDLE_VERSION|${appContext.packageName}".toByteArray(Charsets.UTF_8)
    }

    private fun appVersionName(): String {
        return runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    companion object {
        private const val KEY_FILE_RELATIVE_PATH = "location/keys/key_store_v1.json"
        private const val WRAPPING_KEY_ALIAS = "blackbox_location_db_wrapping_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DB_KEY_SIZE_BYTES = 32

        private val LOCAL_STATE_VERSION = BuildConfig.LOCATION_DB_LOCAL_STATE_VERSION
        private val EXPORT_PAYLOAD_VERSION = BuildConfig.LOCATION_DB_EXPORT_PAYLOAD_VERSION
        private val KEY_BUNDLE_VERSION = BuildConfig.LOCATION_DB_BUNDLE_VERSION

        private const val JSON_VERSION = "version"
        private const val JSON_ACTIVE_KEY_ID = "activeKeyId"
        private const val JSON_KEYS = "keys"
        private const val JSON_KEY_ID = "keyId"
        private const val JSON_CREATED_AT_MS = "createdAtMs"
        private const val JSON_KEY_FINGERPRINT_SHA256 = "keyFingerprintSha256"
        private const val JSON_SOURCE_PACKAGE_NAME = "sourcePackageName"
        private const val JSON_SOURCE_APP_VERSION = "sourceAppVersion"
        private const val JSON_SOURCE_DEVICE_LABEL = "sourceDeviceLabel"
        private const val JSON_KEY_COUNT = "keyCount"
        private const val JSON_WRAPPED_IV_B64 = "wrappedIvBase64"
        private const val JSON_WRAPPED_KEY_B64 = "wrappedKeyBase64"
        private const val JSON_RAW_KEY_B64 = "rawKeyBase64"

        private const val JSON_BUNDLE_VERSION = "bundleVersion"
        private const val JSON_KDF = "kdf"
        private const val JSON_ITERATIONS = "iterations"
        private const val JSON_SALT_B64 = "saltBase64"
        private const val JSON_NONCE_B64 = "nonceBase64"
        private const val JSON_CIPHERTEXT_B64 = "ciphertextBase64"

        private const val PBKDF2_ITERATIONS = 210_000
        private const val PBKDF2_SALT_SIZE_BYTES = 16
        private const val AES_GCM_NONCE_SIZE_BYTES = 12
        private const val AES_GCM_TAG_BITS = 128

        private const val KDF_NAME = "PBKDF2WithHmacSHA256"
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        private val secureRandom = SecureRandom()
    }
}
