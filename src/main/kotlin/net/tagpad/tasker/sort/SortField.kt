package net.tagpad.tasker.sort

import com.intellij.openapi.util.text.StringUtil
import net.tagpad.tasker.StatusKind
import net.tagpad.tasker.statusKind

/**
 * A value rows can be ordered by, in one direction or the other.
 *
 * [id] is stable — it is what gets saved — while [displayName] is what the user sees. [kind] decides how
 * the two directions are worded. [allServers] is false for values only some trackers report, which the
 * sort controls say so about. [key] reads the value off a row; null means the row doesn't have one, and such
 * rows always sort last. Values are ordered by [order].
 */
class SortField<T : Any>(
    val id: String,
    val displayName: String,
    val kind: FieldKind,
    val allServers: Boolean,
    private val key: (SortableRow) -> T?,
    private val order: Comparator<T>,
) {
    /** Orders rows by this field: rows without a value last, whatever the [direction]. */
    fun comparator(direction: SortDirection): Comparator<SortableRow> {
        val values = if (direction == SortDirection.ASCENDING) order else order.reversed()
        return Comparator { a, b ->
            val va = key(a)
            val vb = key(b)
            when {
                va == null && vb == null -> 0
                va == null -> 1
                vb == null -> -1
                else -> values.compare(va, vb)
            }
        }
    }

    override fun toString(): String = displayName
}

/**
 * How a field's directions read, in words rather than ASC/DESC: "Newest first" says what it does, where
 * "Descending" leaves the user to work out what a descending date is.
 */
enum class FieldKind(val ascending: String, val descending: String) {
    TEXT("A → Z", "Z → A"),
    DATE("Oldest first", "Newest first"),
    WORKFLOW("To do → Done", "Done → To do"),
    PRIORITY("Highest first", "Lowest first"),
    ;

    fun label(direction: SortDirection): String =
        if (direction == SortDirection.ASCENDING) ascending else descending
}

/**
 * The fields Tasker sorts by. [ALL] is the order sort controls offer them in: what every server reports
 * first, then what only some do. A field added here is offered everywhere, saved, and restored.
 */
object SortFields {

    private val NATURAL: Comparator<String> = Comparator(StringUtil::naturalCompare)

    val ID = SortField("id", "ID", FieldKind.TEXT, allServers = true, { it.id }, NATURAL)

    val SUMMARY = SortField("summary", "Summary", FieldKind.TEXT, allServers = true, { it.summary }, NATURAL)

    /** By where the state sits in a workflow rather than alphabetically; ties by name. */
    val STATUS = SortField(
        "status", "Status", FieldKind.WORKFLOW, allServers = true,
        { it.status.takeIf(String::isNotBlank) },
        compareBy<String> { workflowPosition(statusKind(it)) }.then(NATURAL),
    )

    val CREATED = SortField("created", "Created", FieldKind.DATE, allServers = true, { it.created }, naturalOrder())

    val UPDATED = SortField("updated", "Updated", FieldKind.DATE, allServers = true, { it.updated }, naturalOrder())

    val SERVER = SortField("server", "Server", FieldKind.TEXT, allServers = true, { it.serverName }, NATURAL)

    /** By the first assignee's name; unassigned and unknown rows have none, and go last. */
    val ASSIGNEE = SortField(
        "assignee", "Assignee", FieldKind.TEXT, allServers = false,
        { it.assignees.firstOrNull()?.name }, NATURAL,
    )

    /** Most urgent first when ascending: a lower rank is more urgent. */
    val PRIORITY = SortField(
        "priority", "Priority", FieldKind.PRIORITY, allServers = false, { it.priority }, compareBy { it.rank },
    )

    val ALL: List<SortField<*>> = listOf(ID, SUMMARY, STATUS, CREATED, UPDATED, SERVER, ASSIGNEE, PRIORITY)

    fun byId(id: String): SortField<*>? = ALL.firstOrNull { it.id == id }

    /** A state no name reading recognises sits between work under way and work finished. */
    private fun workflowPosition(kind: StatusKind): Int = when (kind) {
        StatusKind.TODO -> 0
        StatusKind.REOPENED -> 1
        StatusKind.IN_PROGRESS -> 2
        StatusKind.CUSTOM -> 3
        StatusKind.DONE -> 4
    }
}
