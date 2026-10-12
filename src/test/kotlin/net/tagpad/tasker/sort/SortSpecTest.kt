package net.tagpad.tasker.sort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Date

class SortSpecTest {

    private data class Row(
        override val id: String,
        override val summary: String = "",
        override val status: String = "Open",
        override val created: Date? = null,
        override val updated: Date? = null,
        override val serverName: String = "server",
        override val assignees: List<SortPerson> = emptyList(),
        override val priority: SortPriority? = null,
    ) : SortableRow

    private fun ids(spec: SortSpec, rows: List<Row>): List<String> = spec.sort(rows).map { it.id }

    @Test
    fun `an empty spec keeps the servers' order`() {
        val rows = listOf(Row("B-2"), Row("A-1"))
        assertEquals(listOf("B-2", "A-1"), ids(SortSpec(), rows))
    }

    @Test
    fun `ids sort naturally`() {
        val spec = SortSpec(listOf(FieldSortRule(SortFields.ID, SortDirection.ASCENDING)))
        assertEquals(listOf("PC-2", "PC-10"), ids(spec, listOf(Row("PC-10"), Row("PC-2"))))
    }

    @Test
    fun `rows without a value sort last in either direction`() {
        val rows = listOf(Row("none"), Row("old", updated = Date(1)), Row("new", updated = Date(2)))
        val newest = SortSpec(listOf(FieldSortRule(SortFields.UPDATED, SortDirection.DESCENDING)))
        val oldest = SortSpec(listOf(FieldSortRule(SortFields.UPDATED, SortDirection.ASCENDING)))
        assertEquals(listOf("new", "old", "none"), ids(newest, rows))
        assertEquals(listOf("old", "new", "none"), ids(oldest, rows))
    }

    @Test
    fun `status sorts by workflow, not alphabetically`() {
        val rows = listOf(Row("done", status = "Done"), Row("doing", status = "In Progress"), Row("todo", status = "To Do"))
        val spec = SortSpec(listOf(FieldSortRule(SortFields.STATUS, SortDirection.ASCENDING)))
        assertEquals(listOf("todo", "doing", "done"), ids(spec, rows))
    }

    @Test
    fun `priority sorts by rank, most urgent first`() {
        val rows = listOf(
            Row("low", priority = SortPriority("Low", 4)),
            Row("none"),
            Row("high", priority = SortPriority("High", 1)),
        )
        val spec = SortSpec(listOf(FieldSortRule(SortFields.PRIORITY, SortDirection.ASCENDING)))
        assertEquals(listOf("high", "low", "none"), ids(spec, rows))
    }

    @Test
    fun `assignee rule ranks the listed people, then everyone else by name`() {
        val rows = listOf(
            Row("bo", assignees = listOf(SortPerson("Bo"))),
            Row("nobody"),
            Row("anna", assignees = listOf(SortPerson("Anna Berg"))),
            Row("mine", assignees = listOf(SortPerson("Magnus", isMe = true))),
            Row("al", assignees = listOf(SortPerson("Al"))),
        )
        val spec = SortSpec(listOf(AssigneeSortRule(listOf(PersonMatcher.Me, PersonMatcher.Named("anna berg")))))
        assertEquals(listOf("mine", "anna", "al", "bo", "nobody"), ids(spec, rows))
    }

    @Test
    fun `assignee rule can put everyone else first`() {
        val rows = listOf(Row("mine", assignees = listOf(SortPerson("Me", isMe = true))), Row("other"))
        val spec = SortSpec(listOf(AssigneeSortRule(listOf(PersonMatcher.Me), OthersPlacement.BEFORE)))
        assertEquals(listOf("other", "mine"), ids(spec, rows))
    }

    @Test
    fun `later rules only break earlier rules' ties, and ties keep server order`() {
        val rows = listOf(
            Row("b", status = "Open", updated = Date(1)),
            Row("a", status = "Open", updated = Date(1)),
            Row("c", status = "Open", updated = Date(2)),
            Row("d", status = "Done", updated = Date(3)),
        )
        val spec = SortSpec(
            listOf(
                FieldSortRule(SortFields.STATUS, SortDirection.ASCENDING),
                FieldSortRule(SortFields.UPDATED, SortDirection.DESCENDING),
            ),
        )
        assertEquals(listOf("c", "b", "a", "d"), ids(spec, rows))
    }

    @Test
    fun `a header click sorts by that column alone, and a second one flips it`() {
        val byStatus = SortSpec(listOf(FieldSortRule(SortFields.STATUS, SortDirection.ASCENDING)))
        val clicked = byStatus.withHeaderClick(SortFields.UPDATED, additive = false)
        assertEquals(SortSpec(listOf(FieldSortRule(SortFields.UPDATED, SortDirection.ASCENDING))), clicked)
        assertEquals(
            SortSpec(listOf(FieldSortRule(SortFields.UPDATED, SortDirection.DESCENDING))),
            clicked.withHeaderClick(SortFields.UPDATED, additive = false),
        )
    }

    @Test
    fun `a shift click adds a rule, or flips it where it is`() {
        val byStatus = SortSpec(listOf(FieldSortRule(SortFields.STATUS, SortDirection.ASCENDING)))
        val added = byStatus.withHeaderClick(SortFields.UPDATED, additive = true)
        assertEquals(2, added.rules.size)
        val flipped = added.withHeaderClick(SortFields.STATUS, additive = true)
        assertEquals(FieldSortRule(SortFields.STATUS, SortDirection.DESCENDING), flipped.rules[0])
        assertEquals(added.rules[1], flipped.rules[1])
    }

    @Test
    fun `header indicators number the columns only when several sort`() {
        val one = SortSpec(listOf(FieldSortRule(SortFields.ID, SortDirection.ASCENDING)))
        assertEquals(SortIndicator(1, SortDirection.ASCENDING, ofMany = false), one.indicatorFor(SortFields.ID))
        val two = one.withHeaderClick(SortFields.UPDATED, additive = true)
        assertEquals(SortIndicator(2, SortDirection.ASCENDING, ofMany = true), two.indicatorFor(SortFields.UPDATED))
        assertNull(two.indicatorFor(SortFields.SUMMARY))
    }
}
