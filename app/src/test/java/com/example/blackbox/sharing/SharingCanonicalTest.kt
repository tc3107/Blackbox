package com.example.blackbox.sharing

import org.junit.Assert.assertEquals
import org.junit.Test

class SharingCanonicalTest {
    @Test
    fun `canonical acl sorts receiver ids`() {
        val acl = AclUnsigned(
            senderId = "sender",
            aclSeq = 3L,
            receiverIds = listOf("z", "a", "z"),
            timestampMs = 100L
        )
        val canonical = canonicalAclMessage(acl).toString(Charsets.UTF_8)
        assertEquals("${SharingVersions.ACL_CANONICAL_VERSION}|sender|3|100|a,z", canonical)
    }

    @Test
    fun `canonical push sorts recipient ciphertexts`() {
        val unsigned = PushEnvelopeUnsigned(
            senderId = "sender",
            seq = 2L,
            timestampMs = 300L,
            payloadVersion = SharingVersions.PAYLOAD_VERSION,
            recipientCiphertexts = listOf(
                RecipientCiphertext(recipientId = "b", ciphertextB64Url = "bbb"),
                RecipientCiphertext(recipientId = "a", ciphertextB64Url = "aaa")
            )
        )
        val canonical = canonicalPushMessage(unsigned).toString(Charsets.UTF_8)
        assertEquals(
            "${SharingVersions.PUSH_CANONICAL_VERSION}|sender|2|300|${SharingVersions.PAYLOAD_VERSION}|a:aaa;b:bbb",
            canonical
        )
    }
}
