package com.example.blackbox.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingLogicTest {
    @Test
    fun `backoff grows and caps`() {
        assertEquals(10_000L, SharingLogic.computeBackoffDelayMs(1))
        assertEquals(20_000L, SharingLogic.computeBackoffDelayMs(2))
        assertEquals(40_000L, SharingLogic.computeBackoffDelayMs(3))
        assertEquals(10 * 60_000L, SharingLogic.computeBackoffDelayMs(99))
    }

    @Test
    fun `matching zones returns nearest names capped`() {
        val zones = listOf(
            ShareZone(
                id = "1",
                name = "Far",
                centerLat = 40.001,
                centerLon = -74.001,
                radiusM = 500,
                createdAtMs = 1L
            ),
            ShareZone(
                id = "2",
                name = "NearA",
                centerLat = 40.0,
                centerLon = -74.0,
                radiusM = 500,
                createdAtMs = 2L
            ),
            ShareZone(
                id = "3",
                name = "NearB",
                centerLat = 40.0,
                centerLon = -74.0001,
                radiusM = 500,
                createdAtMs = 3L
            ),
            ShareZone(
                id = "4",
                name = "NearC",
                centerLat = 40.0,
                centerLon = -74.0002,
                radiusM = 500,
                createdAtMs = 4L
            )
        )

        val matched = SharingLogic.matchingZoneTags(lat = 40.0, lon = -74.0, zones = zones)
        assertEquals(3, matched.size)
        assertTrue(matched.contains("NearA"))
        assertTrue(matched.contains("NearB"))
        assertTrue(matched.contains("NearC"))
        assertFalse(matched.contains("Far"))
    }

    @Test
    fun `claim validator rejects invalid required fields`() {
        val valid = LocationClaimV1(
            version = SharingVersions.PAYLOAD_VERSION,
            timestampMs = 1L,
            lat = 40.0,
            lon = -74.0,
            speed = null,
            accuracy = null,
            zones = null,
            username = null,
            senderId = "sender",
            seq = 1L
        )
        assertTrue(SharingLogic.isValidClaim(valid))

        assertFalse(SharingLogic.isValidClaim(valid.copy(version = SharingVersions.PAYLOAD_VERSION - 1)))
        assertFalse(SharingLogic.isValidClaim(valid.copy(timestampMs = 0L)))
        assertFalse(SharingLogic.isValidClaim(valid.copy(lat = 120.0)))
        assertFalse(SharingLogic.isValidClaim(valid.copy(lon = 220.0)))
    }
}
