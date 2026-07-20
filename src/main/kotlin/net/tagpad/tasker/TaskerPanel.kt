package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.tasks.Task
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepository
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.CellRendererPane
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableColumnModel
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.table.TableColumnModel
import javax.swing.tree.DefaultMutableTreeNode

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

    private class ServerGroup(
        val repository: TaskRepository,
        val tasks: List<Task>,
        val error: String?,
        val loading: Boolean = false,
    )

    private val columns: Array<ColumnInfo<DefaultMutableTreeNode, *>> = taskerColumns()
    private val root = DefaultMutableTreeNode()
    private val treeModel = ListTreeTableModelOnColumns(root, columns)
    private val treeTable = SpanningTreeTable(treeModel)
    private val detailsPanel = TaskDetailsPanel()

    private var cache: List<ServerGroup> = emptyList()
    private var groupByServer: Boolean = true
    private var includeClosed: Boolean = false
    private var issueLimit: Int = DEFAULT_LIMIT
    private var sortColumn: Int? = null
    private var sortAscending: Boolean = true

    /** Incremented on every (re)load so results from superseded loads can be discarded. */
    private var loadGeneration: Int = 0
    /** Incremented on every selection so stale lazy detail fetches can be discarded. */
    private var detailRequestId: Int = 0
    /** Task currently shown in the details pane; avoids redundant reloads when selection is restored. */
    private var currentDetailTaskId: String? = null
    /** True while the tree is being rebuilt, so selection churn from reload() doesn't refetch details. */
    private var rebuilding: Boolean = false

    private companion object {
        const val ID_COLUMN = 0
        const val STATUS_COLUMN = 1
        const val DEFAULT_LIMIT = 30
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

        // Max-issues spinner: how many issues to request per server (lower = faster loads).
        val spinner = JBIntSpinner(issueLimit, 1, 500)
        spinner.addChangeListener {
            val value = spinner.number
            if (value != issueLimit) {
                issueLimit = value
                refresh()
            }
        }
        val limitPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel("Max:"))
            add(spinner)
        }

        return JPanel(BorderLayout()).apply {
            add(actionToolbar.component, BorderLayout.WEST)
            add(limitPanel, BorderLayout.EAST)
        }
    }

    /**
     * Loads every server in parallel and fills the tree incrementally as each responds, so one slow
     * server never blocks the others. Configured servers first appear as "loading"; unconfigured ones
     * are flagged. Results from a superseded load (newer refresh) are discarded via [loadGeneration].
     */
    private fun refresh() {
        val generation = ++loadGeneration
        val limit = issueLimit
        val includeClosedNow = includeClosed
        val repositories = TaskManager.getManager(project).allRepositories.toList()

        // Seed the cache so configured servers show up immediately as "loading".
        cache = repositories.map { repo ->
            ServerGroup(repo, emptyList(), if (repo.isConfigured) null else "not configured", loading = repo.isConfigured)
        }
        rebuild()

        for (repo in repositories) {
            if (!repo.isConfigured) continue
            AppExecutorUtil.getAppExecutorService().execute {
                val result = try {
                    val issues = repo.getIssues(null, 0, limit, includeClosedNow, EmptyProgressIndicator())
                    ServerGroup(repo, issues.toList(), null)
                } catch (ce: ProcessCanceledException) {
                    return@execute
                } catch (ex: Exception) {
                    ServerGroup(repo, emptyList(), ex.message ?: ex.javaClass.simpleName)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (generation != loadGeneration) return@invokeLater // superseded by a newer refresh
                    cache = cache.map { if (it.repository === repo) result else it }
                    rebuild()
                }
            }
        }
    }

    /** Rebuild the tree from [cache] using the current grouping + sort. No refetch. */
    private fun rebuild() {
        val selectedId = selectedTaskNode()?.task?.id
        rebuilding = true
        try {
            root.removeAllChildren()
            if (cache.isEmpty()) {
                root.add(DefaultMutableTreeNode("No task servers configured"))
                treeModel.reload()
                return
            }

            val comparator: Comparator<DefaultMutableTreeNode>? = sortColumn?.let { col ->
                columns[col].comparator?.let { if (sortAscending) it else it.reversed() }
            }

            if (groupByServer) {
                for (serverGroup in cache) {
                    val serverNode =
                        DefaultMutableTreeNode(ServerNode(serverGroup.repository, serverGroup.error, serverGroup.loading))
                    serverGroup.tasks
                        .map { DefaultMutableTreeNode(TaskNode(it, serverGroup.repository)) }
                        .let { nodes -> comparator?.let(nodes::sortedWith) ?: nodes }
                        .forEach { serverNode.add(it) }
                    root.add(serverNode)
                }
            } else {
                val taskNodes = cache.flatMap { group -> group.tasks.map { TaskNode(it, group.repository) } }
                    .map { DefaultMutableTreeNode(it) }
                    .let { nodes -> comparator?.let(nodes::sortedWith) ?: nodes }
                taskNodes.forEach { root.add(it) }
                if (taskNodes.isEmpty()) {
                    root.add(DefaultMutableTreeNode(if (cache.any { it.loading }) "Loading…" else "No tasks"))
                }
            }

            treeModel.reload()
            expandAll()
            if (selectedId != null) reselectTask(selectedId)
        } finally {
            rebuilding = false
        }
        // Reconcile the details pane once, after the tree is stable (no-op if the same task is still selected).
        updateDetailsFromSelection()
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

    private fun selectedTaskNode(): TaskNode? {
        val row = treeTable.selectedRow
        if (row < 0) return null
        val node = treeTable.tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
        return node?.userObject as? TaskNode
    }

    private fun reselectTask(taskId: String) {
        val tree = treeTable.tree
        for (row in 0 until tree.rowCount) {
            val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
            if ((node?.userObject as? TaskNode)?.task?.id == taskId) {
                treeTable.selectionModel.setSelectionInterval(row, row)
                return
            }
        }
    }

    /**
     * Shows the selected task in the details pane. Fields we already have render immediately; if the
     * task carries no comments yet, they (and a fuller description) are fetched lazily via
     * [TaskRepository.findTask] off the EDT — so the list load never pays for comments it may not need.
     */
    private fun updateDetailsFromSelection() {
        if (rebuilding) return // ignore selection churn while the tree is being rebuilt
        val node = selectedTaskNode()
        val taskId = node?.task?.id
        if (taskId == currentDetailTaskId) return // selection restored to the same task; nothing to do
        currentDetailTaskId = taskId

        val requestId = ++detailRequestId
        if (node == null) {
            detailsPanel.show(null)
            return
        }

        val task = node.task
        val repository = node.repository
        val hasComments = task.comments.isNotEmpty()
        detailsPanel.show(task, loadingComments = !hasComments)
        if (hasComments) return // e.g. GitHub already loaded comments during the list fetch

        AppExecutorUtil.getAppExecutorService().execute {
            val full = try {
                repository.findTask(task.id)
            } catch (ce: ProcessCanceledException) {
                return@execute
            } catch (ex: Exception) {
                null
            }
            ApplicationManager.getApplication().invokeLater {
                if (requestId != detailRequestId) return@invokeLater // selection moved on
                detailsPanel.show(full ?: task, loadingComments = false)
            }
        }
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
                    when {
                        obj.loading -> append("  (loading…)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        obj.error != null -> append("  (${obj.error})", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        else -> append("  (${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
}
