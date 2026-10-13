package net.tagpad.tasker

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinksTest {

    @Test
    fun `merge request links are told from other GitLab links`() {
        assertTrue(isMergeRequestUrl("https://gitlab.example.com/a/b/c/-/merge_requests/4"))
        assertTrue(isMergeRequestUrl("https://gitlab.com/a/b/-/merge_requests/4/diffs"))
        assertFalse(isMergeRequestUrl("https://gitlab.com/a/b/-/merge_requests/new"))
        assertFalse(isMergeRequestUrl("https://gitlab.example.com/a/b/-/issues/4"))
        assertFalse(isMergeRequestUrl("https://github.com/a/b/pull/4"))
    }
}
