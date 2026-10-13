package net.tagpad.tasker

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.SwingUtilities

/**
 * Lays its children out in a grid of equal columns, as many as fit the width between [minColumns] and
 * [maxColumns], each at least as wide as the widest child wants. Rows are as tall as their tallest child.
 *
 * The number of columns follows the width, and the height follows the number of columns, so a resize that
 * changes the count lays the container out again: its parent asked for its height before it knew the width.
 */
internal class FieldGridLayout(
    private val minColumns: Int,
    private val maxColumns: Int,
    hgap: Int,
    vgap: Int,
) : LayoutManager {

    private val hgap = JBUI.scale(hgap)
    private val vgap = JBUI.scale(vgap)

    /** The column count the last preferred size was worked out for. */
    private var measuredColumns = -1

    override fun addLayoutComponent(name: String?, comp: Component) {}
    override fun removeLayoutComponent(comp: Component) {}

    override fun preferredLayoutSize(parent: Container): Dimension = size(parent) { it.preferredSize }

    override fun minimumLayoutSize(parent: Container): Dimension = size(parent) { it.minimumSize }

    override fun layoutContainer(parent: Container) {
        val children = visible(parent)
        val insets = parent.insets
        val width = parent.width - insets.left - insets.right
        val columns = columnsFor(parent)
        if (columns != measuredColumns) SwingUtilities.invokeLater { parent.revalidate() }

        val cellWidth = ((width - hgap * (columns - 1)) / columns).coerceAtLeast(0)
        var y = insets.top
        for (row in children.chunked(columns)) {
            val height = row.maxOf { it.preferredSize.height }
            row.forEachIndexed { i, child ->
                child.setBounds(insets.left + i * (cellWidth + hgap), y, cellWidth, height)
            }
            y += height + vgap
        }
    }

    private fun size(parent: Container, sizeOf: (Component) -> Dimension): Dimension {
        val children = visible(parent)
        val insets = parent.insets
        val columns = columnsFor(parent).also { measuredColumns = it }
        val cellWidth = children.maxOfOrNull { sizeOf(it).width } ?: 0
        val rows = children.chunked(columns)
        val height = rows.sumOf { row -> row.maxOf { sizeOf(it).height } } + vgap * (rows.size - 1).coerceAtLeast(0)
        // Only as wide as the fewest columns need, so the grid never holds its parent wider than that.
        return Dimension(
            insets.left + insets.right + cellWidth * minColumns + hgap * (minColumns - 1),
            insets.top + insets.bottom + height,
        )
    }

    private fun columnsFor(parent: Container): Int {
        val insets = parent.insets
        val cellWidth = visible(parent).maxOfOrNull { it.preferredSize.width } ?: 0
        return columnsFitting(parent.width - insets.left - insets.right, cellWidth, hgap, minColumns, maxColumns)
    }

    private fun visible(parent: Container) = parent.components.filter { it.isVisible }

    companion object {
        /** How many columns of [cellWidth], [gap] apart, fit in [width]; [min] before the container has a width. */
        fun columnsFitting(width: Int, cellWidth: Int, gap: Int, min: Int, max: Int): Int {
            if (width <= 0 || cellWidth <= 0) return min
            return ((width + gap) / (cellWidth + gap)).coerceIn(min, max)
        }
    }
}
