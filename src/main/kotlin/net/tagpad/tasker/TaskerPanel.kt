package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task as ProgressTask
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Condition
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.CustomTaskState
import com.intellij.tasks.actions.OpenTaskDialog
import com.intellij.tasks.LocalTask
import com.intellij.tasks.Task
import com.intellij.tasks.TaskListener
import com.intellij.tasks.TaskManager
import com.intellij.tasks.TaskRepository
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import net.tagpad.tasker.sort.DefaultSortModel
import net.tagpad.tasker.sort.FieldSortRule
import net.tagpad.tasker.sort.SortDirection
import net.tagpad.tasker.sort.SortModel
import net.tagpad.tasker.sort.SortSpec
import net.tagpad.tasker.sort.SortSpecCodec
import net.tagpad.tasker.sort.ui.SortPopup
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.net.SocketTimeoutException
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode

/** One server's list for one closed-issues setting. Identity on the server, like [TaskKey]. */
private class ListKey(val repository: TaskRepository, val includeClosed: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is ListKey && other.repository === repository && other.includeClosed == includeClosed

    override fun hashCode(): Int = 31 * System.identityHashCode(repository) + includeClosed.hashCode()
}

/** A fetched list: the first [limit] issues the server returned, its decorations, and when it was asked. */
private data class CachedList(
    val tasks: List<Task>,
    val limit: Int,
    val decorations: Map<String, TaskDecoration>,
    val fetchedAt: Long,
) {
    fun isFresh(maxAgeMs: Long): Boolean = System.currentTimeMillis() - fetchedAt < maxAgeMs
}

/** A list fetch under way: the limit it asked for, and when it started. Compared by identity. */
private class ListFetch(val limit: Int, val startedAt: Long)

/** Values per task with when each was stored, so a reader can tell a current one from one to read again. */
private class TimedCache<V> {
    private val entries = HashMap<TaskKey, Pair<V, Long>>()

    operator fun get(key: TaskKey): V? = entries[key]?.first

    operator fun set(key: TaskKey, value: V) {
        entries[key] = value to System.currentTimeMillis()
    }

    fun isFresh(key: TaskKey, maxAgeMs: Long): Boolean =
        entries[key]?.let { System.currentTimeMillis() - it.second < maxAgeMs } == true

    /** Whether something is held for [key] but has grown older than [maxAgeMs]. */
    fun isStale(key: TaskKey, maxAgeMs: Long): Boolean = key in entries && !isFresh(key, maxAgeMs)

    fun remove(key: TaskKey) {
        entries.remove(key)
    }

    fun clear() = entries.clear()

    fun retainServers(servers: Set<TaskRepository>) {
        entries.keys.removeIf { it.repository !in servers }
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
        /** Keyed by task id; arrives after the tasks themselves — see [decorate]. */
        val decorations: Map<String, TaskDecoration> = emptyMap(),
    )

    private val columns: Array<ColumnInfo<DefaultMutableTreeNode, *>> = taskerColumns()
    private val root = DefaultMutableTreeNode()
    private val treeModel = ListTreeTableModelOnColumns(root, columns)
    private val treeTable = SpanningTreeTable(treeModel)
    private val detailsPanel = TaskDetailsPanel(EditHandler())

    /** The servers on display, in the platform's order, as of the last [reload]. EDT-only. */
    private var servers: List<TaskRepository> = emptyList()

    /** What the tree shows: one group per server in [servers], derived by [redisplay]. EDT-only. */
    private var cache: List<ServerGroup> = emptyList()

    /**
     * Every server list fetched this session, per server and per closed-issues setting, with when it was
     * fetched — so toggling Show Closed or lowering the task limit can show a list already held instead
     * of asking every server again. See [maxAgeMs] for when a held list is read again. EDT-only.
     */
    private val listCache = HashMap<ListKey, CachedList>()

    /** The last failure of each list's fetch, until one succeeds; shown on its server row. EDT-only. */
    private val listErrors = HashMap<ListKey, String>()

    /** List fetches under way, with the limit each asked for, so a second request doesn't duplicate one. EDT-only. */
    private val listsInFlight = HashMap<ListKey, ListFetch>()

    /** When Refresh last dropped everything; a fetch started before then is stale on arrival. EDT-only. */
    private var droppedAt = 0L

    /** Where the view settings below are remembered between sessions, per project. */
    private val settings = PropertiesComponent.getInstance(project)

