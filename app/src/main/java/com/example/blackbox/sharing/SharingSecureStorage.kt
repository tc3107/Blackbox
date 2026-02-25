package com.example.blackbox.sharing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.blackbox.data.locationdb.LocationDbPaths
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SharingSecureStorage(context: Context) {
    private val appContext = context.applicationContext
    private val stateFile = File(appContext.filesDir, STATE_FILE_RELATIVE_PATH)
    private val json = Json { ignoreUnknownKeys = true }

    fun readPlaintextState(): ByteArray? {
        if (!stateFile.exists()) {
            return null
        }
        val wrapper = runCatching {
            json.decodeFromString<EncryptedBlobWrapper>(stateFile.readText())
        }.getOrNull() ?: return null

        val iv = wrapper.ivBase64.base64Decode()
        val ciphertext = wrapper.ciphertextBase64.base64Decode()
        return aesGcmDecrypt(iv = iv, ciphertext = ciphertext)
    }

    fun writePlaintextState(plaintext: ByteArray) {
        val encrypted = aesGcmEncrypt(plaintext)
        val wrapper = EncryptedBlobWrapper(
            version = SharingVersions.SECURE_STORAGE_BLOB_VERSION,
            ivBase64 = encrypted.iv.base64Encode(),
            ciphertextBase64 = encrypted.ciphertext.base64Encode(),
            updatedAtMs = System.currentTimeMillis()
        )

        LocationDbPaths.ensureParentDir(stateFile)
        val temp = File(stateFile.parentFile, "${stateFile.name}.tmp")
        temp.writeText(json.encodeToString(EncryptedBlobWrapper.serializer(), wrapper))
        if (!temp.renameTo(stateFile)) {
            error("Failed to persist encrypted sharing state atomically.")
        }
    }

    private fun aesGcmEncrypt(plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateStateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(iv = cipher.iv, ciphertext = ciphertext)
    }

    private fun aesGcmDecrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateStateKey(),
            GCMParameterSpec(AES_GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateStateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(STATE_KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            STATE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(false)
        }

        generator.init(builder.build())
        return generator.generateKey()
    }

    private data class EncryptedBlob(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    @Serializable
    private data class EncryptedBlobWrapper(
        val version: Int,
        val ivBase64: String,
        val ciphertextBase64: String,
        val updatedAtMs: Long
    )

    private fun ByteArray.base64Encode(): String {
        return Base64.encodeToString(this, Base64.NO_WRAP)
    }

    private fun String.base64Decode(): ByteArray {
        return Base64.decode(this, Base64.DEFAULT)
    }

    private companion object {
        const val STATE_FILE_RELATIVE_PATH = "location/sharing/state_v1.encjson"
        const val STATE_KEY_ALIAS = "blackbox_sharing_state_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_GCM_TAG_BITS = 128
    }
}
