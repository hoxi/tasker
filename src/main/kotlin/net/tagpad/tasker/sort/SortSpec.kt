package net.tagpad.tasker.sort

/**
 * A whole sort: [rules] applied in turn. Empty means the servers' own order, which is also what breaks the
 * last tie — rows are sorted stably, so equal rows keep the order their server returned them in.
 */
data class SortSpec(val rules: List<SortRule> = emptyList()) {

    val isEmpty: Boolean get() = rules.isEmpty()

    /** Null for an empty spec, so a caller can skip sorting altogether. */
    fun comparator(): Comparator<SortableRow>? =
        rules.map(SortRule::comparator).reduceOrNull { first, then -> first.then(then) }

    /** Sorts [rows] by this spec, stably; rows come back untouched when there is nothing to sort by. */
    fun <R : SortableRow> sort(rows: List<R>): List<R> = comparator()?.let(rows::sortedWith) ?: rows

    /** Where [field] sits among the rules and in which direction, for a column header's arrow; null if absent. */
    fun indicatorFor(field: SortField<*>): SortIndicator? {
        val index = rules.indexOfFirst { it is FieldSortRule && it.field === field }
        if (index < 0) return null
        val rule = rules[index] as FieldSortRule
        return SortIndicator(position = index + 1, direction = rule.direction, ofMany = rules.size > 1)
    }

    /**
     * The spec after a click on [field]'s column header.
     *
     * A plain click sorts by that field alone, flipping its direction if it already was the only rule. With
     * [additive] — Shift held — the field is added as the next rule, or flipped where it already is, leaving
     * every other rule in place.
     */
    fun withHeaderClick(field: SortField<*>, additive: Boolean): SortSpec {
        val existing = rules.indexOfFirst { it is FieldSortRule && it.field === field }
        if (!additive) {
            val only = rules.singleOrNull() as? FieldSortRule
            val direction =
                if (only != null && only.field === field) only.direction.flipped() else SortDirection.ASCENDING
            return SortSpec(listOf(FieldSortRule(field, direction)))
        }
        if (existing < 0) return SortSpec(rules + FieldSortRule(field, SortDirection.ASCENDING))
        val flipped = (rules[existing] as FieldSortRule).let { it.copy(direction = it.direction.flipped()) }
        return SortSpec(rules.toMutableList().also { it[existing] = flipped })
    }
}

/** A column's place in a sort: its [position] among the rules, shown only when there are [ofMany]. */
data class SortIndicator(val position: Int, val direction: SortDirection, val ofMany: Boolean)
