package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import java.awt.Component
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.CellRendererPane
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableColumnModel
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.table.TableColumnModel
import javax.swing.tree.DefaultMutableTreeNode

// The task table's rendering: the tree table itself, its cell and header renderers, and their helpers.

/** The ID column, which carries the tree and stays pinned at the far left. */
private const val ID_COLUMN = 0

/** Column model that keeps the first (ID / tree) column pinned at the far left; other columns may reorder. */
private class PinnedFirstColumnModel : DefaultTableColumnModel() {
    override fun moveColumn(columnIndex: Int, newIndex: Int) {
        if (columnIndex != newIndex && (columnIndex == 0 || newIndex == 0)) return
        super.moveColumn(columnIndex, newIndex)
    }
}

/**
 * Paints [delegate], or [fallback] while [delegate] reports no size.
 * Jira's connector loads issue-type icons with `ImageIcon(url)`, and Jira Cloud serves them as SVG,
 * which ImageIcon cannot decode — the result is a -1x-1 icon that the platform's selection painting
 * reports as an error. Hiding the delegate behind this wrapper keeps the platform from inspecting it.
 */
internal class FallbackIcon(private val delegate: Icon, private val fallback: Icon) : Icon {
    private fun current(): Icon = if (delegate.iconWidth > 0 && delegate.iconHeight > 0) delegate else fallback

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = current().paintIcon(c, g, x, y)
    override fun getIconWidth(): Int = current().iconWidth
    override fun getIconHeight(): Int = current().iconHeight
}

/**
 * Renders the tree column (ID): server name for group rows, presentable id for task rows. [isActive]
 * tells it the task the IDE is on, [grouped] whether rows sit under their server, and [taskIcon] the
 * icon to show for a task its decoration has none for.
 */
internal class IdCellRenderer(
    private val isActive: (TaskNode) -> Boolean,
    private val grouped: () -> Boolean,
    private val taskIcon: (TaskNode) -> Icon,
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is ServerNode -> {
                icon = obj.repository.icon
                append(obj.repository.presentableName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                when {
                    obj.loading -> append("  (loading…)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    obj.error != null -> append("  (${obj.error})", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    else -> append("  (${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is TaskNode -> {
                val task = obj.task
                // The task the IDE is currently on, which the context menu can now switch.
                val active = isActive(obj)
                /*
                 * The active task gets an arrow in the icon slot, where the eye already is: bold alone
                 * proved far too quiet on an id as short as "PC-1". Otherwise, when flat, the server
                 * icon shows the task's origin; when grouped, that already sits on the header row.
                 */
                icon = when {
                    active -> AllIcons.Actions.Forward
                    grouped() -> obj.decoration?.icon ?: taskIcon(obj)
                    else -> obj.repository.icon
                }
                append(
                    task.presentableId,
                    when {
                        active -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        task.isClosed -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    },
                )
            }

            is String -> append(obj, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

/** Wraps the default header renderer to draw an up/down arrow on the column [sortColumn] names. */
internal class SortableHeaderRenderer(
    private val delegate: TableCellRenderer,
    private val sortColumn: () -> Int?,
    private val ascending: () -> Boolean,
) : TableCellRenderer {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val component = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (component is JLabel) {
            val modelColumn = table.convertColumnIndexToModel(column)
            component.horizontalTextPosition = SwingConstants.LEFT
            component.icon = when {
                modelColumn != sortColumn() -> null
                ascending() -> AllIcons.General.ArrowUp
                else -> AllIcons.General.ArrowDown
            }
        }
        return component
    }
}

/**
 * TreeTable that paints server/message rows across the full width (independent of the columns).
 * After the normal cell painting, it overpaints such rows with a single component starting at the
 * tree node's content x (from [getPathBounds]) — so the content spans every column while the
 * expand/collapse handle, which sits to the left of that x, stays visible.
 */
internal class SpanningTreeTable(model: TreeTableModel) : TreeTable(model) {

    private val rendererPane = CellRendererPane()
    private val spanComponent = SimpleColoredComponent()

    init {
        add(rendererPane)
    }

    override fun createDefaultColumnModel(): TableColumnModel = PinnedFirstColumnModel()

    /**
     * Refuses to start a drag on the pinned ID column: returning without setting the dragged column
     * means the header never animates it, so there is no flicker/snap-back.
     */
    override fun createDefaultTableHeader(): JTableHeader = object : JBTableHeader() {
        override fun setDraggedColumn(column: TableColumn?) {
            if (column != null && columnModel.columnCount > 0 && column === columnModel.getColumn(ID_COLUMN)) {
                super.setDraggedColumn(null)
                return
            }
            super.setDraggedColumn(column)
        }
    }

    private fun spanNode(row: Int): DefaultMutableTreeNode? {
        val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val obj = node.userObject
        return if (obj is ServerNode || obj is String) node else null
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
        var row = 0
        while (row < rowCount) {
            val node = spanNode(row)
            if (node != null) {
                val rowRect = getCellRect(row, 0, true)
                if (rowRect.y < clip.y + clip.height && rowRect.y + rowRect.height > clip.y) {
                    val contentX = tree.getPathForRow(row)?.let { tree.getPathBounds(it)?.x } ?: rowRect.x
                    val spanWidth = width - contentX
                    if (spanWidth > 0) {
                        val component = prepareSpan(node, row)
                        rendererPane.paintComponent(
                            g, component, this, contentX, rowRect.y, spanWidth, rowRect.height, true,
                        )
                    }
                }
            }
            row++
        }
    }

    private fun prepareSpan(node: DefaultMutableTreeNode, row: Int): JComponent {
        val selected = isRowSelected(row)
        spanComponent.clear()
        spanComponent.isOpaque = true
        spanComponent.background = if (selected) selectionBackground else background
        when (val obj = node.userObject) {
            is ServerNode -> {
                spanComponent.icon = obj.repository.icon
                val nameAttrs =
                    if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, selectionForeground)
                    else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                spanComponent.append(obj.repository.presentableName, nameAttrs)
                val suffix = when {
                    obj.loading -> "  (loading…)"
                    obj.error != null -> "  (${obj.error})"
                    else -> "  (${node.childCount})"
                }
                val suffixAttrs = when {
                    selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, selectionForeground)
                    obj.error != null -> SimpleTextAttributes.ERROR_ATTRIBUTES
                    else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                }
                spanComponent.append(suffix, suffixAttrs)
            }

            is String -> {
                spanComponent.icon = null
                spanComponent.append(obj, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        return spanComponent
    }
}
