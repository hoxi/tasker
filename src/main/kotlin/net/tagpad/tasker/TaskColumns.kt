package net.tagpad.tasker

import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.ui.ColumnInfo
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.tree.DefaultMutableTreeNode

/** User objects carried by the tree nodes. */
internal class ServerNode(val repository: TaskRepository, val error: String?, val loading: Boolean = false)
internal class TaskNode(val task: Task, val repository: TaskRepository)

internal fun DefaultMutableTreeNode.task(): Task? = (userObject as? TaskNode)?.task

internal fun statusText(task: Task): String =
    task.state?.presentableName ?: if (task.isClosed) "Closed" else "Open"

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm")

internal fun formatDate(date: Date?): String = date?.let { DATE_FORMAT.format(it) } ?: ""

/** Compare two nodes by a task date, nulls (and non-task nodes) last. */
private fun byDate(extract: (Task) -> Date?): Comparator<DefaultMutableTreeNode> =
    Comparator { a, b ->
        val da = a.task()?.let(extract)
        val db = b.task()?.let(extract)
        when {
            da == null && db == null -> 0
            da == null -> 1
            db == null -> -1
            else -> da.compareTo(db)
        }
    }

private fun byString(extract: (Task) -> String?): Comparator<DefaultMutableTreeNode> =
    Comparator { a, b ->
        StringUtil.naturalCompare(a.task()?.let(extract) ?: "", b.task()?.let(extract) ?: "")
    }

/** ID column — this is the tree column (holds the expand handles and per-server grouping). */
private class IdColumn : ColumnInfo<DefaultMutableTreeNode, Any>("ID") {
    override fun valueOf(item: DefaultMutableTreeNode): Any = item
    override fun getColumnClass(): Class<*> = TreeTableModel::class.java
    override fun getComparator(): Comparator<DefaultMutableTreeNode> = byString { it.presentableId }
}

private class StatusColumn : ColumnInfo<DefaultMutableTreeNode, String>("Status") {
    // Note: the badge renderer is installed on the TableColumn in TaskerPanel — a plain TreeTable
    // (unlike TableView) does not consult ColumnInfo.getRenderer.
    override fun valueOf(item: DefaultMutableTreeNode): String = item.task()?.let(::statusText) ?: ""
    override fun getComparator(): Comparator<DefaultMutableTreeNode> = byString { statusText(it) }
}

private class SummaryColumn : ColumnInfo<DefaultMutableTreeNode, String>("Summary") {
    override fun valueOf(item: DefaultMutableTreeNode): String = item.task()?.summary ?: ""
    override fun getComparator(): Comparator<DefaultMutableTreeNode> = byString { it.summary }
}

private class UpdatedColumn : ColumnInfo<DefaultMutableTreeNode, String>("Updated") {
    override fun valueOf(item: DefaultMutableTreeNode): String = formatDate(item.task()?.updated)
    override fun getComparator(): Comparator<DefaultMutableTreeNode> = byDate { it.updated }
}

private class CreatedColumn : ColumnInfo<DefaultMutableTreeNode, String>("Created") {
    override fun valueOf(item: DefaultMutableTreeNode): String = formatDate(item.task()?.created)
    override fun getComparator(): Comparator<DefaultMutableTreeNode> = byDate { it.created }
}

/** Column order: ID (tree), Status, Summary, Updated, Created. */
internal fun taskerColumns(): Array<ColumnInfo<DefaultMutableTreeNode, *>> =
    arrayOf(IdColumn(), StatusColumn(), SummaryColumn(), UpdatedColumn(), CreatedColumn())