    private var groupByServer: Boolean = settings.getBoolean(GROUP_BY_SERVER_KEY, true)
    private var includeClosed: Boolean = settings.getBoolean(INCLUDE_CLOSED_KEY, false)
    private var issueLimit: Int = settings.getInt(ISSUE_LIMIT_KEY, DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /**
     * How the list is sorted, as every sort control edits it — the column headers and the sort popup —
     * and saved per project. The tree only reads it, and redraws when it changes.
     */
    private val sortModel: SortModel = DefaultSortModel(
        initial = savedSortSpec(),
        persist = { settings.setValue(SORT_KEY, SortSpecCodec.encode(it)) },
        peopleSource = ::assigneeNames,
    )

    /** Incremented on every selection so stale lazy detail fetches can be discarded. */
    private var detailRequestId: Int = 0
    /**
     * Task currently shown in the details pane; avoids redundant reloads when selection is restored. A
     * [TaskKey] rather than the id alone, so the same id on two servers counts as two tasks.
     */
    private var currentDetailKey: TaskKey? = null

    /**
     * Tasks whose details (comments + full description) have already been resolved. Membership *is* the
     * loaded-flag: `TaskRepository.findTask` does no caching of its own (it's abstract, and every
     * implementation issues a live request), and an empty comment list is otherwise indistinguishable
     * from "not fetched yet". Timestamped, so an entry older than [maxAgeMs] is shown and read again.
     * Cleared by a forced [reload]. EDT-only.
     */
    private val detailCache = TimedCache<Task>()

    /**
     * Adapter-fetched properties, keyed like [detailCache] and cleared alongside it.
     *
     * Kept separate rather than folded into the cached task because the two are resolved independently:
     * GitHub ships comments with the list fetch, which short-circuits the detail request entirely, but
     * says nothing about the assignee. Only successes land here, so a failed read is retried on the next
     * visit instead of sticking as a permanent blank. EDT-only.
     */
    private val propertyCache = TimedCache<TaskExtras>()

    /** Linked pull and merge requests, keyed and cleared like [propertyCache], and cached only on success. EDT-only. */
    private val pullRequestCache = TimedCache<List<LinkedPullRequest>>()

    /**
     * The comments for each task in [detailCache], wherever they came from: the task itself, or the
     * adapter for a provider whose task never carries any — see [TaskEditor.comments]. A task counts as
     * resolved only once it has an entry here too. EDT-only.
     */
    private val commentCache = HashMap<TaskKey, List<Comment>>()

    /**
     * One [FallbackIcon] per task icon, so repaints reuse the same instance instead of filling the
     * platform's icon caches with a new wrapper on every paint. Cleared by a forced [reload]. EDT-only.
     */
    private val taskIcons = HashMap<Icon, Icon>()

    /** Pending deferred "Loading comments…" label, if a fetch is in flight. EDT-only. */
    private var loadingLabelTimer: Timer? = null

    /** Reloads once the task limit has stopped changing. EDT-only. */
    private val limitRefreshTimer = Timer(LIMIT_SETTLE_MS) { reload(force = false) }.apply { isRepeats = false }

    /** Checks for anything grown stale while the tool window is showing; see [refreshStale]. EDT-only. */
    private val staleCheckTimer = Timer(STALE_CHECK_MS) { refreshStale() }

    /** Pending reads for the selected task, until the selection has settled. EDT-only. */
    private var selectionSettleTimer: Timer? = null

    /**
     * Whether the pane is currently admitting to a comment fetch.
     *
     * Late-arriving properties re-render the pane, and they have no idea whether the comments they are
     * rendering beside have landed yet. Without this they would quietly replace "Loading…" with "No
     * comments." for as long as the real fetch took. EDT-only.
     */
    private var showingLoadingComments: Boolean = false
    /** Set once by [dispose]; background results arriving after it are dropped. EDT-only. */
    private var disposed: Boolean = false

    /** True while the tree is being rebuilt, so selection churn from reload() doesn't refetch details. */
    private var rebuilding: Boolean = false

    private companion object {
        val LOG = logger<TaskerPanel>()

        const val STATUS_COLUMN = 1

        /** The Task Management plugin's own Tools menu group, reused as a toolbar dropdown. */
        const val TASKS_AND_CONTEXTS_GROUP = "tasks.and.contexts"
        const val DEFAULT_LIMIT = 30

        /** Each server is asked for this many issues, so the list can hold up to that many per server. */
        const val LIMIT_TOOLTIP = "How many issues to request from each server. Lower loads faster."
        const val MAX_LIMIT = 500

        const val ACTIVE_WINDOW_PROPERTY = "activeWindow"

        const val GROUP_BY_SERVER_KEY = "tasker.groupByServer"
        const val INCLUDE_CLOSED_KEY = "tasker.includeClosed"
        const val ISSUE_LIMIT_KEY = "tasker.issueLimit"
        const val SORT_KEY = "tasker.sort"

        /** Where versions before multi-rule sorting kept their single sort column; read once, then dropped. */
        const val LEGACY_SORT_COLUMN_KEY = "tasker.sortColumn"
        const val LEGACY_SORT_ASCENDING_KEY = "tasker.sortAscending"

        /** How often [refreshStale] looks for anything older than the platform's update interval. */
        const val STALE_CHECK_MS = 60_000

        /** How long a selection has to stay put before its task is read from the server. */
        const val SELECTION_SETTLE_MS = 150

        /** How long the task limit has to stay put before the servers are asked again. */
        const val LIMIT_SETTLE_MS = 500

        /** Grace period before admitting to a comment fetch; servers that answer inside it never flash the label. */
        const val LOADING_LABEL_DELAY_MS = 200
    }

    /**
     * Refreshes when the configured servers have changed since the last load.
     *
     * Task Management announces no such change — its settings page simply replaces the server list — so
     * this checks whenever one of this project's windows becomes active again, which is exactly what
     * closing the Settings dialog does. Both count: the project frame, which the dialog returns to, and
     * the window this panel lives in, which differs when the tool window floats. Every apply on the
     * servers page installs new server objects, so it is compared by identity: an apply always reloads,
     * and anything else costs one list compare.
     */
    private val windowActivationListener = PropertyChangeListener { event ->
        val activated = event.newValue ?: return@PropertyChangeListener
        val ours = activated === SwingUtilities.getWindowAncestor(this) ||
            activated === WindowManager.getInstance().getFrame(project)
        if (disposed || !ours) return@PropertyChangeListener
        val configured = TaskManager.getManager(project).allRepositories
        if (configured.size != servers.size || configured.indices.any { configured[it] !== servers[it] }) {
            reload(force = false) // new server objects have nothing held, so they are read afresh
        }
    }

    init {
        /*
         * Which row is bold depends on state the platform owns, so nothing here would notice it changing
         * — not even a switch made from our own context menu, which goes straight to the TaskManager.
         */
        TaskManager.getManager(project).addTaskListener(object : TaskListener {
            override fun taskActivated(task: LocalTask) = treeTable.repaint()
            override fun taskDeactivated(task: LocalTask) = treeTable.repaint()
            override fun taskAdded(task: LocalTask) = Unit
            override fun taskRemoved(task: LocalTask) = Unit
        }, this)

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener(ACTIVE_WINDOW_PROPERTY, windowActivationListener)

        /*
         * The comment panes bake the theme's colours into their stylesheet, so a theme switch redraws the
         * pane from what is already cached. Same task, so any edit in progress is kept.
         */
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                currentDetailKey = null
                updateDetailsFromSelection()
            },
        )

