package net.tagpad.tasker.sort.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/**
 * Lets an item be dragged to another place among its siblings, by a handle on it.
 *
 * Nothing moves while the item is carried: it is tinted where it stands and a line marks where it would land,
 * both painted on a layer above the window. Only on release is the item moved, once, and [install]'s
 * `onDropped` called so the caller can read the new order back from the parent's children. Moving the real
 * component under the pointer on every drag step is what made earlier versions jump about. Works for a column
 * of rows and for chips on wrapping lines alike — see [dropSlot].
 */
internal object DragReorder {

    fun install(handle: JComponent, item: JComponent, onDropped: () -> Unit) {
        handle.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val listener = object : MouseAdapter() {
            private var pressedAt: Point? = null
            private var overlay: Overlay? = null
            private var target = -1

            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                pressedAt = e.point
                target = -1
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = pressedAt ?: return
                // A click that wobbles a pixel or two is still a click.
                if (overlay == null && start.distance(e.point) < JBUI.scale(DRAG_THRESHOLD)) return
                val parent = item.parent as? JComponent ?: return
                val layers = SwingUtilities.getRootPane(parent)?.layeredPane ?: return
                val shown = overlay ?: Overlay().also {
                    it.bounds = Rectangle(0, 0, layers.width, layers.height)
                    layers.add(it, JLayeredPane.DRAG_LAYER)
                    overlay = it
                }
                val peers = peers(item)
                val from = peers.indexOf(item)
                val pointer = SwingUtilities.convertPoint(e.component, e.point, parent)
                val bounds = peers.map { it.bounds }
                val slot = dropSlot(bounds, pointer)
                target = targetIndex(from, slot)
                shown.carried = SwingUtilities.convertRectangle(parent, item.bounds, layers)
                shown.marker = if (target == from) null
                else SwingUtilities.convertRectangle(parent, marker(bounds, slot, pointer, JBUI.scale(2)), layers)
                shown.repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                pressedAt = null
                val shown = overlay ?: return
                overlay = null
                shown.parent?.let { layers ->
                    layers.remove(shown)
                    layers.repaint()
                }
                if (moveTo(item, target)) onDropped()
            }
        }
        handle.addMouseListener(listener)
        handle.addMouseMotionListener(listener)
    }

    /** Only among its own kind: a chip mustn't land after the "+" button that follows the chips. */
    private fun peers(item: JComponent): List<Component> =
        item.parent.components.filter { it.javaClass == item.javaClass }

    /** Puts [item] at [target] among its peers; false if that is where it already is. */
    private fun moveTo(item: JComponent, target: Int): Boolean {
        val parent = item.parent as? JComponent ?: return false
        val peers = peers(item)
        if (target !in peers.indices || peers[target] === item) return false
        val others = peers - item
        parent.remove(item)
        val at = if (target < others.size) parent.components.indexOf(others[target])
        else parent.components.indexOf(others.last()) + 1
        parent.add(item, at)
        parent.revalidate()
        parent.repaint()
        return true
    }

    /**
     * The gap among siblings laid out at [bounds] that [point] is in: 0 before the first, `bounds.size` after
     * the last. A column of rows splits at each row's vertical middle; chips on wrapping lines split at each
     * chip's horizontal middle on the pointer's line, with every chip on an earlier line before it.
     */
    fun dropSlot(bounds: List<Rectangle>, point: Point): Int =
        if (isWrapping(bounds)) {
            bounds.count { it.maxY <= point.y || (point.y >= it.y && point.y < it.maxY && point.x >= it.centerX) }
        } else {
            bounds.count { point.y >= it.centerY }
        }

    /** Where the item at [from] ends up when dropped into [slot] — the gaps either side of it leave it put. */
    fun targetIndex(from: Int, slot: Int): Int = if (slot > from) slot - 1 else slot

    /**
     * The line showing [slot]: across the column between two rows, or upright between two chips. A slot at
     * the end of one line is also the start of the next; the line goes where the [point]er is.
     */
    fun marker(bounds: List<Rectangle>, slot: Int, point: Point, thickness: Int): Rectangle {
        val before = bounds.getOrNull(slot - 1)
        val after = bounds.getOrNull(slot)
        if (!isWrapping(bounds)) {
            val left = bounds.minOf { it.x }
            val width = bounds.maxOf { it.maxX.toInt() } - left
            val y = when {
                before == null -> after!!.y
                after == null -> before.maxY.toInt()
                else -> (before.maxY.toInt() + after.y) / 2
            }
            return Rectangle(left, y - thickness / 2, width, thickness)
        }
        val sameLine = before != null && after != null && before.y == after.y
        val x: Int
        val line: Rectangle
        when {
            sameLine -> {
                x = (before.maxX.toInt() + after.x) / 2
                line = after
            }
            after != null && (before == null || point.y >= after.y) -> {
                x = after.x
                line = after
            }
            else -> {
                x = before!!.maxX.toInt()
                line = before
            }
        }
        return Rectangle(x - thickness / 2, line.y, thickness, line.height)
    }

    /** Chips share lines; rows each have one to themselves. */
    private fun isWrapping(bounds: List<Rectangle>): Boolean =
        bounds.indices.any { i -> (i + 1 until bounds.size).any { j -> sharesLine(bounds[i], bounds[j]) } }

    private fun sharesLine(a: Rectangle, b: Rectangle): Boolean = a.y < b.maxY && b.y < a.maxY

    private const val DRAG_THRESHOLD = 4

    /** Paints the carried item's tint and the drop line above everything; never takes the mouse. */
    private class Overlay : JComponent() {
        var carried: Rectangle? = null
        var marker: Rectangle? = null

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(6)
                carried?.let {
                    g2.color = JBUI.CurrentTheme.DragAndDrop.ROW_BACKGROUND
                    g2.fillRoundRect(it.x, it.y, it.width, it.height, arc, arc)
                }
                marker?.let {
                    g2.color = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR
                    g2.fillRect(it.x, it.y, it.width, it.height)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
