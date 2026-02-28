package com.example.blackbox.sharing

import androidx.core.net.toUri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val SHARING_SCHEMA_VERSION: Int
    get() = SharingVersions.STATE_SCHEMA_VERSION
const val CONTACT_LIMIT = 200
const val ZONE_LIMIT = 20
const val ZONE_TAG_LIMIT = 3
const val USERNAME_MIN_LENGTH = 1
const val USERNAME_MAX_LENGTH = 32
const val ZONE_NAME_MIN_LENGTH = 1
const val ZONE_NAME_MAX_LENGTH = 40
const val ZONE_RADIUS_MIN_METERS = 10
const val ZONE_RADIUS_MAX_METERS = 500

@Serializable
data class ShareZone(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusM: Int,
    val createdAtMs: Long
)

@Serializable
data class LocationClaimV1(
    val version: Int,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speed: Float? = null,
    val accuracy: Float? = null,
    val batteryPercent: Int? = null,
    val zones: List<String>? = null,
    val username: String? = null,
    val senderId: String,
    val seq: Long
)

@Serializable
data class RecipientCiphertext(
    val recipientId: String,
    val ciphertextB64Url: String
)

@Serializable
data class PushEnvelopeUnsigned(
    val senderId: String,
    val seq: Long,
    val timestampMs: Long,
    val payloadVersion: Int,
    val recipientCiphertexts: List<RecipientCiphertext>
)

@Serializable
data class PushEnvelopeSigned(
    val envelope: PushEnvelopeUnsigned,
    val signatureB64Url: String
)

@Serializable
data class SharingIdentityState(
    val senderId: String = "",
    val signPrivateKeyPkcs8B64Url: String = "",
    val signPublicKeySpkiB64Url: String = "",
    val encPrivateKeysetJson: String = "",
    val encPublicKeysetJson: String = "",
    val createdAtMs: Long = 0L,
    @SerialName("signPrivateKeysetJson") val legacySignPrivateKeysetJson: String? = null,
    @SerialName("signPublicKeysetJson") val legacySignPublicKeysetJson: String? = null
)

@Serializable
data class PeerContactState(
    val senderId: String,
    val onboardingName: String? = null,
    val localAlias: String? = null,
    val signPublicKeySpkiB64Url: String = "",
    val encPublicKeysetJson: String,
    val canReceiveFromMe: Boolean = false,
    val iFollow: Boolean = false,
    val lastSeenSeq: Long? = null,
    val safetyFingerprint: String,
    val addedAtMs: Long,
    val updatedAtMs: Long,
    @SerialName("signPublicKeysetJson") val legacySignPublicKeysetJson: String? = null
)

@Serializable
data class ReceivedLocationState(
    val senderId: String,
    val claim: LocationClaimV1,
    val relaySeq: Long,
    val relayTimestampMs: Long,
    val receivedAtMs: Long
)

@Serializable
data class SenderPollStatus(
    val senderId: String,
    val lastSuccessAtMs: Long? = null,
    val lastErrorAtMs: Long? = null,
    val lastError: String? = null
)

@Serializable
data class PendingPushState(
    val envelope: PushEnvelopeSigned,
    val claim: LocationClaimV1,
    val queuedAtMs: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtMs: Long? = null
)

@Serializable
data class SharingSyncState(
    val lastPushAttemptAtMs: Long? = null,
    val lastPushSuccessAtMs: Long? = null,
    val lastPushErrorAtMs: Long? = null,
    val lastPushError: String? = null,
    val lastPollAttemptAtMs: Long? = null,
    val lastPollSuccessAtMs: Long? = null,
    val lastPollErrorAtMs: Long? = null,
    val lastPollError: String? = null,
    val lastRelayStatusCheckAtMs: Long? = null,
    val lastRelayStatusOkAtMs: Long? = null,
    val lastRelayStatusErrorAtMs: Long? = null,
    val lastRelayStatusError: String? = null,
    val relayReachable: Boolean? = null,
    val relayStatusChecking: Boolean = false,
    val relayCheckFailureStreak: Int = 0,
    val pollFailureStreak: Int = 0,
    val pushFailureStreak: Int = 0,
    val pollRequestInFlight: Boolean = false,
    val pushRequestInFlight: Boolean = false,
    val pollingActive: Boolean = false,
    val nextSeq: Long = 1L,
    val lastAclSeq: Long = 0L,
    val lastAclDigest: String? = null
)

