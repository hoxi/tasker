package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionGroupWrapper
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.CustomTaskState
import com.intellij.tasks.actions.OpenTaskDialog
import com.intellij.tasks.LocalTask
import com.intellij.tasks.Task
import com.intellij.tasks.TaskListener
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepository
import com.intellij.ui.awt.RelativePoint
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
import javax.swing.Timer
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
class TaskerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private class ServerGroup(
        val repository: TaskRepository,
        val tasks: List<Task>,
        val error: String?,
        val loading: Boolean = false,
    )

    /** Cache key for a resolved task: ids are only unique within a server, so the url is part of the key. */
    private data class DetailKey(val repositoryUrl: String?, val taskId: String)

    private val columns: Array<ColumnInfo<DefaultMutableTreeNode, *>> = taskerColumns()
    private val root = DefaultMutableTreeNode()
    private val treeModel = ListTreeTableModelOnColumns(root, columns)
    private val treeTable = SpanningTreeTable(treeModel)
    private val detailsPanel = TaskDetailsPanel(EditHandler())

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

    /**
     * Tasks whose details (comments + full description) have already been resolved. Membership *is* the
     * loaded-flag: `TaskRepository.findTask` does no caching of its own (it's abstract, and every
     * implementation issues a live request), and an empty comment list is otherwise indistinguishable
     * from "not fetched yet". Cleared by [refresh]. EDT-only.
     */
    private val detailCache = HashMap<DetailKey, Task>()

    /**
     * Adapter-fetched properties, keyed like [detailCache] and cleared alongside it.
     *
     * Kept separate rather than folded into the cached task because the two are resolved independently:
     * GitHub ships comments with the list fetch, which short-circuits the detail request entirely, but
     * says nothing about the assignee. Only successes land here, so a failed read is retried on the next
     * visit instead of sticking as a permanent blank. EDT-only.
     */
    private val propertyCache = HashMap<DetailKey, List<TaskProperty>>()

    /** Pending deferred "Loading comments…" label, if a fetch is in flight. EDT-only. */
    private var loadingLabelTimer: Timer? = null

    /**
     * Whether the pane is currently admitting to a comment fetch.
     *
     * Late-arriving properties re-render the pane, and they have no idea whether the comments they are
     * rendering beside have landed yet. Without this they would quietly replace "Loading…" with "No
     * comments." for as long as the real fetch took. EDT-only.
     */
    private var showingLoadingComments: Boolean = false
    /** True while the tree is being rebuilt, so selection churn from reload() doesn't refetch details. */
    private var rebuilding: Boolean = false

    private companion object {
        const val ID_COLUMN = 0
        const val STATUS_COLUMN = 1

        /** The Task Management plugin's own Tools menu group, reused as a toolbar dropdown. */
        const val TASKS_AND_CONTEXTS_GROUP = "tasks.and.contexts"
        const val DEFAULT_LIMIT = 30

        /** Grace period before admitting to a comment fetch; servers that answer inside it never flash the label. */
        const val LOADING_LABEL_DELAY_MS = 200
    }

    init {
        // Which row is bold depends on state the platform owns, so nothing here would notice it changing
        // — not even a switch made from our own context menu, which goes straight to the TaskManager.
        TaskManager.getManager(project).addTaskListener(object : TaskListener {
            override fun taskActivated(task: LocalTask) = treeTable.repaint()
            override fun taskDeactivated(task: LocalTask) = treeTable.repaint()
            override fun taskAdded(task: LocalTask) = Unit
            override fun taskRemoved(task: LocalTask) = Unit
        }, this)

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
        treeTable.addMouseListener(object : MouseAdapter() {
            // Which of the two carries the popup trigger is platform-dependent, so check both.
            override fun mousePressed(e: MouseEvent) = maybeShowStateMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowStateMenu(e)
        })
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
        // All three are DumbAware for the same reason the tool window is: they refetch from servers or
        // rearrange rows we already hold, and neither needs an index to be ready.
        group.add(object : AnAction("Refresh", "Reload tasks from all configured servers", AllIcons.Actions.Refresh), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })
        group.add(object : ToggleAction("Group by Server", "Group tasks under their task server", AllIcons.Actions.GroupBy), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = groupByServer
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupByServer = state
                rebuild()
            }
        })
        group.add(object : ToggleAction("Show Closed Issues", "Also fetch and show closed/resolved issues", AllIcons.Actions.ToggleVisibility), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = includeClosed
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                includeClosed = state
                refresh() // closed issues aren't in the cache — refetch from the servers
            }
        })
        // Registered by the bundled Task Management plugin, which this plugin depends on outright — so
        // the only way this misses is a rename on JetBrains' side, and then the button simply isn't there.
        (ActionManager.getInstance().getAction(TASKS_AND_CONTEXTS_GROUP) as? ActionGroup)?.let { tasksMenu ->
            group.addSeparator()
            group.add(TasksAndContextsGroup(tasksMenu))
        }

        val actionToolbar = ActionManager.getInstance().createActionToolbar("TaskerToolbar", group, true)
        actionToolbar.targetComponent = treeTable

        // Task limit spinner: how many issues to request per server (lower = faster loads).
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
            add(JBLabel("Task limit:"))
            add(spinner)
        }

        return JPanel(BorderLayout()).apply {
            add(actionToolbar.component, BorderLayout.WEST)
            add(limitPanel, BorderLayout.EAST)
        }
    }

    /**
     * Toolbar shortcut to the Task Management plugin's own "Tasks & Contexts" menu, the one that
     * normally only lives under Tools.
     *
     * [ActionGroupWrapper] rather than a group that fetches the children itself: reading someone else's
     * children means calling [ActionGroup.getChildren], which is marked `@ApiStatus.OverrideOnly`, and
     * the wrapper routes it through the platform's own machinery instead. Everything in the menu stays
     * whatever that plugin registers — this is a second way in, not a reimplementation.
     */
    private class TasksAndContextsGroup(delegate: ActionGroup) : ActionGroupWrapper(delegate) {
        init {
            // After the superclass has copied the delegate's presentation, so these win. The text is
            // left alone when the delegate has one: it is the same menu, already localized.
            isPopup = true
            templatePresentation.icon = AllIcons.Toolwindows.Task
            if (templatePresentation.text.isNullOrBlank()) templatePresentation.text = "Tasks & Contexts"
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
        // An explicit refresh means the user wants fresh comments — and a fresh assignee — too.
        detailCache.clear()
        propertyCache.clear()

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
     * Shows the selected task in the details pane. Fields we already have render immediately; comments
     * (and a fuller description) are fetched lazily via [TaskRepository.findTask] off the EDT — so the
     * list load never pays for comments it may not need — and memoized in [detailCache] so revisiting a
     * task is instant, including tasks that turn out to have no comments at all. A fetch only announces
     * itself if it outlives [LOADING_LABEL_DELAY_MS].
     */
    private fun updateDetailsFromSelection() {
        if (rebuilding) return // ignore selection churn while the tree is being rebuilt
        val node = selectedTaskNode()
        val taskId = node?.task?.id
        if (taskId == currentDetailTaskId) return // selection restored to the same task; nothing to do
        currentDetailTaskId = taskId

        cancelLoadingLabel() // whatever we show next supersedes any pending label
        showingLoadingComments = false
        val requestId = ++detailRequestId
        if (node == null) {
            detailsPanel.show(null)
            return
        }

        val task = node.task
        val repository = node.repository
        val key = DetailKey(repository.url, task.id)

        // Resolved already? Render straight away. Comments arriving with the list fetch (GitHub does this)
        // count as resolved too, and are folded into the cache so the tree node itself can be dropped.
        loadExtraProperties(task, repository, key, requestId)

        val resolved = detailCache[key] ?: task.takeIf { it.comments.isNotEmpty() }?.also { detailCache[key] = it }
        if (resolved != null) {
            detailsPanel.show(resolved, repository, loadingComments = false, extra = extraFor(task, key))
            return
        }

        // Render the fields we already have straight away, but stay quiet about the fetch for a beat:
        // a server that answers within the grace period renders once, with no label flashing in between.
        detailsPanel.show(task, repository, loadingComments = false, extra = extraFor(task, key))
        loadingLabelTimer = Timer(LOADING_LABEL_DELAY_MS) {
            if (requestId == detailRequestId) {
                showingLoadingComments = true
                detailsPanel.show(task, repository, loadingComments = true, extra = extraFor(task, key))
            }
        }.apply {
            isRepeats = false
            start()
        }

        AppExecutorUtil.getAppExecutorService().execute {
            val full = try {
                repository.findTask(task.id)
            } catch (ce: ProcessCanceledException) {
                return@execute
            } catch (ex: Exception) {
                null
            }
            ApplicationManager.getApplication().invokeLater {
                // Cache before the staleness check: the result is still valid even if the selection moved on.
                // Failures aren't cached, so they're retried on the next visit rather than sticking.
                if (full != null) detailCache[key] = full
                if (requestId != detailRequestId) return@invokeLater // selection moved on; its own timer now owns the pane
                cancelLoadingLabel()
                showingLoadingComments = false
                detailsPanel.show(full ?: task, repository, loadingComments = false, extra = extraFor(task, key))
            }
        }
    }

    /**
     * Everything the pane should show beyond what the task carries: what the IDE tracked locally, then
     * whatever the tracker told us when asked.
     *
     * Local first because it is always available — the adapter's answer arrives a request later, and
     * appending it keeps the rows already on screen from reshuffling when it does.
     */
    private fun extraFor(task: Task, key: DetailKey): List<TaskProperty> =
        localProperties(task) + propertyCache[key].orEmpty()

    /**
     * What the IDE itself knows, from the [LocalTask] behind this issue — which exists only once the
     * task has been switched to, so most rows contribute nothing.
     *
     * Labelled "Tracked in IDE" rather than "Time spent" on purpose: this is the platform's own
     * stopwatch, counting time this IDE observed the task being active. It is not what the tracker has
     * logged, and GitLab reports that separately under its own name.
     */
    /**
     * Whether this row is the task the IDE is currently on.
     *
     * Compared by id rather than by identity: our rows are freshly fetched remote issues, while the
     * active one is the [com.intellij.tasks.LocalTask] built from whichever copy was activated. Cheap
     * enough for a renderer — the platform holds the active task in a field.
     */
    private fun isActiveTask(task: Task): Boolean =
        TaskManager.getManager(project).activeTask.id == task.id

    private fun localProperties(task: Task): List<TaskProperty> {
        val local = TaskManager.getManager(project).findTask(task.id) ?: return emptyList()
        return buildList { addIfPresent("Tracked in IDE", formatDuration(local.totalTimeSpent)) }
    }

    /**
     * Asks the provider adapter for fields [Task] has no room for, once per task per refresh.
     *
     * Runs alongside the comment fetch rather than after it: the two are independent reads, and chaining
     * them would make the assignee wait on comments it has nothing to do with.
     */
    private fun loadExtraProperties(task: Task, repository: TaskRepository, key: DetailKey, requestId: Int) {
        if (propertyCache.containsKey(key)) return
        val editor = TaskEditors.forRepository(repository) ?: return

        AppExecutorUtil.getAppExecutorService().execute {
            val properties = try {
                editor.extraProperties(task)
            } catch (ce: ProcessCanceledException) {
                return@execute
            } catch (ex: Exception) {
                return@execute // uncached, so the next visit tries again
            }
            ApplicationManager.getApplication().invokeLater {
                propertyCache[key] = properties
                if (requestId != detailRequestId) return@invokeLater // selection moved on
                if (properties.isNotEmpty()) {
                    detailsPanel.show(
                        detailCache[key] ?: task,
                        repository,
                        loadingComments = showingLoadingComments,
                        extra = extraFor(task, key),
                    )
                }
            }
        }
    }

    /** The task listener unregisters itself against this; the timer would otherwise outlive the panel. */
    override fun dispose() {
        cancelLoadingLabel()
    }

    /** Stops any pending "Loading comments…" label so it can't fire over content that has already arrived. */
    private fun cancelLoadingLabel() {
        loadingLabelTimer?.stop()
        loadingLabelTimer = null
    }

    private fun maybeShowStateMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = treeTable.rowAtPoint(e.point)
        if (row < 0) return
        // Right-clicking outside the current selection acts on the row under the cursor, as elsewhere.
        if (!treeTable.selectionModel.isSelectedIndex(row)) treeTable.selectionModel.setSelectionInterval(row, row)
        val node = selectedTaskNode() ?: return // server group rows have no task to act on
        showTaskMenu(node, RelativePoint(e))
    }

    /**
     * Offers switching to this task, then the states it can move to.
     *
     * The states come from the platform's own support: [TaskRepository.STATE_UPDATING] says whether the
     * server can do it at all, and [TaskRepository.getAvailableTaskStates] asks it which transitions are
     * legal from here — so a Jira workflow only ever offers the steps it actually permits.
     *
     * That query hits the network, so it runs off the EDT and the popup opens once it answers. Switching
     * needs no such round trip, but it shares the popup, so it waits with everything else.
     */
    private fun showTaskMenu(node: TaskNode, at: RelativePoint) {
        val task = node.task
        val repository = node.repository
        val switchItem = TaskMenuItem.Switch("Switch to ${switchTarget(task)}")

        if (!task.isIssue || !repository.isSupported(TaskRepository.STATE_UPDATING)) {
            val provider = repository.repositoryType?.name ?: "this server"
            val items = listOf(switchItem, TaskMenuItem.Message("Status changes aren't supported for $provider"))
            showTaskContextMenu(items, null, at, { switchToTask(task) }) {}
            return
        }

        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(project, "Fetching available task states…", true) {
            private var states: List<CustomTaskState> = emptyList()
            private var failure: Exception? = null

            override fun run(indicator: ProgressIndicator) {
                states = try {
                    repository.getAvailableTaskStates(task).toList()
                } catch (ce: ProcessCanceledException) {
                    throw ce
                } catch (ex: Exception) {
                    failure = ex
                    emptyList()
                }
            }

            override fun onSuccess() {
                val error = failure
                val stateItems = when {
                    error != null -> listOf(TaskMenuItem.Message(error.message ?: "Could not load task states"))
                    states.isEmpty() -> listOf(TaskMenuItem.Message("No states available for this task"))
                    else -> states.map(TaskMenuItem::State)
                }
                showTaskContextMenu(listOf(switchItem) + stateItems, statusText(task), at, { switchToTask(task) }) { state ->
                    applyWrite(repository, task, "Updating status…") { repository.setTaskState(task, state) }
                }
            }
        })
    }

    /**
     * Hands the task to the Task Management plugin exactly the way its own "Open Task" chooser does: one
     * that is already local is activated outright, while an unknown one goes through the Open Task
     * dialog — which is where the changelist, branch and context options live, and is why this doesn't
     * just call [TaskManager.activateTask] for both.
     */
    private fun switchToTask(task: Task) {
        val manager = TaskManager.getManager(project)
        val local = manager.findTask(task.id)
        if (local != null) manager.activateTask(local, true) else OpenTaskDialog(project, task).show()
    }

    /** Enough of the task to recognise the row that was right-clicked, without widening the popup. */
    private fun switchTarget(task: Task): String {
        val summary = StringUtil.shortenTextWithEllipsis(task.summary.orEmpty().trim(), 60, 0)
        return if (summary.isEmpty()) task.presentableId else "${task.presentableId}: $summary"
    }

    /** Turns the details pane's inline edits into server writes. */
    private inner class EditHandler : TaskDetailsPanel.EditRequests {

        /**
         * Reads through the editor rather than the task: GitLab's Task reports no description even when
         * the issue has one, so seeding the inline editor from the task would turn a save into a silent
         * wipe. On failure the callback never fires and the pane stays in read mode.
         */
        override fun loadDescription(onLoaded: (String) -> Unit) {
            val node = selectedTaskNode() ?: return
            val task = node.task
            val editor = TaskEditors.forRepository(node.repository) ?: return

            ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(project, "Loading description…", true) {
                private var loaded: String? = null
                private var failure: Exception? = null

                override fun run(indicator: ProgressIndicator) {
                    try {
                        loaded = editor.currentDescription(task)
                    } catch (ce: ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        failure = ex
                    }
                }

                override fun onSuccess() {
                    val error = failure
                    if (error != null) notifyFailure("Loading description", error) else onLoaded(loaded.orEmpty())
                }
            })
        }

        override fun saveSummary(text: String) =
            write("Renaming task…") { editor, task -> editor.rename(task, text) }

        override fun saveDescription(text: String) =
            write("Updating description…") { editor, task -> editor.setDescription(task, text) }

        override fun postComment(text: String) =
            write("Posting comment…") { editor, task -> editor.addComment(task, text) }
    }

    /** Resolves the selection and its adapter, then hands the write off to [applyWrite]. */
    private fun write(progressTitle: String, action: (TaskEditor, Task) -> Unit) {
        val node = selectedTaskNode() ?: return
        val task = node.task
        val repository = node.repository
        val editor = TaskEditors.forRepository(repository) ?: return

        applyWrite(repository, task, progressTitle) { action(editor, task) }
    }

    /**
     * Runs a server write off the EDT, then re-reads the task so the pane and the tree both show the
     * result. Shared by the detail edits and by status changes, which differ only in the write itself.
     */
    private fun applyWrite(repository: TaskRepository, task: Task, progressTitle: String, write: () -> Unit) {
        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(project, progressTitle, false) {
            private var failure: Exception? = null
            private var refreshed: Task? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    write()
                } catch (ce: ProcessCanceledException) {
                    throw ce
                } catch (ex: Exception) {
                    failure = ex
                    return
                }
                refreshed = try {
                    repository.findTask(task.id)
                } catch (ex: Exception) {
                    null // the write landed; only the re-read failed
                }
            }

            override fun onSuccess() {
                val error = failure
                if (error != null) notifyFailure(progressTitle, error) else applyEdited(repository, task.id, refreshed)
            }
        })
    }

    private fun applyEdited(repository: TaskRepository, taskId: String, refreshed: Task?) {
        val key = DetailKey(repository.url, taskId)
        if (refreshed == null) {
            detailCache.remove(key)
            return
        }

        detailCache[key] = refreshed
        cache = cache.map { group ->
            if (group.repository !== repository || group.tasks.none { it.id == taskId }) {
                group
            } else {
                ServerGroup(
                    group.repository,
                    group.tasks.map { if (it.id == taskId) refreshed else it },
                    group.error,
                    group.loading,
                )
            }
        }
        // The selection hasn't moved, so clear the guard or the pane would decline to re-render.
        currentDetailTaskId = null
        rebuild()
    }

    private fun notifyFailure(action: String, error: Exception) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Tasker")
            .createNotification(
                action.trimEnd('…'),
                error.message ?: error.javaClass.simpleName,
                NotificationType.ERROR,
            )
            .notify(project)
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
                    // The task the IDE is currently on, which the context menu can now switch.
                    val active = isActiveTask(task)
                    icon = when {
                        // An arrow in the icon slot, where the eye already is. Bold alone proved far too
                        // quiet on an id as short as "PC-1" — it was being applied and still went unseen.
                        active -> AllIcons.Actions.Forward
                        // When flat (ungrouped), prepend the server icon so the task's origin is visible;
                        // when grouped, the server icon already sits on the parent header row.
                        groupByServer -> task.icon
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
