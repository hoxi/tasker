package net.tagpad.tasker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkedIssuesTest {

    private fun titles(groups: List<LinkGroup>) = groups.map { it.title }

    private fun ids(group: LinkGroup) = group.issues.map { it.id }

    @Test
    fun `the hierarchy comes first, then other links in the tracker's order`() {
        val links = listOf(
            LinkedIssue(LinkKind.OTHER, "P-9", "", relation = "relates to"),
            LinkedIssue(LinkKind.SUBTASK, "P-3", ""),
            LinkedIssue(LinkKind.OTHER, "P-8", "", relation = "is blocked by"),
            LinkedIssue(LinkKind.EPIC, "P-1", ""),
            LinkedIssue(LinkKind.PARENT, "P-2", ""),
        )
        assertEquals(listOf("Epic", "Parent", "Sub-tasks", "Relates to", "Is blocked by"), titles(linkGroups(links)))
    }

    @Test
    fun `open issues come before resolved ones within a group`() {
        val links = listOf(
            LinkedIssue(LinkKind.SUBTASK, "P-1", "", resolved = true),
            LinkedIssue(LinkKind.SUBTASK, "P-2", ""),
            LinkedIssue(LinkKind.SUBTASK, "P-3", ""),
        )
        assertEquals(listOf("P-2", "P-3", "P-1"), ids(linkGroups(links).single()))
    }

    @Test
    fun `the same relation in another case is one group, and a repeated issue shows once`() {
        val links = listOf(
            LinkedIssue(LinkKind.OTHER, "P-1", "", relation = "blocks"),
            LinkedIssue(LinkKind.OTHER, "P-2", "", relation = "Blocks"),
            LinkedIssue(LinkKind.OTHER, "P-1", "", relation = "blocks"),
        )
        val group = linkGroups(links).single()
        assertEquals("Blocks", group.title)
        assertEquals(listOf("P-1", "P-2"), ids(group))
    }

    @Test
    fun `an issue can be both linked and a sub-task`() {
        val links = listOf(
            LinkedIssue(LinkKind.SUBTASK, "P-1", ""),
            LinkedIssue(LinkKind.OTHER, "P-1", "", relation = "relates to"),
        )
        assertEquals(listOf("Sub-tasks", "Relates to"), titles(linkGroups(links)))
    }
}
