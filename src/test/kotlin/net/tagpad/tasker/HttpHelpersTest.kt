package net.tagpad.tasker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color

class HttpHelpersTest {

    @Test
    fun `durations are shown coarsely`() {
        assertEquals("", formatDuration(0))
        assertEquals("30s", formatDuration(30_000))
        assertEquals("45m", formatDuration(45 * 60_000L))
        assertEquals("2h", formatDuration(2 * 3_600_000L))
        assertEquals("2h 15m", formatDuration(2 * 3_600_000L + 15 * 60_000L))
    }

    @Test
    fun `hex colours parse with or without a hash`() {
        assertEquals(Color(0xE6F6CF), parseHexColor("#e6f6cf"))
        assertEquals(Color(0xD73A4A), parseHexColor("d73a4a"))
        assertNull(parseHexColor("#fff"))
        assertNull(parseHexColor("nothex"))
        assertNull(parseHexColor(null))
    }
}
