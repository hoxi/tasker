package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.IconUtil
import com.intellij.util.ui.CenteredIcon
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import net.tagpad.tasker.sort.SortDirection
import net.tagpad.tasker.sort.SortIndicator
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.BorderFactory
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

/** The size of a task's icon, in unscaled pixels. */
private const val ICON_SIZE = 16

/** Space between a started task's arrow and its icon, in unscaled pixels. */
private const val ARROW_GAP = 4

/** Column model that keeps the first (ID / tree) column pinned at the far left; other columns may reorder. */
private class PinnedFirstColumnModel : DefaultTableColumnModel() {
    override fun moveColumn(columnIndex: Int, newIndex: Int) {
        if (columnIndex != newIndex && (columnIndex == 0 || newIndex == 0)) return
        super.moveColumn(columnIndex, newIndex)
    }
}

/**
 * [icon] at the 16px every other icon in a row is, scaled if a tracker serves it at another size: Jira lets
 * a custom issue type have any image, and the connector loads it at whatever size it was uploaded.
 */
internal fun fittedIcon(icon: Icon): Icon {
    val size = JBUI.scale(ICON_SIZE)
    if (icon.iconWidth <= 0 || icon.iconHeight <= 0 || (icon.iconWidth == size && icon.iconHeight == size)) return icon
    return IconUtil.resizeSquared(icon, ICON_SIZE)
}

/**
 * Paints [delegate], or [fallback] while [delegate] reports no size.
 * Jira's connector loads issue-type icons with `ImageIcon(url)`, and Jira Cloud serves them as SVG,
 * which ImageIcon cannot decode — the result is a -1x-1 icon that the platform's selection painting
 * reports as an error. Hiding the delegate behind this wrapper keeps the platform from inspecting it.
 */
internal class FallbackIcon(private val delegate: Icon, private val fallback: Icon) : Icon {
    private val fitted by lazy { fittedIcon(delegate) }

    private fun current(): Icon = if (delegate.iconWidth > 0 && delegate.iconHeight > 0) fitted else fallback

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = current().paintIcon(c, g, x, y)
    override fun getIconWidth(): Int = current().iconWidth
    override fun getIconHeight(): Int = current().iconHeight
}

/**
 * Where a task stands with Task Management: the one the IDE is on, one started earlier — a local task the
 * IDE can switch back to — or neither.
 */
internal enum class TaskActivity { ACTIVE, STARTED, IDLE }

/**
 * Renders the tree column (ID): server name for group rows, presentable id for task rows. [activity]
 * tells it where a task stands with Task Management, [anyStarted] whether any task has been started, so
 * room for the arrow is kept, [grouped] whether rows sit under their server, and [taskIcon] the icon to
 * show for a task its decoration has none for.
 */
internal class IdCellRenderer(
    private val activity: (TaskNode) -> TaskActivity,
    private val anyStarted: () -> Boolean,
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
                val state = activity(obj)
                val active = state == TaskActivity.ACTIVE
                /*
                 * The active task gets a yellow arrow ahead of its icon, where the eye already is: bold alone
                 * proved far too quiet on an id as short as "PC-1". It is the debugger's "you are here"
                 * arrow, centred in the 16px slot it is 12px of. Tasks started earlier get the plain grey
                 * one, and the rest an empty slot, so the ids line up. A small gap keeps the arrow apart from
                 * the icon.
                 */
                val arrow = when {
                    active -> CenteredIcon(AllIcons.Debugger.NextStatement, JBUI.scale(16), JBUI.scale(16), false)
                    state == TaskActivity.STARTED -> AllIcons.Actions.Forward
                    anyStarted() -> EmptyIcon.ICON_16
                    else -> null
                }
                // When flat, the server icon shows the task's origin; when grouped, that is on the header row.
                val kind = if (grouped()) obj.decoration?.icon ?: taskIcon(obj) else obj.repository.icon
                icon = arrow?.let { RowIcon(it, EmptyIcon.create(ARROW_GAP, 16), kind) } ?: kind
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

/**
 * Wraps the default header renderer to show where each column sits in the sort: an arrow for its direction,
 * and — when several columns sort at once — its place among them, as "Updated 2".
 *
 * [indicator] answers for a column by model index; the renderer doesn't know how the sort is held.
 */
internal class SortableHeaderRenderer(
    private val delegate: TableCellRenderer,
    private val indicator: (modelColumn: Int) -> SortIndicator?,
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
            val sorted = indicator(table.convertColumnIndexToModel(column))
            component.horizontalTextPosition = SwingConstants.LEFT
            component.icon = when (sorted?.direction) {
                null -> null
                SortDirection.ASCENDING -> AllIcons.General.ArrowUp
                SortDirection.DESCENDING -> AllIcons.General.ArrowDown
            }
            if (sorted != null && sorted.ofMany) component.text = "${value ?: ""} ${sorted.position}"
            /*
             * Keep the theme's padding but not its lines. A theme may give TableHeader.cellBorder a colour, boxing
             * every heading, while the header UI already draws the separators between columns.
             */
            component.border?.getBorderInsets(component)?.let {
                component.border = BorderFactory.createEmptyBorder(it.top, it.left, it.bottom, it.right)
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

    /*
     * The selection dims when the table loses focus, as the tree column already does. Every other cell
     * renderer reads these, and Swing's own would otherwise paint the focused colours regardless.
     */
    override fun getSelectionBackground(): Color = RenderingUtil.getSelectionBackground(this)

    override fun getSelectionForeground(): Color = RenderingUtil.getSelectionForeground(this)

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
