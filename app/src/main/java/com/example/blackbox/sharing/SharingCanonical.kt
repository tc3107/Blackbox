package com.example.blackbox.sharing

fun canonicalAclMessage(acl: AclUnsigned): ByteArray {
    val receivers = acl.receiverIds.distinct().sorted().joinToString(",")
    return "${SharingVersions.ACL_CANONICAL_VERSION}|${acl.senderId}|${acl.aclSeq}|${acl.timestampMs}|$receivers"
        .toByteArray(Charsets.UTF_8)
}

fun canonicalPushMessage(unsigned: PushEnvelopeUnsigned): ByteArray {
    val recipients = unsigned.recipientCiphertexts
        .sortedBy { it.recipientId }
        .joinToString(";") { "${it.recipientId}:${it.ciphertextB64Url}" }
    return "${SharingVersions.PUSH_CANONICAL_VERSION}|${unsigned.senderId}|${unsigned.seq}|${unsigned.timestampMs}|${unsigned.payloadVersion}|$recipients"
        .toByteArray(Charsets.UTF_8)
}

fun canonicalPullMessage(receiverId: String, senderIds: List<String>, timestampMs: Long, nonceB64Url: String): ByteArray {
    val senders = senderIds.distinct().sorted().joinToString(",")
    return "${SharingVersions.PULL_CANONICAL_VERSION}|$receiverId|$timestampMs|$nonceB64Url|$senders"
        .toByteArray(Charsets.UTF_8)
}

fun canonicalSelfStatusMessage(senderId: String, timestampMs: Long, nonceB64Url: String): ByteArray {
    return "${SharingVersions.SELF_STATUS_CANONICAL_VERSION}|$senderId|$timestampMs|$nonceB64Url"
        .toByteArray(Charsets.UTF_8)
}

fun canonicalClearMessage(senderId: String, timestampMs: Long, nonceB64Url: String): ByteArray {
    return "${SharingVersions.CLEAR_CANONICAL_VERSION}|$senderId|$timestampMs|$nonceB64Url"
        .toByteArray(Charsets.UTF_8)
}

fun canonicalContactCardMessage(
    version: Int,
    senderId: String,
    onboardingName: String?,
    signPublicKeySpkiB64Url: String,
    encPublicKeysetJson: String,
    createdAtMs: Long
): ByteArray {
    val safeName = onboardingName?.trim().orEmpty()
    return "${SharingVersions.CONTACT_CARD_CANONICAL_VERSION}|$version|$senderId|$safeName|$signPublicKeySpkiB64Url|$encPublicKeysetJson|$createdAtMs"
        .toByteArray(Charsets.UTF_8)
}
