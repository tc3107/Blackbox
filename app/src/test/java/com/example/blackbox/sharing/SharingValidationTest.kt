package com.example.blackbox.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingValidationTest {
    @Test
    fun `username normalization and validation`() {
        assertEquals("alice", normalizeUsername("  alice  "))
        assertTrue(isValidUsername("a"))
        assertTrue(isValidUsername("abcdefghijklmnopqrstuvwxyz123456"))
        assertFalse(isValidUsername(""))
        assertFalse(isValidUsername("   "))
        assertFalse(isValidUsername("abcdefghijklmnopqrstuvwxyz1234567"))
    }

    @Test
    fun `zone constraints clamp radius and validate name`() {
        assertEquals(10, clampZoneRadius(1))
        assertEquals(500, clampZoneRadius(9999))
        assertTrue(isValidZoneName("Home"))
        assertFalse(isValidZoneName(""))
    }
}
