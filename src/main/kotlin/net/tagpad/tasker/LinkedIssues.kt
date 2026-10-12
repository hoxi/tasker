package net.tagpad.tasker

/** Linked issues under one heading in the details pane: "Epic", "Sub-tasks", "Is blocked by". */
data class LinkGroup(val title: String, val issues: List<LinkedIssue>)

/**
 * [links] grouped and ordered for the details pane: epic, parent and sub-tasks first, then the other kinds of
 * link in the order the tracker first lists them. Within a group open issues come before resolved ones,
 * otherwise in the tracker's order. An issue listed twice under the same heading is shown once.
 */
fun linkGroups(links: List<LinkedIssue>): List<LinkGroup> =
    links
        .distinctBy { Triple(it.kind, it.relation.lowercase(), it.id) }
        .groupBy { it.kind to (if (it.kind == LinkKind.OTHER) it.relation.lowercase() else "") }
        .entries
        .sortedBy { (key, _) -> key.first.ordinal }
        .map { (key, issues) ->
            LinkGroup(groupTitle(key.first, issues.first().relation), issues.sortedBy { it.resolved })
        }

private fun groupTitle(kind: LinkKind, relation: String): String = when (kind) {
    LinkKind.EPIC -> "Epic"
    LinkKind.PARENT -> "Parent"
    LinkKind.SUBTASK -> "Sub-tasks"
    LinkKind.OTHER -> relation.trim().replaceFirstChar { it.uppercaseChar() }.ifBlank { "Linked" }
}
