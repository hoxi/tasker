package net.tagpad.tasker

import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskState
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import net.tagpad.tasker.sort.SortField
import net.tagpad.tasker.sort.SortFields
import net.tagpad.tasker.sort.SortPerson
import net.tagpad.tasker.sort.SortPriority
import net.tagpad.tasker.sort.SortableRow
import java.util.Date
import javax.swing.tree.DefaultMutableTreeNode

/** User objects carried by the tree nodes. */
internal class ServerNode(val repository: TaskRepository, val error: String?, val loading: Boolean = false)
/**
 * A task row: the task, its server, and what its adapter added. Sortable through [SortableRow], whose
 * values it reads from the task and, for what [Task] has no room for, from the [decoration].
 */
internal class TaskNode(
    val task: Task,
    val repository: TaskRepository,
    val decoration: TaskDecoration? = null,
) : SortableRow {
    override val id: String get() = task.presentableId
    override val summary: String get() = task.summary.orEmpty()
    override val status: String get() = statusText(task, decoration)
    override val created: Date? get() = task.created
    override val updated: Date? get() = task.updated
    override val serverName: String get() = repository.presentableName.orEmpty()

    override val assignees: List<SortPerson>
        get() = decoration?.assignees.orEmpty().map { SortPerson(it.name, it.isMe) }

    override val priority: SortPriority?
        get() = decoration?.priority?.let { p -> p.rank?.let { SortPriority(p.name, it) } }
}

internal fun DefaultMutableTreeNode.task(): Task? = (userObject as? TaskNode)?.task

/** The row's status text, preferring what the provider adapter resolved over what [Task] reports. */
internal fun DefaultMutableTreeNode.statusText(): String =
    (userObject as? TaskNode)?.let { statusText(it.task, it.decoration) } ?: ""

/**
 * The state as the tracker itself names it.
 *
 * [Task.getState] returns the [TaskState] enum, which flattens anything it doesn't recognise to
 * [TaskState.OTHER] — and YouTrack routes most of its board there, including "In Progress", whose
 * uppercased form has a space where the enum has an underscore. Taking the enum's presentable name at
 * face value would label most YouTrack issues "Other".
 *
 * So prefer the tracker's own state property when it exposes one; the enum is only a fallback.
 */
internal fun statusText(task: Task, decoration: TaskDecoration? = null): String {
    decoration?.status?.let { return it }
    trackerStateName(task)?.let { return it }
    val state = task.state
    if (state != null && state != TaskState.OTHER) return state.presentableName
    return if (task.isClosed) "Closed" else "Open"
}

/** Matched case-insensitively: YouTrack keys this "state", but the property name isn't standardised. */
private fun trackerStateName(task: Task): String? =
    task.customProperties.entries
        .firstOrNull { it.key.equals("state", ignoreCase = true) }
        ?.value?.value
        ?.takeIf { it.isNotBlank() }

/** A date and time in the IDE's own format, as set under Settings | Appearance & Behavior | System Settings. */
internal fun formatDate(date: Date?): String = date?.let(DateFormatUtil::formatDateTime).orEmpty()

/** ID column — this is the tree column (holds the expand handles and per-server grouping). */
private class IdColumn : ColumnInfo<DefaultMutableTreeNode, Any>("ID") {
    override fun valueOf(item: DefaultMutableTreeNode): Any = item
    override fun getColumnClass(): Class<*> = TreeTableModel::class.java
}

private class StatusColumn : ColumnInfo<DefaultMutableTreeNode, String>("Status") {
    /*
     * The badge renderer is installed on the TableColumn in TaskerPanel — a plain TreeTable (unlike
     * TableView) does not consult ColumnInfo.getRenderer.
     */
    override fun valueOf(item: DefaultMutableTreeNode): String = item.statusText()
}

/** Who the task is assigned to, as its adapter reported; blank where the tracker has no adapter. */
private class AssigneeColumn : ColumnInfo<DefaultMutableTreeNode, String>("Assignee") {
    override fun valueOf(item: DefaultMutableTreeNode): String =
        (item.userObject as? TaskNode)?.assignees?.joinToString(", ") { it.name }.orEmpty()
}

private class SummaryColumn : ColumnInfo<DefaultMutableTreeNode, String>("Summary") {
    override fun valueOf(item: DefaultMutableTreeNode): String = item.task()?.summary ?: ""
}

private class UpdatedColumn : ColumnInfo<DefaultMutableTreeNode, String>("Updated") {
    override fun valueOf(item: DefaultMutableTreeNode): String = formatDate(item.task()?.updated)
}

private class CreatedColumn : ColumnInfo<DefaultMutableTreeNode, String>("Created") {
    override fun valueOf(item: DefaultMutableTreeNode): String = formatDate(item.task()?.created)
}

/** Column order: ID (tree), Status, Assignee, Summary, Updated, Created. */
internal fun taskerColumns(): Array<ColumnInfo<DefaultMutableTreeNode, *>> =
    arrayOf(IdColumn(), StatusColumn(), AssigneeColumn(), SummaryColumn(), UpdatedColumn(), CreatedColumn())

/** The field each column sorts by, by model index, in [taskerColumns]' order. */
private val COLUMN_SORT_FIELDS: List<SortField<*>> =
    listOf(
        SortFields.ID,
        SortFields.STATUS,
        SortFields.ASSIGNEE,
        SortFields.SUMMARY,
        SortFields.UPDATED,
        SortFields.CREATED,
    )

/** The field a click on the column at [modelIndex] sorts by, or null for a column that doesn't sort. */
internal fun sortFieldForColumn(modelIndex: Int): SortField<*>? = COLUMN_SORT_FIELDS.getOrNull(modelIndex)

/**
 * One task on one server. Ids are only unique within a server, so the server is part of the key — the
 * configured server itself rather than its url, which two servers can share: every GitHub repository is
 * configured against the same API host. Keys are dropped with every refresh, the only time the server
 * objects are looked up afresh, so identity is stable for as long as a key lives.
 */
internal class TaskKey(val repository: TaskRepository, val taskId: String) {
    override fun equals(other: Any?): Boolean =
        other is TaskKey && other.repository === repository && other.taskId == taskId

    override fun hashCode(): Int = 31 * System.identityHashCode(repository) + taskId.hashCode()
}
