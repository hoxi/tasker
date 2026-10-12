package net.tagpad.tasker.sort

import com.intellij.openapi.util.text.StringUtil

enum class SortDirection {
    ASCENDING,
    DESCENDING,
    ;

    fun flipped(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

/**
 * One step of a sort: how it orders two rows. A [SortSpec] applies its rules in turn, each one deciding only
 * between rows the ones before it found equal.
 *
 * Rules are immutable; editing one means replacing it. A new kind of rule implements this, gets a
 * [SortRuleCodec] so it can be saved, and an editor in the sort popup's registry.
 */
sealed interface SortRule {

    fun comparator(): Comparator<SortableRow>

    /** The rule in a few words, for tooltips: "Updated, newest first". */
    val description: String
}

/** Orders by one [field], in one [direction]. */
data class FieldSortRule(val field: SortField<*>, val direction: SortDirection) : SortRule {
    override fun comparator(): Comparator<SortableRow> = field.comparator(direction)

    override val description: String
        get() = "${this.field.displayName}, ${this.field.kind.label(direction).lowercase()}"
}

/**
 * Orders by who a row is assigned to: rows assigned to someone in [people] first, in the order listed, then
 * everyone else as [others] says.
 *
 * A row assigned to several people takes the place of the first of them the list mentions. The list is a
 * ranking, not a direction — "my issues, then Anna's" — which is why this is a rule of its own rather than a
 * field.
 */
data class AssigneeSortRule(
    val people: List<PersonMatcher>,
    val others: OthersPlacement = OthersPlacement.AFTER_BY_NAME,
) : SortRule {

    override val description: String get() = "Assignee"

    override fun comparator(): Comparator<SortableRow> = Comparator { a, b ->
        val pa = place(a)
        val pb = place(b)
        when {
            pa != pb -> pa.compareTo(pb)
            pa == othersPlace && others == OthersPlacement.AFTER_BY_NAME ->
                StringUtil.naturalCompare(firstName(a), firstName(b))
            else -> 0
        }
    }

    /** Where everyone the list doesn't mention goes: after the list, or ahead of it. */
    private val othersPlace: Int get() = if (others == OthersPlacement.BEFORE) -1 else people.size

    private fun place(row: SortableRow): Int {
        val index = people.indexOfFirst { it.matches(row) }
        return if (index >= 0) index else othersPlace
    }

    /** Unassigned rows have no name to sort by, and go last among the others. */
    private fun firstName(row: SortableRow): String = row.assignees.firstOrNull()?.name ?: "￿"
}

/** Who an [AssigneeSortRule] places: the user, nobody, or someone by name. */
sealed interface PersonMatcher {

    fun matches(row: SortableRow): Boolean

    /** Whoever each server's credentials belong to. */
    data object Me : PersonMatcher {
        override fun matches(row: SortableRow): Boolean = row.assignees.any { it.isMe }
    }

    /** Rows with nobody assigned. */
    data object Unassigned : PersonMatcher {
        override fun matches(row: SortableRow): Boolean = row.assignees.isEmpty()
    }

    /** Someone by the name trackers show for them, case-insensitively. */
    data class Named(val name: String) : PersonMatcher {
        override fun matches(row: SortableRow): Boolean = row.assignees.any { it.name.equals(name, ignoreCase = true) }
    }
}

/** Where an [AssigneeSortRule] puts the rows its list doesn't mention. */
enum class OthersPlacement(val label: String) {
    AFTER_BY_NAME("after, by name"),
    AFTER("after, in their order"),
    BEFORE("before"),
}
