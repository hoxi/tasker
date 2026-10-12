package net.tagpad.tasker.sort.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Lets an item in a [ReorderPanel] be dragged to another place among its siblings, by a handle on it.
 *
 * Nothing moves while the item is carried: the panel tints it where it stands and draws a line where it would
 * land, in its own coordinates, over its children. Only on release is the item moved, once, and [install]'s
 * `onDropped` called so the caller can read the new order back from the panel's children. Works for a column
 * of rows and for chips on wrapping lines alike — see [dropSlot].
 */
internal object DragReorder {

    fun install(handle: JComponent, item: JComponent, onDropped: () -> Unit) {
        handle.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val listener = object : MouseAdapter() {
            private var pressedAt: Point? = null
            private var dragging = false
            private var target = -1

            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                pressedAt = e.point
                dragging = false
                target = -1
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = pressedAt ?: return
                // A click that wobbles a pixel or two is still a click.
                if (!dragging && start.distance(e.point) < JBUI.scale(DRAG_THRESHOLD)) return
                val panel = item.parent as? ReorderPanel ?: return
                dragging = true
                val peers = peers(item)
                val from = peers.indexOf(item)
                val bounds = peers.map { it.bounds }
                val pointer = SwingUtilities.convertPoint(e.component, e.point, panel)
                val grabbed = SwingUtilities.convertPoint(e.component, start, panel)
                // Where the item would be drawn if it followed the pointer.
                val dragged = Rectangle(item.bounds).apply { translate(pointer.x - grabbed.x, pointer.y - grabbed.y) }
                target = dropTarget(bounds, from, dragged, pointer)
                panel.showDrag(
                    carried = item.bounds,
                    marker = if (target == from) null
                    else marker(bounds, slotFor(from, target), pointer, JBUI.scale(2)),
                )
            }

            override fun mouseReleased(e: MouseEvent) {
                pressedAt = null
                if (!dragging) return
                dragging = false
                (item.parent as? ReorderPanel)?.showDrag(carried = null, marker = null)
                if (moveTo(item, target)) onDropped()
            }
        }
        handle.addMouseListener(listener)
        handle.addMouseMotionListener(listener)
    }

    /** Only among its own kind: a chip mustn't land after the "+" button that follows the chips. */
    private fun peers(item: JComponent): List<Component> =
        item.parent.components.filter { it.javaClass == item.javaClass }

    /**
     * Puts [item] at [target] among its peers; false if that is where it already is.
     *
     * Every child is re-added in the new order rather than the item alone at an index: some layouts keep
     * their own list of children and append whatever is added to it, whatever the index — IntelliJ's
     * `VerticalLayout` does — which left the row drawn where it was while the panel's order said otherwise.
     */
    private fun moveTo(item: JComponent, target: Int): Boolean {
        val parent = item.parent as? JComponent ?: return false
        val peers = peers(item)
        if (target !in peers.indices || peers[target] === item) return false
        val others = peers - item
        val children = parent.components.toMutableList().apply { remove(item) }
        val at = if (target < others.size) children.indexOf(others[target]) else children.indexOf(others.last()) + 1
        children.add(at, item)
        parent.removeAll()
        children.forEach(parent::add)
        parent.revalidate()
        parent.repaint()
        return true
    }

    /**
     * Where the item at [from] lands among siblings laid out at [bounds], carried to [dragged] by the pointer
     * now at [point].
     *
     * In a column it passes a row once its leading edge crosses that row's middle — its bottom edge going down,
     * its top edge going up — so rows of any height swap after the same feel of movement. Going by the pointer
     * instead, a tall row would have to be dragged most of its own height before the next row gave way, and a
     * short one dragged deep into a tall neighbour. Chips, alike in size, go by the pointer: see [dropSlot].
     */
    fun dropTarget(bounds: List<Rectangle>, from: Int, dragged: Rectangle, point: Point): Int {
        if (isWrapping(bounds)) return targetIndex(from, dropSlot(bounds, point))
        val down = (from + 1 until bounds.size).takeWhile { dragged.maxY >= bounds[it].centerY }.count()
        if (down > 0) return from + down
        return from - (from - 1 downTo 0).takeWhile { dragged.y <= bounds[it].centerY }.count()
    }

    /** The gap the item at [from] goes into to end up at [target]: past it going down, before it going up. */
    fun slotFor(from: Int, target: Int): Int = if (target > from) target + 1 else target

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
     * The line showing [slot]: across the column between two rows, or upright between two chips, kept within
     * the siblings' extent so it isn't clipped at either end. A slot at the end of one line is also the start
     * of the next; the line goes where the [point]er is.
     */
    fun marker(bounds: List<Rectangle>, slot: Int, point: Point, thickness: Int): Rectangle {
        val extent = bounds.reduce(Rectangle::union)
        val before = bounds.getOrNull(slot - 1)
        val after = bounds.getOrNull(slot)
        if (!isWrapping(bounds)) {
            val y = when {
                before == null -> after!!.y
                after == null -> before.maxY.toInt()
                else -> (before.maxY.toInt() + after.y) / 2
            }
            val top = clamp(y - thickness / 2, extent.y, extent.maxY.toInt() - thickness)
            return Rectangle(extent.x, top, extent.width, thickness)
        }
        val (x, line) = when {
            before != null && after != null && sharesLine(before, after) ->
                (before.maxX.toInt() + after.x) / 2 to after
            after != null && (before == null || point.y >= after.y) -> after.x to after
            else -> before!!.maxX.toInt() to before
        }
        val left = clamp(x - thickness / 2, extent.x, extent.maxX.toInt() - thickness)
        return Rectangle(left, line.y, thickness, line.height)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, maxOf(min, max))

    /** Chips share lines; rows each have one to themselves. */
    private fun isWrapping(bounds: List<Rectangle>): Boolean =
        bounds.indices.any { i -> (i + 1 until bounds.size).any { j -> sharesLine(bounds[i], bounds[j]) } }

    private fun sharesLine(a: Rectangle, b: Rectangle): Boolean = a.y < b.maxY && b.y < a.maxY

    private const val DRAG_THRESHOLD = 4
}

/**
 * A panel whose items [DragReorder] can drag into a new order; it paints the drag over its children. Its
 * [layout] must place children in the order they were added, without constraints.
 */
internal class ReorderPanel(layout: LayoutManager) : JPanel(layout) {

    private var carried: Rectangle? = null
    private var marker: Rectangle? = null

    init {
        isOpaque = false
    }

    fun showDrag(carried: Rectangle?, marker: Rectangle?) {
        if (carried == this.carried && marker == this.marker) return
        this.carried = carried
        this.marker = marker
        repaint()
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        if (carried == null && marker == null) return
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
