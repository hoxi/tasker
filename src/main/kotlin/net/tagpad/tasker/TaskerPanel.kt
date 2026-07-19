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
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import com.intellij.openapi.progress.Task as ProgressTask

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
    private val treeTable = TreeTable(treeModel)
    private val detailsPanel = TaskDetailsPanel()

    private var cache: List<ServerGroup> = emptyList()
    private var groupByServer: Boolean = true
    private var sortColumn: Int? = null
    private var sortAscending: Boolean = true

    init {
        treeTable.setRootVisible(false)
        treeTable.tree.showsRootHandles = true
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
        val widths = intArrayOf(160, 100, 500, 150, 150)
        for (i in widths.indices) {
            treeTable.columnModel.getColumn(i).preferredWidth = widths[i]
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
                            val issues = repo.getIssues(null, 0, 50, false, indicator)
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
                    .map { DefaultMutableTreeNode(TaskNode(it)) }
                    .let { nodes -> comparator?.let(nodes::sortedWith) ?: nodes }
                    .forEach { serverNode.add(it) }
                root.add(serverNode)
            }
        } else {
            cache.flatMap { it.tasks }
                .map { DefaultMutableTreeNode(TaskNode(it)) }
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
                    icon = task.icon
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
}