@Serializable
data class SharingStateSnapshot(
    val schemaVersion: Int = SharingVersions.STATE_SCHEMA_VERSION,
    val identity: SharingIdentityState? = null,
    val contacts: List<PeerContactState> = emptyList(),
    val zones: List<ShareZone> = emptyList(),
    val receivedLocations: List<ReceivedLocationState> = emptyList(),
    val senderPollStatuses: List<SenderPollStatus> = emptyList(),
    val sync: SharingSyncState = SharingSyncState(),
    val pendingPush: PendingPushState? = null
)

data class SharingSettings(
    val sharingEnabled: Boolean = false,
    val username: String = "",
    val relayBaseUrl: String = DEFAULT_RELAY_BASE_URL,
    val normalIntervalMs: Long = DEFAULT_NORMAL_PUSH_INTERVAL_MS,
    val fastIntervalMs: Long = DEFAULT_FAST_PUSH_INTERVAL_MS,
    val fastSpeedThresholdMps: Float = DEFAULT_FAST_SPEED_THRESHOLD_MPS
)

data class ContactView(
    val senderId: String,
    val displayName: String,
    val onboardingName: String?,
    val localAlias: String?,
    val canReceiveFromMe: Boolean,
    val iFollow: Boolean,
    val safetyFingerprint: String,
    val lastSeenSeq: Long?
)

data class ReceivedCardView(
    val senderId: String,
    val displayName: String,
    val claim: LocationClaimV1,
    val relaySeq: Long,
    val relayTimestampMs: Long,
    val receivedAtMs: Long,
    val pollStatus: SenderPollStatus?
)

data class ContactHistorySample(
    val senderId: String,
    val seq: Long,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double
)

data class ContactHistoryRange(
    val senderId: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val samples: List<ContactHistorySample>
)

data class LocationSharingState(
    val initialized: Boolean = false,
    val settings: SharingSettings = SharingSettings(),
    val identitySenderId: String? = null,
    val mySafetyFingerprint: String? = null,
    val myContactCode: String? = null,
    val contacts: List<ContactView> = emptyList(),
    val zones: List<ShareZone> = emptyList(),
    val receivedCards: List<ReceivedCardView> = emptyList(),
    val sync: SharingSyncState = SharingSyncState(),
    val outboundRecipientsCount: Int = 0,
    val followingCount: Int = 0,
    val pollingVisible: Boolean = false,
    val lastError: String? = null,
    val lastInfo: String = "Sharing not initialized."
)

data class ContactCardImportResult(
    val senderId: String,
    val displayName: String,
    val safetyFingerprint: String
)

const val DEFAULT_RELAY_BASE_URL = "https://blackbox.tc3107.workers.dev"
const val DEFAULT_NORMAL_PUSH_INTERVAL_MS = 5 * 60_000L
const val DEFAULT_FAST_PUSH_INTERVAL_MS = 60_000L
const val DEFAULT_FAST_SPEED_THRESHOLD_MPS = 8.0f
const val POLL_INTERVAL_MS = 2 * 60_000L
const val RELAY_STATUS_INTERVAL_MS = 20_000L
const val CONTACT_HISTORY_WINDOW_MS = 24 * 60 * 60_000L

fun normalizeUsername(raw: String): String {
    return raw.trim().take(USERNAME_MAX_LENGTH)
}

fun isValidUsername(value: String): Boolean {
    val normalized = value.trim()
    return normalized.length in USERNAME_MIN_LENGTH..USERNAME_MAX_LENGTH
}

fun isValidRelayBaseUrl(baseUrl: String): Boolean {
    val normalized = baseUrl.trim().trimEnd('/')
    if (!normalized.startsWith("https://")) {
        return false
    }
    val uri = runCatching { normalized.toUri() }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank()
}

fun isValidNormalIntervalMs(value: Long): Boolean {
    return value in MIN_NORMAL_INTERVAL_MS..MAX_NORMAL_INTERVAL_MS
}

fun isValidFastIntervalMs(value: Long): Boolean {
    return value in MIN_FAST_INTERVAL_MS..MAX_FAST_INTERVAL_MS
}

fun normalizeZoneName(raw: String): String {
    return raw.trim().take(ZONE_NAME_MAX_LENGTH)
}

fun isValidZoneName(value: String): Boolean {
    val normalized = value.trim()
    return normalized.length in ZONE_NAME_MIN_LENGTH..ZONE_NAME_MAX_LENGTH
}

fun clampZoneRadius(radiusM: Int): Int {
    return radiusM.coerceIn(ZONE_RADIUS_MIN_METERS, ZONE_RADIUS_MAX_METERS)
}

fun isValidContactCount(count: Int): Boolean = count <= CONTACT_LIMIT
fun isValidZoneCount(count: Int): Boolean = count <= ZONE_LIMIT
