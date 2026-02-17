package com.example.blackbox.locationdb

import com.example.blackbox.data.locationdb.LocationDbPaths
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationDbPathsTest {
    @Test
    fun `uses utc filename format`() {
        val day = LocalDate.of(2026, 2, 17)
        assertEquals("blackbox-2026-02-17.db", LocationDbPaths.fileNameForDay(day))
    }

    @Test
    fun `builds expected relative path`() {
        val day = LocalDate.of(2026, 7, 3)
        assertEquals("databases/2026/07/blackbox-2026-07-03.db", LocationDbPaths.relativePathForDay(day))
    }

    @Test
    fun `parses day from db filename`() {
        val parsed = LocationDbPaths.parseUtcDayFromDbFile(File("/tmp/blackbox-2026-07-03.db"))
        assertEquals(LocalDate.of(2026, 7, 3), parsed)
    }

    @Test
    fun `returns null for unknown filename`() {
        assertNull(LocationDbPaths.parseUtcDayFromDbFile(File("/tmp/random.db")))
    }
}
