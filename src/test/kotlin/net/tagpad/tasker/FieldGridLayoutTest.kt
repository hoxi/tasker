package net.tagpad.tasker

import net.tagpad.tasker.FieldGridLayout.Companion.columnsFitting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FieldGridLayoutTest {

    @Test
    fun `a third column once the width holds three cells and their gaps`() {
        assertEquals(2, columnsFitting(width = 331, cellWidth = 100, gap = 16, min = 2, max = 3))
        assertEquals(3, columnsFitting(width = 332, cellWidth = 100, gap = 16, min = 2, max = 3))
        assertEquals(3, columnsFitting(width = 2000, cellWidth = 100, gap = 16, min = 2, max = 3))
    }

    @Test
    fun `never fewer than two columns, even when they don't fit or before there is a width`() {
        assertEquals(2, columnsFitting(width = 150, cellWidth = 100, gap = 16, min = 2, max = 3))
        assertEquals(2, columnsFitting(width = 0, cellWidth = 100, gap = 16, min = 2, max = 3))
    }
}
