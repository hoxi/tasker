package net.tagpad.tasker.sort.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle

class DragReorderTest {

    /** Three rows, 20px tall, stacked. */
    private val rows = listOf(Rectangle(0, 0, 200, 20), Rectangle(0, 20, 200, 20), Rectangle(0, 40, 200, 20))

    /** Two chips, 50px wide, on one line, and a third wrapped onto the next. */
    private val chips = listOf(Rectangle(0, 0, 50, 20), Rectangle(50, 0, 50, 20), Rectangle(0, 20, 50, 20))

    @Test
    fun `rows split at their vertical middle`() {
        assertEquals(0, DragReorder.dropSlot(rows, Point(10, 5)))
        assertEquals(1, DragReorder.dropSlot(rows, Point(10, 15)))
        assertEquals(2, DragReorder.dropSlot(rows, Point(10, 35)))
        assertEquals(3, DragReorder.dropSlot(rows, Point(10, 500)))
    }

    @Test
    fun `chips split at their horizontal middle, on the pointer's line`() {
        assertEquals(0, DragReorder.dropSlot(chips, Point(10, 10)))
        assertEquals(1, DragReorder.dropSlot(chips, Point(60, 10)))
        assertEquals(2, DragReorder.dropSlot(chips, Point(90, 10)))
        assertEquals(2, DragReorder.dropSlot(chips, Point(10, 30)))
        assertEquals(3, DragReorder.dropSlot(chips, Point(40, 30)))
    }

    /** A short row, a tall one like the assignee ranking, and another short one, 6px apart. */
    private val mixed = listOf(Rectangle(0, 0, 200, 20), Rectangle(0, 26, 200, 80), Rectangle(0, 112, 200, 20))

    private fun drop(from: Int, dy: Int) =
        DragReorder.dropTarget(mixed, from, Rectangle(mixed[from]).apply { translate(0, dy) }, Point(10, 0))

    @Test
    fun `a short row passes a tall one once its bottom edge crosses the tall one's middle`() {
        assertEquals(0, drop(0, 40))
        assertEquals(1, drop(0, 46))
        assertEquals(2, drop(2, -40))
        assertEquals(1, drop(2, -46))
    }

    @Test
    fun `a tall row passes a short one as soon as its edge crosses the short one's middle`() {
        assertEquals(1, drop(1, 10))
        assertEquals(2, drop(1, 16))
        assertEquals(1, drop(1, -10))
        assertEquals(0, drop(1, -16))
    }

    @Test
    fun `the gaps either side of the item leave it where it is`() {
        assertEquals(1, DragReorder.targetIndex(1, 1))
        assertEquals(1, DragReorder.targetIndex(1, 2))
        assertEquals(0, DragReorder.targetIndex(1, 0))
        assertEquals(2, DragReorder.targetIndex(1, 3))
    }

    @Test
    fun `the row marker runs across the column between the rows, inside the ends`() {
        assertEquals(Rectangle(0, 19, 200, 2), DragReorder.marker(rows, 1, Point(10, 15), 2))
        assertEquals(Rectangle(0, 58, 200, 2), DragReorder.marker(rows, 3, Point(10, 500), 2))
    }

    @Test
    fun `the chip marker stands between chips, on the pointer's line, inside the ends`() {
        assertEquals(Rectangle(49, 0, 2, 20), DragReorder.marker(chips, 1, Point(60, 10), 2))
        // Slot 2 is both the end of the first line and the start of the second.
        assertEquals(Rectangle(98, 0, 2, 20), DragReorder.marker(chips, 2, Point(90, 10), 2))
        assertEquals(Rectangle(0, 20, 2, 20), DragReorder.marker(chips, 2, Point(10, 30), 2))
    }
}