        sortModel.addListener(this) {
            rebuild()
            treeTable.tableHeader.repaint()
        }

        treeTable.setRootVisible(false)
        treeTable.tree.showsRootHandles = true
        treeTable.setRowHeight(JBUI.scale(24))
        treeTable.setTreeCellRenderer(
            IdCellRenderer(
                isActive = { isActiveTask(it.task, it.repository) },
                grouped = { groupByServer },
                taskIcon = { taskIcons.getOrPut(it.task.icon) { FallbackIcon(it.task.icon, it.repository.icon) } },
            ),
        )
        treeTable.tableHeader.defaultRenderer =
            SortableHeaderRenderer(treeTable.tableHeader.defaultRenderer) { column ->
                sortFieldForColumn(column)?.let(sortModel.spec::indicatorFor)
            }
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

        // Catches up as soon as the tool window is shown again, having asked nothing of the servers while hidden.
        addHierarchyListener { e ->
            val showingChanged = e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (showingChanged && isShowing) refreshStale()
        }
        staleCheckTimer.start()

        showMessage("Loading…")
        reload(force = false)
    }

    private fun setColumnWidths() {
        val widths = intArrayOf(160, 110, 500, 150, 150)
        for (i in widths.indices) {
            val column = treeTable.columnModel.getColumn(i)
            column.preferredWidth = widths[i]
            /*
             * A plain TreeTable ignores ColumnInfo.getRenderer, so install the badge renderer here.
             * Match by model index so it survives column reordering.
             */
            if (column.modelIndex == STATUS_COLUMN) {
                column.cellRenderer = StatusCellRenderer()
            }
        }
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        /*
         * All three are DumbAware for the same reason the tool window is: they refetch from servers or
         * rearrange rows we already hold, and neither needs an index to be ready.
         */
        group.add(object : AnAction("Refresh", "Reload tasks from all configured servers", AllIcons.Actions.Refresh), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = reload(force = true)
        })
        group.add(object : ToggleAction("Group by Server", "Group tasks under their task server", AllIcons.Actions.GroupBy), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = groupByServer
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupByServer = state
                settings.setValue(GROUP_BY_SERVER_KEY, state, true)
                rebuild()
            }
        })
        group.add(object : ToggleAction("Show Closed Issues", "Also fetch and show closed/resolved issues", AllIcons.Actions.ToggleVisibility), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = includeClosed
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                includeClosed = state
                settings.setValue(INCLUDE_CLOSED_KEY, state, false)
                reload(force = false) // a list held for this setting is shown, or fetched
            }
        })
        group.add(SortAction())
        /*
         * Registered by the Task Management plugin, which this plugin depends on outright — so the only
         * way this misses is a rename on JetBrains' side, and then the button simply isn't there.
         */
        (ActionManager.getInstance().getAction(TASKS_AND_CONTEXTS_GROUP) as? ActionGroup)?.let { tasksMenu ->
            group.addSeparator()
            group.add(TasksAndContextsGroup(tasksMenu))
        }

        val actionToolbar = ActionManager.getInstance().createActionToolbar("TaskerToolbar", group, true)
        actionToolbar.targetComponent = treeTable

        val spinner = JBIntSpinner(issueLimit, 1, MAX_LIMIT).apply { toolTipText = LIMIT_TOOLTIP }
        spinner.addChangeListener {
            val value = spinner.number
            if (value != issueLimit) {
                issueLimit = value
                settings.setValue(ISSUE_LIMIT_KEY, value, DEFAULT_LIMIT)
                limitRefreshTimer.restart() // a run of clicks reloads once, after the last
            }
        }
        val limitPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel("Task list limit:").apply { toolTipText = LIMIT_TOOLTIP })
            add(spinner)
        }

        return JPanel(BorderLayout()).apply {
            add(actionToolbar.component, BorderLayout.WEST)
            add(limitPanel, BorderLayout.EAST)
        }
    }

    /**
     * Opens the sort popup under the button. The icon wears a dot while any sort applies, and the tooltip
     * says what it is, so a sorted list can be told from the servers' own order at a glance.
     */
    private inner class SortAction :
        AnAction("Sort…", "Sort tasks by any number of fields", AllIcons.ObjectBrowser.Sorted), DumbAware {

        private val badged = BadgeIconSupplier(AllIcons.ObjectBrowser.Sorted)

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val spec = sortModel.spec
            e.presentation.icon = badged.getLiveIndicatorIcon(!spec.isEmpty)
            e.presentation.description =
                if (spec.isEmpty) "Sort tasks — in each server's own order now"
                else "Sorted by " + spec.rules.joinToString(", then ") { it.description }
        }

        override fun actionPerformed(e: AnActionEvent) {
            SortPopup(sortModel).show(e.inputEvent?.component as? JComponent ?: treeTable)
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
            /*
             * After the superclass has copied the delegate's presentation, so these win. The text is
             * left alone when the delegate has one: it is the same menu, already localized.
             */
            isPopup = true
            templatePresentation.icon = AllIcons.Toolwindows.Task
            if (templatePresentation.text.isNullOrBlank()) templatePresentation.text = "Tasks & Contexts"
        }
    }

    /**
     * How long anything read from a server counts as current: lists, details, comments, extra fields,
     * linked pull requests. Task Management's own "Update issue cache every N minutes" setting, so Tasker
     * goes stale exactly as often as the platform's cache does, and never while that updating is off.
     * Read afresh each time, so a change in Settings applies from the next check.
     */
    private fun maxAgeMs(): Long = IssueCacheSettings.read(project).maxAgeMs

    /**
     * Reads again, in the background, whatever on screen has grown older than [maxAgeMs]: each server's
     * list, and the selected task's details. Runs every [STALE_CHECK_MS] while the tool window is showing,
     * and as soon as it is shown again; nothing is asked of a server while it is hidden.
     *
     * Only stale lists are fetched, and nothing is redrawn until one of them answers — the tree is rebuilt
     * from scratch on each redraw, and doing that every minute for nothing would undo the user's collapsed
     * groups and scroll position.
     */
    private fun refreshStale() {
        if (disposed || !isShowing) return
        val maxAge = maxAgeMs()
        for (repo in servers) {
            if (!repo.isConfigured) continue
            val held = listCache[ListKey(repo, includeClosed)] ?: continue
            if (!held.isFresh(maxAge)) loadServer(repo, includeClosed, issueLimit, force = false)
        }
        val selected = selectedTaskNode() ?: return
        val key = TaskKey(selected.repository, selected.task.id)
        val staleDetails = detailCache.isStale(key, maxAge) || propertyCache.isStale(key, maxAge) ||
            pullRequestCache.isStale(key, maxAge)
        if (staleDetails) {
            currentDetailKey = null // same task: the pane redraws in place, keeping any edit in progress
            updateDetailsFromSelection()
        }
    }

    /**
     * Shows every server's list for the current settings, fetching the ones that need it.
     *
     * A list already held for the current closed-issues setting is shown at once, cut down to the task
     * limit, and read again in the background only if it is older than [maxAgeMs] or was fetched for
     * a lower limit. [force] — the Refresh button — drops everything held, task details included, and
     * reads every server afresh. Servers load in parallel and fill the tree as each answers, so one slow
     * server never holds up the others.
     */
    private fun reload(force: Boolean) {
        servers = TaskManager.getManager(project).allRepositories.toList()
        if (force) {
            droppedAt = System.currentTimeMillis()
            listCache.clear()
            listErrors.clear()
            detailCache.clear()
            propertyCache.clear()
            pullRequestCache.clear()
            commentCache.clear()
            taskIcons.clear()
        } else {
            forgetRemovedServers()
        }

        for (repo in servers) {
            if (!repo.isConfigured) continue
            val held = listCache[ListKey(repo, includeClosed)]
            val current = held != null && held.limit >= issueLimit && held.isFresh(maxAgeMs())
            if (force || !current) loadServer(repo, includeClosed, issueLimit, force)
        }
        redisplay()
    }

    /** Drops what is held for servers no longer configured, whose objects a reconfiguration has replaced. */
    private fun forgetRemovedServers() {
        val live = servers.toSet()
        listCache.keys.removeIf { it.repository !in live }
        listErrors.keys.removeIf { it.repository !in live }
        listsInFlight.keys.removeIf { it.repository !in live }
        for (timed in listOf(detailCache, propertyCache, pullRequestCache)) timed.retainServers(live)
        commentCache.keys.removeIf { it.repository !in live }
    }

    /** Rebuilds [cache] from what is held for the current settings, then the tree from that. */
    private fun redisplay() {
        cache = servers.map(::groupFor)
        rebuild()
    }

    private fun groupFor(repo: TaskRepository): ServerGroup {
        if (!repo.isConfigured) return ServerGroup(repo, emptyList(), "not configured")
        val key = ListKey(repo, includeClosed)
        val held = listCache[key]
        return ServerGroup(
            repo,
            held?.tasks?.take(issueLimit).orEmpty(),
            listErrors[key],
            loading = held == null && key in listsInFlight,
            decorations = held?.decorations.orEmpty(),
        )
    }

    /**
     * Fetches one server's list off the EDT into [listCache], then decorates it.
     *
     * A fetch already under way for the same list and at least this limit is left to answer instead,
     * unless [force]d. A result is kept only if nothing fetched later has landed first, and is shown if
     * it is for the settings on display by then. A failure keeps whatever list was held, shown with the
     * error on its row. [redrawDetails] makes the pane re-render the selected task from the new list.
     */
    private fun loadServer(
        repo: TaskRepository,
        includeClosed: Boolean,
        limit: Int,
        force: Boolean,
        redrawDetails: Boolean = false,
    ) {
        val key = ListKey(repo, includeClosed)
        val running = listsInFlight[key]
        if (!force && running != null && running.limit >= limit) return
        val fetch = ListFetch(limit, System.currentTimeMillis())
        listsInFlight[key] = fetch

        AppExecutorUtil.getAppExecutorService().execute {
            val outcome = try {
                val issues = repo.getIssues(null, 0, limit, includeClosed, EmptyProgressIndicator())
                /*
                 * Filtered again here, as the platform's own TaskManager does: connectors are asked to leave
                 * closed issues out, but the older ones never see the flag, and not every one honours it.
                 */
                Result.success(issues.filter { includeClosed || !it.isClosed })
            } catch (ce: ProcessCanceledException) {
                onEdt { if (listsInFlight[key] === fetch) listsInFlight.remove(key) }
                return@execute
            } catch (ex: Exception) {
                Result.failure(ex)
            }
            val tasks = outcome.getOrNull()
            onEdt {
                if (listsInFlight[key] === fetch) listsInFlight.remove(key)
                val newer = (listCache[key]?.fetchedAt ?: 0) > fetch.startedAt
                if (newer || fetch.startedAt < droppedAt || repo !in servers) return@onEdt
                if (tasks != null) {
                    listCache[key] = CachedList(tasks, limit, emptyMap(), fetch.startedAt)
                    listErrors.remove(key)
                } else {
                    listErrors[key] = loadFailure(repo, outcome.exceptionOrNull() as Exception)
                }
                if (redrawDetails) currentDetailKey = null
                redisplay()
            }
            if (!tasks.isNullOrEmpty()) decorate(repo, key, tasks)
        }
    }

    /**
     * Second pass over a server's list, for what [Task] reports wrong or not at all — Jira's real status
     * names and issue-type icons. Runs after the plain list is already on screen, so a slow or failing
     * adapter costs nothing but the extras. Called on the loading thread; the decorations are kept with
     * the list they were read for, if that is still the one held.
     */
    private fun decorate(repository: TaskRepository, key: ListKey, tasks: List<Task>) {
        val editor = TaskEditors.forRepository(repository) ?: return
        val decorations = try {
            editor.decorate(tasks)
        } catch (ce: ProcessCanceledException) {
            return
        } catch (ex: Exception) {
            LOG.warn("Could not decorate tasks from ${repository.presentableName}", ex)
            return
        }
        if (decorations.isEmpty()) return

        onEdt {
            val held = listCache[key]?.takeIf { it.tasks === tasks } ?: return@onEdt
            listCache[key] = held.copy(decorations = held.decorations + decorations)
            // The pane already shows this task without them; let the reconcile in rebuild() redraw it.
            currentDetailKey = null
            redisplay()
        }
    }

    private fun decorationFor(repository: TaskRepository, taskId: String): TaskDecoration? =
        cache.firstOrNull { it.repository === repository }?.decorations?.get(taskId)

    /** Rebuild the tree from [cache] using the current grouping + sort. No refetch. */
    private fun rebuild() {
        val selected = selectedTaskNode()
        rebuilding = true
        try {
            root.removeAllChildren()
            if (cache.isEmpty()) {
                root.add(DefaultMutableTreeNode("No task servers configured"))
                treeModel.reload()
                return
            }

            val sort = sortModel.spec

            if (groupByServer) {
                for (serverGroup in cache) {
                    val serverNode =
                        DefaultMutableTreeNode(ServerNode(serverGroup.repository, serverGroup.error, serverGroup.loading))
                    serverGroup.tasks
                        .map { TaskNode(it, serverGroup.repository, serverGroup.decorations[it.id]) }
                        .let(sort::sort)
                        .forEach { serverNode.add(DefaultMutableTreeNode(it)) }
                    root.add(serverNode)
                }
            } else {
                val taskNodes = cache
                    .flatMap { group -> group.tasks.map { TaskNode(it, group.repository, group.decorations[it.id]) } }
                    .let(sort::sort)
                taskNodes.forEach { root.add(DefaultMutableTreeNode(it)) }
                if (taskNodes.isEmpty()) {
                    root.add(DefaultMutableTreeNode(if (cache.any { it.loading }) "Loading…" else "No tasks"))
                }
            }

            treeModel.reload()
            expandAll()
            if (selected != null) reselectTask(selected.repository, selected.task.id)
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

    /** A header click sorts by that column alone; with Shift, it adds the column as a further rule. */
    private fun onHeaderClicked(e: MouseEvent) {
        val viewColumn = treeTable.tableHeader.columnAtPoint(e.point)
        if (viewColumn < 0) return
        val field = sortFieldForColumn(treeTable.convertColumnIndexToModel(viewColumn)) ?: return
        sortModel.update(sortModel.spec.withHeaderClick(field, additive = e.isShiftDown))
    }

    /** The saved sort, or the single column an older version saved, carried over once. */
    private fun savedSortSpec(): SortSpec {
        settings.getValue(SORT_KEY)?.let { return SortSpecCodec.decode(it) }
        val legacyField = sortFieldForColumn(settings.getInt(LEGACY_SORT_COLUMN_KEY, -1))
        val ascending = settings.getBoolean(LEGACY_SORT_ASCENDING_KEY, true)
        settings.unsetValue(LEGACY_SORT_COLUMN_KEY)
        settings.unsetValue(LEGACY_SORT_ASCENDING_KEY)
        val direction = if (ascending) SortDirection.ASCENDING else SortDirection.DESCENDING
        return SortSpec(listOfNotNull(legacyField?.let { FieldSortRule(it, direction) }))
    }

    /** Everyone the rows on display are assigned to, for the sort controls to offer by name. */
    private fun assigneeNames(): List<String> =
        cache.flatMap { group -> group.decorations.values.flatMap { it.assignees.orEmpty() } }
            .map { it.name }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun selectedTaskNode(): TaskNode? {
        val row = treeTable.selectedRow
        if (row < 0) return null
        val node = treeTable.tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
        return node?.userObject as? TaskNode
    }

    /**
     * Selects the row for [taskId] on [repository], so a rebuild keeps the selection where it was.
     *
     * The server is matched by identity first, since two servers can share both a url and task ids. A
     * reconfiguration replaces every server object, though, so failing that the url stands in for it.
     */
    private fun reselectTask(repository: TaskRepository, taskId: String) {
        val tree = treeTable.tree
        val rows = (0 until tree.rowCount).mapNotNull { row ->
            val node = (tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TaskNode
            node?.takeIf { it.task.id == taskId }?.let { row to it.repository }
        }
        val row = rows.firstOrNull { it.second === repository }?.first
            ?: rows.firstOrNull { it.second.url == repository.url }?.first
            ?: return
        treeTable.selectionModel.setSelectionInterval(row, row)
    }

    /**
     * Shows the selected task in the details pane. Fields we already have render immediately; comments
     * (and a fuller description) are fetched lazily via [TaskRepository.findTask] off the EDT — so the
     * list load never pays for comments it may not need — and memoized in [detailCache] so revisiting a
     * task is instant, including tasks that turn out to have no comments at all. Nothing is fetched until
     * the selection has stayed put for [SELECTION_SETTLE_MS].
     */
    private fun updateDetailsFromSelection() {
        if (rebuilding) return // ignore selection churn while the tree is being rebuilt
        val node = selectedTaskNode()
        val selectedKey = node?.let { TaskKey(it.repository, it.task.id) }
        if (selectedKey == currentDetailKey) return // selection restored to the same task; nothing to do
        currentDetailKey = selectedKey

        cancelLoadingLabel() // whatever we show next supersedes any pending label
        selectionSettleTimer?.stop()
        showingLoadingComments = false
        val requestId = ++detailRequestId
        if (node == null) {
            detailsPanel.show(null)
            return
        }

        val task = node.task
        val repository = node.repository
        val key = TaskKey(repository, task.id)

        /*
         * Resolved already? Render straight away. Comments arriving with the list fetch count as resolved
         * too, and are folded into the cache so the tree node itself can be dropped — but not when the
         * adapter fetches comments itself: Jira's list tasks carry comments too, but raw, and taking those
         * as final would mean the adapter's own were never asked for.
         */
        val ownComments = TaskEditors.forRepository(repository)?.suppliesComments != true
        val resolved = detailCache[key]?.takeIf { key in commentCache }
            ?: task.takeIf { ownComments && it.comments.isNotEmpty() }?.also {
                detailCache[key] = it
                commentCache[key] = it.comments.toList()
            }
        // Shown however old; read again below, quietly, once older than maxAgeMs.
        val current = resolved != null && detailCache.isFresh(key, maxAgeMs())
        showDetails(resolved ?: task, repository, key, loadingComments = false)
        if (resolved == null) {
            /*
             * Stay quiet about the fetch for a beat: a server that answers within the grace period renders
             * once, with no label flashing in between.
             */
            loadingLabelTimer = Timer(LOADING_LABEL_DELAY_MS) {
                if (requestId == detailRequestId) {
                    showingLoadingComments = true
                    showDetails(task, repository, key, loadingComments = true)
                }
            }.apply {
                isRepeats = false
                start()
            }
        }

        /*
         * The reads wait for the selection to settle. Every one of them is a round trip, several for some
         * trackers, and holding an arrow key down the list would otherwise send them for every row passed.
         */
        selectionSettleTimer = Timer(SELECTION_SETTLE_MS) {
            if (requestId != detailRequestId) return@Timer
            loadExtraProperties(task, repository, key, requestId)
            loadPullRequests(task, repository, key, requestId)
            if (!current) loadDetails(task, repository, key, requestId)
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** Reads the task in full, with its comments, and renders the result. */
    private fun loadDetails(task: Task, repository: TaskRepository, key: TaskKey, requestId: Int) {
        AppExecutorUtil.getAppExecutorService().execute {
            /*
             * A null answer is not a failure: findTask is @Nullable, and a connector that can't find the
             * issue on its own has nothing fuller than the listed task. That task is then the resolved one,
             * and is cached like any other. Only a throw is retried — so a connector that is known to have
             * no lookup at all isn't asked, rather than failing again on every visit.
             */
            val full = try {
                if (lacksSingleTaskLookup(repository)) task else repository.findTask(task.id) ?: task
            } catch (ce: ProcessCanceledException) {
                return@execute
            } catch (ex: Exception) {
                null
            }
            val comments = full?.let { loadComments(repository, it) }
            onEdt {
                /*
                 * Cache before the staleness check: the result is still valid even if the selection moved
                 * on. Failures aren't cached, so they're retried on the next visit rather than sticking.
                 */
                if (full != null && comments != null) {
                    detailCache[key] = full
                    commentCache[key] = comments
                }
                if (requestId != detailRequestId) return@onEdt // selection moved on; its own timer now owns the pane
                cancelLoadingLabel()
                showingLoadingComments = false
                // A failed re-read leaves what was held on screen rather than falling back to the list's copy.
                showDetails(full ?: detailCache[key] ?: task, repository, key, loadingComments = false)
            }
        }
    }

    /**
     * Whether [repository] is a Generic server with no single-task url configured, which is its default.
     * Its findTask then requests an empty url and fails, every time. The class lives in Task Management's
     * own module, so the setting is read by name; any other server answers false.
     */
    private fun lacksSingleTaskLookup(repository: TaskRepository): Boolean =
        runCatching { repository.javaClass.getMethod("getSingleTaskUrl").invoke(repository) as? String }
            .getOrNull()?.isBlank() == true

    /**
     * What a server row says when its list failed, after logging the failure as the platform does.
     *
     * Only messages likely meant for a person are shown, by the platform's own rule — a plain Exception or
     * its RequestFailedException, which connectors throw with something to say — plus an HTTP status, whose
     * message is the server's own. Anything else is an internal error whose message would mean nothing on a
     * server row, and the log has the details.
     */
    private fun loadFailure(repository: TaskRepository, ex: Exception): String {
        val expected = ex is SocketTimeoutException || ex is HttpRequests.HttpStatusException
        if (expected) LOG.warn("Can't connect to ${repository.presentableName}: ${ex.message}")
        else LOG.warn("Can't load issues from ${repository.presentableName}", ex)

        val meantForUsers = ex.javaClass == Exception::class.java || ex is HttpRequests.HttpStatusException ||
            ex.javaClass.simpleName == "RequestFailedException"
        return when {
            meantForUsers && !ex.message.isNullOrBlank() -> ex.message.orEmpty()
            ex is SocketTimeoutException -> "timed out"
            else -> "could not load issues — see the IDE log"
        }
    }

    /**
     * Everything the pane should show beyond what the task carries: what the IDE tracked locally, then
     * whatever the tracker told us when asked.
     *
     * Local first because it is always available — the adapter's answer arrives a request later, and
     * appending it keeps the rows already on screen from reshuffling when it does.
     */
    private fun extraFor(task: Task, repository: TaskRepository, key: TaskKey): List<TaskProperty> =
        localProperties(task) + decorationFor(repository, task.id)?.properties.orEmpty() +
            propertyCache[key]?.properties.orEmpty()

    /** Renders [task] in the pane with everything resolved for it so far. */
    private fun showDetails(task: Task, repository: TaskRepository, key: TaskKey, loadingComments: Boolean) {
        detailsPanel.show(
            task,
            repository,
            loadingComments = loadingComments,
            extra = extraFor(task, repository, key),
            status = decorationFor(repository, task.id)?.status,
            comments = commentCache[key],
            priority = propertyCache[key]?.priority,
            labels = propertyCache[key]?.labels.orEmpty(),
            pullRequests = pullRequestCache[key].orEmpty(),
        )
    }

    /**
     * The comments for a freshly read [task]: the adapter's when it supplies its own, the task's otherwise.
     * Null when the adapter's read failed. Hits the network, so call it off the EDT.
     */
    private fun loadComments(repository: TaskRepository, task: Task): List<Comment>? =
        try {
            TaskEditors.forRepository(repository)?.comments(task) ?: task.comments.toList()
        } catch (ce: ProcessCanceledException) {
            throw ce
        } catch (ex: Exception) {
            LOG.warn("Could not load comments for ${task.presentableId}", ex)
            null
        }

    /**
     * Whether this row is the task the IDE is currently on.
     *
     * Compared by id rather than by identity: our rows are freshly fetched remote issues, while the
     * active one is the [com.intellij.tasks.LocalTask] built from whichever copy was activated. Cheap
     * enough for a renderer — the platform holds the active task in a field.
     *
     * Ids are only unique per server, so the server has to agree too — by url, since the local task
     * outlives the server objects of the session it was activated in. One that doesn't know its server
     * is taken on its id alone.
     */
    private fun isActiveTask(task: Task, repository: TaskRepository): Boolean {
        val active = TaskManager.getManager(project).activeTask
        val activeServer = active.repository
        return active.id == task.id && (activeServer == null || activeServer.url == repository.url)
    }

    /**
     * What the IDE itself knows, from the [LocalTask] behind this issue — which exists only once the
     * task has been switched to, so most rows contribute nothing.
     *
     * Labelled "Tracked in IDE" rather than "Time spent" on purpose: this is the platform's own
     * stopwatch, counting time this IDE observed the task being active. It is not what the tracker has
     * logged, and GitLab reports that separately under its own name.
     */
    private fun localProperties(task: Task): List<TaskProperty> {
        val local = TaskManager.getManager(project).findTask(task.id) ?: return emptyList()
        return buildList { addIfPresent("Tracked in IDE", formatDuration(local.totalTimeSpent)) }
    }

    /**
     * Asks the provider adapter for fields [Task] has no room for, again once what is held is older than
     * [maxAgeMs].
     *
     * Runs alongside the comment fetch rather than after it: the two are independent reads, and chaining
     * them would make the assignee wait on comments it has nothing to do with.
     */
    private fun loadExtraProperties(task: Task, repository: TaskRepository, key: TaskKey, requestId: Int) {
        if (propertyCache.isFresh(key, maxAgeMs())) return
        val editor = TaskEditors.forRepository(repository) ?: return

        AppExecutorUtil.getAppExecutorService().execute {
            val extras = try {
                editor.extras(task)
            } catch (ce: ProcessCanceledException) {
                return@execute
            } catch (ex: Exception) {
                LOG.info("Could not read details for ${task.presentableId} on ${repository.presentableName}", ex)
                return@execute // uncached, so the next visit tries again
            }
            onEdt {
                propertyCache[key] = extras
                if (requestId != detailRequestId) return@onEdt // selection moved on
                if (!extras.isEmpty()) {
                    showDetails(detailCache[key] ?: task, repository, key, loadingComments = showingLoadingComments)
                }
            }
        }
    }

    /**
     * Asks the adapter for linked pull and merge requests, again once older than [maxAgeMs], alongside
     * the other detail reads. A failure is logged rather than shown: for Jira this rides on an unofficial
     * API, and a missing section is the honest outcome when it doesn't answer.
     */
    private fun loadPullRequests(task: Task, repository: TaskRepository, key: TaskKey, requestId: Int) {
        if (pullRequestCache.isFresh(key, maxAgeMs())) return
        val editor = TaskEditors.forRepository(repository) ?: return

        AppExecutorUtil.getAppExecutorService().execute {
            val pullRequests = try {
                editor.pullRequests(task)
            } catch (ce: ProcessCanceledException) {
                return@execute
            } catch (ex: Exception) {
                LOG.info("Could not read pull requests for ${task.presentableId} on ${repository.presentableName}", ex)
                return@execute // uncached, so the next visit tries again
            }
            onEdt {
                pullRequestCache[key] = pullRequests
                if (requestId != detailRequestId) return@onEdt // selection moved on
                if (pullRequests.isNotEmpty()) {
                    showDetails(detailCache[key] ?: task, repository, key, loadingComments = showingLoadingComments)
                }
            }
        }
    }

    /**
     * The task and theme listeners unregister themselves against this; the window listener and the
     * timer would otherwise outlive the panel.
     */
    override fun dispose() {
        disposed = true
        selectionSettleTimer?.stop()
        limitRefreshTimer.stop()
        staleCheckTimer.stop()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener(ACTIVE_WINDOW_PROPERTY, windowActivationListener)
        cancelLoadingLabel()
    }

    /**
     * Runs [action] on the EDT, unless the panel has been disposed by then. Every background read ends
     * here, and one that outlives its panel — a project closed mid-refresh — must not rebuild a dead
     * tree or ask a disposed project's TaskManager anything.
     */
    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ action() }, Condition<Any?> { disposed })

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
                if (disposed) return
                val error = failure
                val stateItems = when {
                    error != null -> listOf(TaskMenuItem.Message(error.message ?: "Could not load task states"))
                    states.isEmpty() -> listOf(TaskMenuItem.Message("No states available for this task"))
                    else -> states.map(TaskMenuItem::State)
                }
                showTaskContextMenu(listOf(switchItem) + stateItems, statusText(task, node.decoration), at, { switchToTask(task) }) { state ->
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
                    if (disposed) return
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

        override fun saveComment(comment: Comment, text: String) =
            write("Updating comment…") { editor, task -> editor.editComment(task, comment, text) }
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
        val focusBefore = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        ProgressManager.getInstance().run(object : ProgressTask.Backgroundable(project, progressTitle, false) {
            private var failure: Exception? = null
            private var refreshed: Task? = null
            private var rereadFailed = false
            private var decorations: Map<String, TaskDecoration> = emptyMap()
            private var comments: List<Comment>? = null

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
                } catch (ce: ProcessCanceledException) {
                    throw ce
                } catch (ex: Exception) {
                    LOG.info("Could not re-read ${task.presentableId} after a write", ex)
                    rereadFailed = true
                    null // the write landed; only the re-read failed
                }
                // A posted comment has to show up, and the task alone may not carry it.
                comments = refreshed?.let { loadComments(repository, it) }
                // A status change is exactly what a stale decoration would get wrong, so re-read it too.
                decorations = refreshed?.let { fresh ->
                    try {
                        TaskEditors.forRepository(repository)?.decorate(listOf(fresh))
                    } catch (ce: ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        LOG.info("Could not re-read the status of ${fresh.presentableId}", ex)
                        null
                    }
                }.orEmpty()
            }

            override fun onSuccess() {
                if (disposed) return
                val error = failure
                if (error != null) {
                    notifyFailure(progressTitle, error)
                } else {
                    applyEdited(repository, task.id, refreshed, decorations, comments, rereadFailed)
                }
            }

            override fun onFinished() = restoreFocus(focusBefore)
        })
    }

    /**
     * Puts the focus back where it was before a write, if the write's aftermath moved it.
     *
     * Re-rendering the pane after a write could leave the focus to Swing's traversal order, which from the
     * comment box wraps round to the toolbar — the task limit spinner ended up focused, its value
     * selected, after every posted comment. Restored only when the focus is now nowhere or in the
     * toolbar, which is not somewhere a user would have moved it in the second a write takes; a click
     * into the list or the pane meanwhile is left alone.
     *
     * The focus owner from before may itself be gone — an inline editor's Save button is removed by the
     * save — and then the pane's own resting place stands in for it.
     */
    private fun restoreFocus(focusBefore: Component?) {
        if (focusBefore == null) return
        // Queued behind the focus transfers the re-render requested, so it sees where they landed.
        onEdt {
            val now = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val strayed = now == null ||
                (SwingUtilities.isDescendingFrom(now, this) && !SwingUtilities.isDescendingFrom(now, treeTable) &&
                    !SwingUtilities.isDescendingFrom(now, detailsPanel))
            if (!strayed || now === focusBefore) return@onEdt
            if (focusBefore.isShowing) focusBefore.requestFocusInWindow() else detailsPanel.parkFocus()
        }
    }

    private fun applyEdited(
        repository: TaskRepository,
        taskId: String,
        refreshed: Task?,
        decorations: Map<String, TaskDecoration>,
        comments: List<Comment>?,
        rereadFailed: Boolean,
    ) {
        val key = TaskKey(repository, taskId)
        if (refreshed == null || comments == null) {
            detailCache.remove(key)
            commentCache.remove(key)
        } else {
            detailCache[key] = refreshed
            commentCache[key] = comments
        }
        if (refreshed == null) {
            /*
             * Answered, but with nothing: a connector without a single-issue lookup, such as Generic. Its
             * list is then the only place the change shows, so that server is read again. Not after a
             * failed re-read, though — on a flaky connection that would trade the list for an error.
             */
            if (!rereadFailed) {
                loadServer(repository, includeClosed, issueLimit, force = true, redrawDetails = true)
            }
            return
        }

        /*
         * Into every list held for this server, with and without closed issues: both may hold the task. A
         * task the write just closed leaves the list of open ones, as a fresh fetch would leave it out.
         */
        for (key in listCache.keys.filter { it.repository === repository }) {
            val held = listCache.getValue(key)
            if (held.tasks.none { it.id == taskId }) continue
            val tasks = held.tasks
                .map { if (it.id == taskId) refreshed else it }
                .filter { key.includeClosed || !it.isClosed }
            listCache[key] = held.copy(tasks = tasks, decorations = held.decorations + decorations)
        }
        // The selection hasn't moved, so clear the guard or the pane would decline to re-render.
        currentDetailKey = null
        redisplay()
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
}
