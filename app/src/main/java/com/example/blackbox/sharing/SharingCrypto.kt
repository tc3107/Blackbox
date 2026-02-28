package com.example.blackbox.sharing

import com.example.blackbox.logging.AppLog as Log
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SharingCrypto {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        runCatching { TinkConfig.register() }
            .onFailure { throwable ->
                Log.e(TAG, "Failed to register Tink", throwable)
                throw throwable
            }
    }

    @Suppress("UNUSED_PARAMETER")
    fun generateIdentity(nowMs: Long, requestedSenderName: String?): SharingIdentityState {
        val keyPair = generateSigningKeyPair()
        val signPrivatePkcs8B64 = keyPair.private.encoded.base64UrlEncode()
        val signPublicSpkiB64 = keyPair.public.encoded.base64UrlEncode()

        val encPrivate = runCatching {
            val x25519Template = x25519HybridTemplateOrNull()
            if (x25519Template != null) {
                KeysetHandle.generateNew(x25519Template)
            } else {
                KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
            }
        }.getOrElse {
            KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
        }
        val encPublic = encPrivate.publicKeysetHandle

        val senderId = senderIdFromSignPublicKey(signPublicSpkiB64)

        return SharingIdentityState(
            senderId = senderId,
            signPrivateKeyPkcs8B64Url = signPrivatePkcs8B64,
            signPublicKeySpkiB64Url = signPublicSpkiB64,
            encPrivateKeysetJson = serializeKeyset(encPrivate),
            encPublicKeysetJson = serializeKeyset(encPublic),
            createdAtMs = nowMs,
            legacySignPrivateKeysetJson = null,
            legacySignPublicKeysetJson = null
        )
    }

    fun isIdentityValid(identity: SharingIdentityState): Boolean {
        if (identity.senderId.isBlank()) return false
        if (identity.signPrivateKeyPkcs8B64Url.isBlank()) return false
        if (identity.signPublicKeySpkiB64Url.isBlank()) return false
        if (identity.encPrivateKeysetJson.isBlank()) return false
        if (identity.encPublicKeysetJson.isBlank()) return false

        return runCatching {
            if (senderIdFromSignPublicKey(identity.signPublicKeySpkiB64Url) != identity.senderId) {
                return@runCatching false
            }

            // Ensure signing private/public keys are a matching pair.
            val probeMessage = "bbx-signing-self-check".toByteArray(Charsets.UTF_8)
            val privateKey = decodePrivateSigningKey(identity.signPrivateKeyPkcs8B64Url)
            val publicKey = decodePublicSigningKey(identity.signPublicKeySpkiB64Url)
            val signer = Signature.getInstance(signatureAlgorithmForKey(privateKey.algorithm))
            signer.initSign(privateKey)
            signer.update(probeMessage)
            val signature = signer.sign()

            val verifier = Signature.getInstance(signatureAlgorithmForKey(publicKey.algorithm))
            verifier.initVerify(publicKey)
            verifier.update(probeMessage)
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    fun sign(identity: SharingIdentityState, message: ByteArray): ByteArray {
        val privateKey = decodePrivateSigningKey(identity.signPrivateKeyPkcs8B64Url)
        val signature = Signature.getInstance(signatureAlgorithmForKey(privateKey.algorithm))
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }

    fun verify(signPublicKeySpkiB64Url: String, message: ByteArray, signature: ByteArray): Boolean {
        return runCatching {
            val publicKey = decodePublicSigningKey(signPublicKeySpkiB64Url)
            val verifier = Signature.getInstance(signatureAlgorithmForKey(publicKey.algorithm))
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    fun encryptForRecipient(
        recipientEncPublicKeysetJson: String,
        plaintext: ByteArray,
        contextInfo: ByteArray
    ): ByteArray {
        val handle = deserializeKeyset(recipientEncPublicKeysetJson)
        val encryptor = handle.getPrimitive(HybridEncrypt::class.java)
        return encryptor.encrypt(plaintext, contextInfo)
    }

    fun decryptForIdentity(identity: SharingIdentityState, ciphertext: ByteArray, contextInfo: ByteArray): ByteArray {
        val handle = deserializeKeyset(identity.encPrivateKeysetJson)
        val decryptor = handle.getPrimitive(HybridDecrypt::class.java)
        return decryptor.decrypt(ciphertext, contextInfo)
    }

    fun exportContactCode(identity: SharingIdentityState, onboardingName: String?): String {
        val payload = OnboardingCardPayload(
            version = SharingVersions.CONTACT_CARD_VERSION,
            senderId = identity.senderId,
            onboardingName = onboardingName?.trim()?.takeIf { it.isNotEmpty() },
            signPublicKeySpkiB64Url = identity.signPublicKeySpkiB64Url,
            encPublicKeysetJson = identity.encPublicKeysetJson,
            // Keep contact code deterministic for a stable QR unless identity/name actually changes.
            createdAtMs = identity.createdAtMs
        )
        val payloadBytes = canonicalContactCardMessage(
            version = payload.version,
            senderId = payload.senderId,
            onboardingName = payload.onboardingName,
            signPublicKeySpkiB64Url = payload.signPublicKeySpkiB64Url,
            encPublicKeysetJson = payload.encPublicKeysetJson,
            createdAtMs = payload.createdAtMs
        )
        val signature = sign(identity, payloadBytes)
        val signedCard = SignedOnboardingCard(
            payload = payload,
            signatureB64Url = signature.base64UrlEncode()
        )
        val cardJson = json.encodeToString(signedCard)
        return "${SharingVersions.CONTACT_CARD_PREFIX}:${cardJson.toByteArray(Charsets.UTF_8).base64UrlEncode()}"
    }

    fun importContactCode(code: String): ImportedContactCard {
        val trimmed = code.trim()
        require(trimmed.startsWith("${SharingVersions.CONTACT_CARD_PREFIX}:")) { "Invalid code prefix." }
        val encoded = trimmed.substringAfter(':')
        val rawJson = encoded.base64UrlDecode().toString(Charsets.UTF_8)
        val signedCard = json.decodeFromString(SignedOnboardingCard.serializer(), rawJson)
        require(signedCard.payload.version == SharingVersions.CONTACT_CARD_VERSION) { "Unsupported contact card version." }

        val expectedSenderId = senderIdFromSignPublicKey(signedCard.payload.signPublicKeySpkiB64Url)
        require(expectedSenderId == signedCard.payload.senderId) { "Contact senderId does not match signing key." }

        val payloadBytes = canonicalContactCardMessage(
            version = signedCard.payload.version,
            senderId = signedCard.payload.senderId,
            onboardingName = signedCard.payload.onboardingName,
            signPublicKeySpkiB64Url = signedCard.payload.signPublicKeySpkiB64Url,
            encPublicKeysetJson = signedCard.payload.encPublicKeysetJson,
            createdAtMs = signedCard.payload.createdAtMs
        )
        val verified = verify(
            signPublicKeySpkiB64Url = signedCard.payload.signPublicKeySpkiB64Url,
            message = payloadBytes,
            signature = signedCard.signatureB64Url.base64UrlDecode()
        )
        require(verified) { "Contact card signature validation failed." }

        val fingerprint = safetyFingerprint(
            senderId = signedCard.payload.senderId,
            signPublicKeySpkiB64Url = signedCard.payload.signPublicKeySpkiB64Url,
            encPublicKeysetJson = signedCard.payload.encPublicKeysetJson
        )

        return ImportedContactCard(
            senderId = signedCard.payload.senderId,
            onboardingName = signedCard.payload.onboardingName,
            signPublicKeySpkiB64Url = signedCard.payload.signPublicKeySpkiB64Url,
            encPublicKeysetJson = signedCard.payload.encPublicKeysetJson,
            safetyFingerprint = fingerprint
        )
    }

    fun safetyFingerprint(
        senderId: String,
        signPublicKeySpkiB64Url: String,
        encPublicKeysetJson: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$senderId|$signPublicKeySpkiB64Url|$encPublicKeysetJson".toByteArray(Charsets.UTF_8)
        )
        val compact = digest.joinToString(separator = "") { "%02x".format(it) }
        val short = compact.take(24)
        return short.chunked(4).joinToString("-")
    }

    fun senderIdFromIdentity(identity: SharingIdentityState): String {
        return senderIdFromSignPublicKey(identity.signPublicKeySpkiB64Url)
    }

    fun senderIdFromSignPublicKey(signPublicKeySpkiB64Url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signPublicKeySpkiB64Url.base64UrlDecode())
        return digest.base64UrlEncode()
    }

    fun encryptContactsBundle(
        identity: SharingIdentityState,
        contacts: List<PeerContactState>,
        passphrase: CharArray
    ): String {
        val payload = json.encodeToString(
            ContactsBundlePayload(
                identity = identity,
                contacts = contacts
            )
        ).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(PBKDF2_SALT_SIZE).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(AES_GCM_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val key = derivePassphraseKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(payload)
        val bundle = IdentityBundle(
            bundleVersion = SharingVersions.IDENTITY_BUNDLE_VERSION,
            kdf = "PBKDF2WithHmacSHA256",
            iterations = PBKDF2_ITERATIONS,
            saltB64Url = salt.base64UrlEncode(),
            nonceB64Url = nonce.base64UrlEncode(),
            ciphertextB64Url = ciphertext.base64UrlEncode(),
            createdAtMs = System.currentTimeMillis()
        )
        return json.encodeToString(bundle)
    }

    fun decryptContactsBundle(bundleJson: String, passphrase: CharArray): ImportedContactsBundle {
        val bundle = json.decodeFromString(IdentityBundle.serializer(), bundleJson)
        require(bundle.bundleVersion == SharingVersions.IDENTITY_BUNDLE_VERSION) { "Unsupported identity bundle version." }

        val salt = bundle.saltB64Url.base64UrlDecode()
        val nonce = bundle.nonceB64Url.base64UrlDecode()
        val ciphertext = bundle.ciphertextB64Url.base64UrlDecode()
        val key = derivePassphraseKey(passphrase, salt, bundle.iterations)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
        val plaintext = cipher.doFinal(ciphertext)
        val payloadText = plaintext.toString(Charsets.UTF_8)
        val importedPayload = runCatching {
            json.decodeFromString(
                ContactsBundlePayload.serializer(),
                payloadText
            )
        }.getOrElse {
            // Backward compatibility for older exports that stored identity only.
            ContactsBundlePayload(
                identity = json.decodeFromString(
                    SharingIdentityState.serializer(),
                    payloadText
                ),
                contacts = emptyList()
            )
        }
        require(isIdentityValid(importedPayload.identity)) { "Imported contacts bundle is invalid." }
        return ImportedContactsBundle(
            identity = importedPayload.identity,
            contacts = importedPayload.contacts
        )
    }

    fun encryptIdentityBundle(identity: SharingIdentityState, passphrase: CharArray): String {
        return encryptContactsBundle(identity = identity, contacts = emptyList(), passphrase = passphrase)
    }

    fun decryptIdentityBundle(bundleJson: String, passphrase: CharArray): SharingIdentityState {
        return decryptContactsBundle(bundleJson = bundleJson, passphrase = passphrase).identity
    }

    private fun derivePassphraseKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = keyFactory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(encoded, "AES")
    }

    private fun serializeKeyset(handle: KeysetHandle): String {
        val output = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(output))
        return output.toString(Charsets.UTF_8.name())
    }

    private fun deserializeKeyset(keysetJson: String): KeysetHandle {
        return CleartextKeysetHandle.read(JsonKeysetReader.withString(keysetJson))
    }

    private fun generateSigningKeyPair(): KeyPair {
        generateEd25519SoftwareKeyPairOrNull()?.let { return it }
        Log.w(TAG, "No software Ed25519 provider available; falling back to EC P-256 signing keys.")
        return generateEcP256KeyPair()
    }

    private fun generateEd25519SoftwareKeyPairOrNull(): KeyPair? {
        val providerNames = Security.getProviders()
            .map { it.name }
            .filterNot { providerName -> isAndroidKeyStoreProvider(providerName) }

        providerNames.forEach { providerName ->
            val pair = runCatching {
                KeyPairGenerator.getInstance("Ed25519", providerName).generateKeyPair()
            }.getOrNull()
            if (pair?.private?.encoded != null && pair.public.encoded != null) {
                return pair
            }
        }

        val defaultPair = runCatching {
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        }.getOrNull()
        if (defaultPair?.private?.encoded != null && defaultPair.public.encoded != null) {
            return defaultPair
        }

        return null
    }

    private fun generateEcP256KeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val pair = generator.generateKeyPair()
        require(pair.private.encoded != null && pair.public.encoded != null) {
            "Generated EC keypair is not exportable."
        }
        return pair
    }

    private fun decodePrivateSigningKey(privateKeyPkcs8B64Url: String): PrivateKey {
        val encoded = privateKeyPkcs8B64Url.base64UrlDecode()
        val spec = PKCS8EncodedKeySpec(encoded)
        runCatching {
            return KeyFactory.getInstance("Ed25519").generatePrivate(spec)
        }
        return runCatching {
            KeyFactory.getInstance("EC").generatePrivate(spec)
        }.getOrElse { throwable ->
            throw IllegalArgumentException("Unsupported signing private key format.", throwable)
        }
    }

    private fun decodePublicSigningKey(publicKeySpkiB64Url: String): PublicKey {
        val encoded = publicKeySpkiB64Url.base64UrlDecode()
        val spec = X509EncodedKeySpec(encoded)
        runCatching {
            return KeyFactory.getInstance("Ed25519").generatePublic(spec)
        }
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(spec)
        }.getOrElse { throwable ->
            throw IllegalArgumentException("Unsupported signing public key format.", throwable)
        }
    }

    private fun signatureAlgorithmForKey(keyAlgorithm: String): String {
        return when (keyAlgorithm.uppercase()) {
            "ED25519" -> "Ed25519"
            "EC" -> "SHA256withECDSA"
            else -> throw IllegalArgumentException("Unsupported signing key algorithm: $keyAlgorithm")
        }
    }

    private fun isAndroidKeyStoreProvider(providerName: String): Boolean {
        return providerName.contains("AndroidKeyStore", ignoreCase = true)
    }

    private fun x25519HybridTemplateOrNull(): KeyTemplate? {
        return runCatching {
            val clazz = Class.forName("com.google.crypto.tink.hybrid.HybridKeyTemplates")
            val field = clazz.getDeclaredField("X25519_HKDF_SHA256_AES256_GCM")
            field.get(null) as? KeyTemplate
        }.getOrNull()
    }

    private fun ByteArray.base64UrlEncode(): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    }

    private fun String.base64UrlDecode(): ByteArray {
        return Base64.getUrlDecoder().decode(this)
    }

    @Serializable
    private data class OnboardingCardPayload(
        val version: Int,
        val senderId: String,
        val onboardingName: String?,
        val signPublicKeySpkiB64Url: String,
        val encPublicKeysetJson: String,
        val createdAtMs: Long
    )

    @Serializable
    private data class SignedOnboardingCard(
        val payload: OnboardingCardPayload,
        val signatureB64Url: String
    )

    @Serializable
    private data class IdentityBundle(
        val bundleVersion: String,
        val kdf: String,
        val iterations: Int,
        val saltB64Url: String,
        val nonceB64Url: String,
        val ciphertextB64Url: String,
        val createdAtMs: Long
    )

    @Serializable
    private data class ContactsBundlePayload(
        val identity: SharingIdentityState,
        val contacts: List<PeerContactState> = emptyList()
    )

    data class ImportedContactCard(
        val senderId: String,
        val onboardingName: String?,
        val signPublicKeySpkiB64Url: String,
        val encPublicKeysetJson: String,
        val safetyFingerprint: String
    )

    data class ImportedContactsBundle(
        val identity: SharingIdentityState,
        val contacts: List<PeerContactState>
    )

    companion object {
        private const val PBKDF2_ITERATIONS = 210_000
        private const val PBKDF2_SALT_SIZE = 16
        private const val AES_GCM_NONCE_SIZE = 12
        private const val AES_GCM_TAG_BITS = 128
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        private val secureRandom = SecureRandom()
        private const val TAG = "SharingCrypto"
    }
}
