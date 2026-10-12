package net.tagpad.tasker.sort.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Lets an item be dragged to another place among its siblings, by a handle on it.
 *
 * The item component itself moves within its parent as the pointer passes the middle of a sibling, so the
 * order on screen is the order; the caller reads it back from the parent's children in [install]'s
 * `onDropped`, once, when the item is let go. Works for a column of rows and for chips on wrapping lines
 * alike — see [dropIndex].
 */
internal object DragReorder {

    fun install(handle: JComponent, item: JComponent, onDropped: () -> Unit) {
        handle.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val listener = object : MouseAdapter() {
            private var moved = false
            private var wasOpaque = false
            private var background: Color? = null

            override fun mousePressed(e: MouseEvent) {
                moved = false
                // Lifted: the item shows it is the one being carried.
                wasOpaque = item.isOpaque
                background = item.background
                item.isOpaque = true
                item.background = JBUI.CurrentTheme.List.Hover.background(true)
                item.repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                val parent = item.parent as? JComponent ?: return
                // Only among its own kind: a chip mustn't land after the "+" button that follows the chips.
                val peers = parent.components.filter { it.javaClass == item.javaClass }
                val from = peers.indexOf(item)
                val pointer = SwingUtilities.convertPoint(e.component, e.point, parent)
                val to = dropIndex(peers.map { it.bounds }, from, pointer)
                if (to == from) return
                val at = parent.components.indexOf(peers[to])
                parent.remove(item)
                parent.add(item, at)
                /*
                 * Laid out now rather than on the next pass: the next drag event measures the siblings again,
                 * and against stale positions it would find the item's old neighbour still under the pointer
                 * and swap it straight back — which is what made dragging flicker between two places.
                 */
                parent.validate()
                parent.repaint()
                moved = true
            }

            override fun mouseReleased(e: MouseEvent) {
                item.isOpaque = wasOpaque
                item.background = background
                item.repaint()
                if (moved) onDropped()
            }
        }
        handle.addMouseListener(listener)
        handle.addMouseMotionListener(listener)
    }

    /**
     * Where the item at [from] belongs with the pointer at [point], among siblings laid out at [bounds].
     *
     * It moves only once the pointer is past the middle of the sibling it is over — across along a line, down
     * between lines — so a pointer resting near an edge can't make it hop back and forth. Moving forward it
     * lands after that sibling, backward before it, which is what removing it and inserting it at the
     * sibling's index does either way.
     */
    fun dropIndex(bounds: List<Rectangle>, from: Int, point: Point): Int {
        val over = bounds.indices.firstOrNull { it != from && bounds[it].contains(point) } ?: return from
        val target = bounds[over]
        val sameLine = target.y == bounds[from].y
        val beyondMiddle = if (sameLine) point.x - target.centerX else point.y - target.centerY
        return when {
            over > from && beyondMiddle >= 0 -> over
            over < from && beyondMiddle <= 0 -> over
            else -> from
        }
    }
}
