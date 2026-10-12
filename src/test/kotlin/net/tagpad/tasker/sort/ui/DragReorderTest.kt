package net.tagpad.tasker.sort.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle

class DragReorderTest {

    /** Three rows, 20px tall, stacked. */
    private val rows = listOf(Rectangle(0, 0, 200, 20), Rectangle(0, 20, 200, 20), Rectangle(0, 40, 200, 20))

    /** Three chips, 50px wide, on one line. */
    private val chips = listOf(Rectangle(0, 0, 50, 20), Rectangle(50, 0, 50, 20), Rectangle(100, 0, 50, 20))

    @Test
    fun `a row moves down only past the middle of the next one`() {
        assertEquals(0, DragReorder.dropIndex(rows, 0, Point(10, 25)))
        assertEquals(1, DragReorder.dropIndex(rows, 0, Point(10, 35)))
    }

    @Test
    fun `a row moves up only past the middle of the previous one`() {
        assertEquals(2, DragReorder.dropIndex(rows, 2, Point(10, 35)))
        assertEquals(1, DragReorder.dropIndex(rows, 2, Point(10, 25)))
    }

    @Test
    fun `over itself or nothing, it stays`() {
        assertEquals(1, DragReorder.dropIndex(rows, 1, Point(10, 30)))
        assertEquals(1, DragReorder.dropIndex(rows, 1, Point(10, 500)))
    }

    @Test
    fun `chips on a line go by the horizontal middle`() {
        assertEquals(0, DragReorder.dropIndex(chips, 0, Point(60, 10)))
        assertEquals(1, DragReorder.dropIndex(chips, 0, Point(80, 10)))
        assertEquals(1, DragReorder.dropIndex(chips, 2, Point(70, 10)))
    }

    @Test
    fun `once moved, the pointer over the item's new place changes nothing`() {
        // Row 0 dragged below row 1: now laid out as [1, 0, 2], with the pointer still where it was.
        val after = listOf(rows[0], rows[1], rows[2])
        assertEquals(1, DragReorder.dropIndex(after, 1, Point(10, 35)))
    }
}
