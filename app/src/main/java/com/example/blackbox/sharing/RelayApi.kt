package com.example.blackbox.sharing

import kotlinx.serialization.Serializable

interface RelayApi {
    suspend fun relayStatus(request: RelayStatusRequest): Result<RelayStatusResponse>
    suspend fun upsertAcl(request: UpsertAclRequest): Result<UpsertAclResponse>
    suspend fun pushLocation(request: PushLocationRequest): Result<PushLocationResponse>
    suspend fun pullBatch(request: PullBatchRequest): Result<PullBatchResponse>
    suspend fun selfStatus(request: SelfStatusRequest): Result<SelfStatusResponse>
    suspend fun clearLocation(request: ClearLocationRequest): Result<ClearLocationResponse>
}

@Serializable
data class RelayStatusRequest(
    val clientTimestampMs: Long
)

@Serializable
data class RelayStatusResponse(
    val ok: Boolean,
    val status: String = "ok",
    val serverTimestampMs: Long,
    val apiVersion: String = "v1",
    val message: String? = null
)

@Serializable
data class AclUnsigned(
    val senderId: String,
    val aclSeq: Long,
    val receiverIds: List<String>,
    val timestampMs: Long
)

@Serializable
data class UpsertAclRequest(
    val acl: AclUnsigned,
    val signatureB64Url: String,
    val senderSignPublicKeySpkiB64Url: String
)

@Serializable
data class UpsertAclResponse(
    val ok: Boolean,
    val appliedAclSeq: Long,
    val message: String? = null
)

@Serializable
data class PushLocationRequest(
    val push: PushEnvelopeSigned,
    val senderSignPublicKeySpkiB64Url: String,
    val senderEncPublicKeysetJson: String
)

@Serializable
data class PushLocationResponse(
    val ok: Boolean,
    val appliedSeq: Long,
    val storedAtMs: Long,
    val message: String? = null
)

@Serializable
data class PullBatchRequest(
    val receiverId: String,
    val senderIds: List<String>,
    val timestampMs: Long,
    val nonceB64Url: String,
    val signatureB64Url: String,
    val receiverSignPublicKeySpkiB64Url: String
)

@Serializable
data class PullBatchResponse(
    val ok: Boolean,
    val serverTimestampMs: Long,
    val records: List<PullRecord>,
    val message: String? = null
)

@Serializable
data class PullRecord(
    val senderId: String,
    val storedAtMs: Long? = null,
    val envelope: PushEnvelopeSigned? = null,
    val status: String,
    val message: String? = null
)

@Serializable
data class SelfStatusRequest(
    val senderId: String,
    val timestampMs: Long,
    val nonceB64Url: String,
    val signatureB64Url: String,
    val senderSignPublicKeySpkiB64Url: String
)

@Serializable
data class SelfStatusResponse(
    val ok: Boolean,
    val latestSeq: Long? = null,
    val latestTimestampMs: Long? = null,
    val storedAtMs: Long? = null,
    val message: String? = null
)

@Serializable
data class ClearLocationRequest(
    val senderId: String,
    val timestampMs: Long,
    val nonceB64Url: String,
    val signatureB64Url: String,
    val senderSignPublicKeySpkiB64Url: String
)

@Serializable
data class ClearLocationResponse(
    val ok: Boolean,
    val cleared: Boolean,
    val message: String? = null
)
