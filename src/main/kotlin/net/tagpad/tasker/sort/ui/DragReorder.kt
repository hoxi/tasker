package net.tagpad.tasker.sort.ui

import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Lets an item be dragged to another place among its siblings, by a handle on it.
 *
 * The item component itself is moved within its parent as the pointer passes over another sibling, so the
 * order on screen is the order — the caller reads it back from the parent's children in [onMoved], which is
 * called after each move. Works for a column of rows and a line of chips alike, since it goes by which
 * sibling is under the pointer rather than by an axis.
 */
internal object DragReorder {

    fun install(handle: JComponent, item: JComponent, onMoved: () -> Unit) {
        handle.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val listener = object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val parent = item.parent as? JComponent ?: return
                val point = SwingUtilities.convertPoint(e.component, e.point, parent)
                val target = parent.components.indexOfFirst { it !== item && it.bounds.contains(point) }
                if (target < 0) return
                parent.remove(item)
                parent.add(item, target)
                parent.revalidate()
                parent.repaint()
                onMoved()
            }
        }
        handle.addMouseListener(listener)
        handle.addMouseMotionListener(listener)
    }
}
