package net.tagpad.tasker.sort

import java.util.Date

/**
 * What the sort engine knows about a row, and all it knows: values, not tasks, servers or Swing.
 *
 * The task list implements this for its rows; anything else that wants sorting the same way can too.
 * Values a row's tracker doesn't report are null or empty, and sort after every row that has them.
 */
interface SortableRow {
    val id: String
    val summary: String

    /** The state as the tracker names it, e.g. "In Review". */
    val status: String

    val created: Date?
    val updated: Date?

    /** The name of the server the row came from. */
    val serverName: String

    /** Who the row is assigned to, in the tracker's order; empty when unassigned or unknown. */
    val assignees: List<SortPerson>

    /** How urgent the row is, where its tracker says; null otherwise. */
    val priority: SortPriority?
}

/**
 * A person a row can be assigned to. [isMe] says whether it is whoever the row's server credentials
 * belong to — worked out per server, since "me" is a different account on every tracker.
 */
data class SortPerson(val name: String, val isMe: Boolean = false)

/**
 * A priority with its place among its tracker's priorities: a lower [rank] is more urgent. Ranks are only
 * comparable within one tracker's scale, which is why they come from the tracker rather than the name.
 */
data class SortPriority(val name: String, val rank: Int)
