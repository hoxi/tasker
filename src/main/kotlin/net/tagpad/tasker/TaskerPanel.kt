package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.tasks.Task
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepository
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.CellRendererPane
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
import com.intellij.openapi.progress.Task as ProgressTask

/** Column model that keeps the first (ID / tree) column pinned at the far left; other columns may reorder. */
private class PinnedFirstColumnModel : DefaultTableColumnModel() {
    override fun moveColumn(columnIndex: Int, newIndex: Int) {
        if (columnIndex != newIndex && (columnIndex == 0 || newIndex == 0)) return
        super.moveColumn(columnIndex, newIndex)
    }
}

/**
 * Tool window content: a multi-column [TreeTable] of tasks (ID / Status / Summary / Updated / Created)
 * with a details pane on the right. Tasks can be grouped per server or shown as one merged list;
 * clicking a column header sorts — within each server group when grouped, globally when flat.
 */
class TaskerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private class ServerGroup(val repository: TaskRepository, val tasks: List<Task>, val error: String?)

    private val columns: Array<ColumnInfo<DefaultMutableTreeNode, *>> = taskerColumns()
    private val root = DefaultMutableTreeNode()
    private val treeModel = ListTreeTableModelOnColumns(root, columns)
    private val treeTable = SpanningTreeTable(treeModel)
    private val detailsPanel = TaskDetailsPanel()

    private var cache: List<ServerGroup> = emptyList()
    private var groupByServer: Boolean = true
    private var includeClosed: Boolean = false
    private var sortColumn: Int? = null
    private var sortAscending: Boolean = true

    private companion object {
        const val ID_COLUMN = 0
        const val STATUS_COLUMN = 1
    }

    init {
        treeTable.setRootVisible(false)
        treeTable.tree.showsRootHandles = true
        treeTable.setRowHeight(JBUI.scale(24))
        treeTable.setTreeCellRenderer(IdCellRenderer())
        treeTable.tableHeader.defaultRenderer = SortableHeaderRenderer(treeTable.tableHeader.defaultRenderer)
        treeTable.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onHeaderClicked(e)
        })
        treeTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) updateDetailsFromSelection()
        }
        setColumnWidths()

        val splitter = OnePixelSplitter(false, 0.6f).apply {
            firstComponent = JBScrollPane(treeTable)
            secondComponent = detailsPanel
        }
        toolbar = createToolbar()
        setContent(splitter)

        showMessage("Loading…")
        refresh()
    }

    private fun setColumnWidths() {
        val widths = intArrayOf(160, 110, 500, 150, 150)
        for (i in widths.indices) {
            val column = treeTable.columnModel.getColumn(i)
            column.preferredWidth = widths[i]
            // A plain TreeTable ignores ColumnInfo.getRenderer, so install the badge renderer here.
            // Match by model index so it survives column reordering.
            if (column.modelIndex == STATUS_COLUMN) {
                column.cellRenderer = StatusCellRenderer()
            }
        }
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Reload tasks from all configured servers", AllIcons.Actions.Refresh) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })
        group.add(object : ToggleAction("Group by Server", "Group tasks under their task server", AllIcons.Actions.GroupBy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = groupByServer
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupByServer = state
                rebuild()
            }
        })
        group.add(object : ToggleAction("Show Closed Issues", "Also fetch and show closed/resolved issues", AllIcons.Actions.ToggleVisibility) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = includeClosed
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                includeClosed = state
                refresh() // closed issues aren't in the cache — refetch from the servers
            }
        })
        val actionToolbar = ActionManager.getInstance().createActionToolbar("TaskerToolbar", group, true)
        actionToolbar.targetComponent = treeTable
        return actionToolbar.component
    }

    private fun refresh() {
        ProgressManager.getInstance().run(
            object : ProgressTask.Backgroundable(project, "Loading tasks from servers", true) {
                override fun run(indicator: ProgressIndicator) {
                    val manager = TaskManager.getManager(project)
                    val groups = ArrayList<ServerGroup>()
                    for (repo in manager.allRepositories) {
                        indicator.checkCanceled()
                        indicator.text = "Loading ${repo.presentableName}…"
                        if (!repo.isConfigured) {
                            groups.add(ServerGroup(repo, emptyList(), "not configured"))
                            continue
                        }
                        try {
                            val issues = repo.getIssues(null, 0, 50, includeClosed, indicator)
                            groups.add(ServerGroup(repo, issues.toList(), null))
                        } catch (ce: ProcessCanceledException) {
                            throw ce
                        } catch (ex: Exception) {
                            groups.add(ServerGroup(repo, emptyList(), ex.message ?: ex.javaClass.simpleName))
                        }
                    }
                    ApplicationManager.getApplication().invokeLater {
                        cache = groups
                        rebuild()
                    }
                }
            }
        )
    }

    /** Rebuild the tree from [cache] using the current grouping + sort. No refetch. */
    private fun rebuild() {
        root.removeAllChildren()
        if (cache.isEmpty()) {
            root.add(DefaultMutableTreeNode("No task servers configured"))
            treeModel.reload()
            detailsPanel.show(null)
            return
        }

        val comparator: Comparator<DefaultMutableTreeNode>? = sortColumn?.let { col ->
            columns[col].comparator?.let { if (sortAscending) it else it.reversed() }
        }

        if (groupByServer) {
            for (serverGroup in cache) {
                val serverNode = DefaultMutableTreeNode(ServerNode(serverGroup.repository, serverGroup.error))
                serverGroup.tasks
                    .map { DefaultMutableTreeNode(TaskNode(it, serverGroup.repository)) }
                    .let { nodes -> comparator?.let(nodes::sortedWith) ?: nodes }
                    .forEach { serverNode.add(it) }
                root.add(serverNode)
            }
        } else {
            cache.flatMap { group -> group.tasks.map { TaskNode(it, group.repository) } }
                .map { DefaultMutableTreeNode(it) }
                .let { nodes -> comparator?.let(nodes::sortedWith) ?: nodes }
                .forEach { root.add(it) }
        }

        treeModel.reload()
        expandAll()
        detailsPanel.show(null)
    }

    private fun expandAll() {
        val tree = treeTable.tree
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }

    private fun showMessage(message: String) {
        root.removeAllChildren()
        root.add(DefaultMutableTreeNode(message))
        treeModel.reload()
    }

    private fun onHeaderClicked(e: MouseEvent) {
        val header = treeTable.tableHeader
        val viewColumn = header.columnAtPoint(e.point)
        if (viewColumn < 0) return
        val modelColumn = treeTable.convertColumnIndexToModel(viewColumn)
        if (columns[modelColumn].comparator == null) return
        if (sortColumn == modelColumn) {
            sortAscending = !sortAscending
        } else {
            sortColumn = modelColumn
            sortAscending = true
        }
        rebuild()
        header.repaint()
    }

    private fun updateDetailsFromSelection() {
        val row = treeTable.selectedRow
        val task = if (row < 0) null else {
            val path = treeTable.tree.getPathForRow(row)
            (path?.lastPathComponent as? DefaultMutableTreeNode)?.task()
        }
        detailsPanel.show(task)
    }

    /** Renders the tree column (ID): server name for group rows, presentable id for task rows. */
    private inner class IdCellRenderer : ColoredTreeCellRenderer() {
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
                    if (obj.error != null) {
                        append("  (${obj.error})", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    } else {
                        append("  (${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }

                is TaskNode -> {
                    val task = obj.task
                    // When flat (ungrouped), prepend the server icon so the task's origin is visible;
                    // when grouped, the server icon already sits on the parent header row.
                    icon = if (groupByServer) task.icon else obj.repository.icon
                    append(
                        task.presentableId,
                        if (task.isClosed) SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    )
                }

                is String -> append(obj, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    /** Wraps the default header renderer to draw an up/down arrow on the active sort column. */
    private inner class SortableHeaderRenderer(private val delegate: TableCellRenderer) : TableCellRenderer {
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
                    modelColumn != sortColumn -> null
                    sortAscending -> AllIcons.General.ArrowUp
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
    private inner class SpanningTreeTable(model: TreeTableModel) : TreeTable(model) {

        private val rendererPane = CellRendererPane()
        private val spanComponent = SimpleColoredComponent()

        init {
            add(rendererPane)
        }

        override fun createDefaultColumnModel(): TableColumnModel = PinnedFirstColumnModel()

        // Refuse to start a drag on the pinned ID column: returning without setting the dragged
        // column means the header never animates it, so there is no flicker/snap-back.
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
                            rendererPane.paintComponent(g, component, this, contentX, rowRect.y, spanWidth, rowRect.height, true)
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
                    val suffix = obj.error?.let { "  ($it)" } ?: "  (${node.childCount})"
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
}
