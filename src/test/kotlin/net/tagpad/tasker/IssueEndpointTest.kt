package net.tagpad.tasker

import net.tagpad.tasker.github.githubIssueEndpoint
import net.tagpad.tasker.gitlab.gitLabIssueEndpoint
import net.tagpad.tasker.jira.jiraCodeHost
import net.tagpad.tasker.jira.jiraPullRequestReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IssueEndpointTest {

    @Test
    fun `github dot com issues go to the API host`() {
        assertEquals(
            "https://api.github.com/repos/hoxi/tasker/issues/7",
            githubIssueEndpoint("https://github.com/hoxi/tasker/issues/7"),
        )
    }

    @Test
    fun `GitHub Enterprise issues go to its api v3 mount`() {
        assertEquals(
            "https://git.example.com/api/v3/repos/team/my-repo/issues/12",
            githubIssueEndpoint("https://git.example.com/team/my-repo/issues/12"),
        )
    }

    @Test
    fun `GitHub urls that aren't issues have no endpoint`() {
        assertNull(githubIssueEndpoint("https://github.com/hoxi/tasker/pull/7"))
        assertNull(githubIssueEndpoint("https://github.com/hoxi/tasker"))
        assertNull(githubIssueEndpoint("not a url"))
    }

    @Test
    fun `GitLab issues are addressed by their encoded project path`() {
        assertEquals(
            "https://gitlab.com/api/v4/projects/group%2Fsub%2Fproject/issues/42",
            gitLabIssueEndpoint("https://gitlab.com", "https://gitlab.com/group/sub/project/-/issues/42"),
        )
        assertEquals(
            "https://gitlab.com/api/v4/projects/group%2Fproject/issues/3",
            gitLabIssueEndpoint("https://gitlab.com", "https://gitlab.com/group/project/issues/3"),
        )
    }

    @Test
    fun `GitLab urls that aren't issues have no endpoint`() {
        assertNull(gitLabIssueEndpoint("https://gitlab.com", "https://gitlab.com/group/project/-/merge_requests/3"))
    }

    @Test
    fun `Jira dev-status application types map to code hosts`() {
        assertEquals(CodeHost.GITLAB, jiraCodeHost("GitLab"))
        assertEquals(CodeHost.GITHUB, jiraCodeHost("githube"))
        assertEquals(CodeHost.BITBUCKET, jiraCodeHost("stash"))
        assertEquals(CodeHost.OTHER, jiraCodeHost("something"))
    }

    @Test
    fun `Jira pull request references get their host's prefix`() {
        assertEquals("!12", jiraPullRequestReference("12", CodeHost.GITLAB))
        assertEquals("#12", jiraPullRequestReference("12", CodeHost.GITHUB))
        assertEquals("#5", jiraPullRequestReference("#5", CodeHost.GITLAB))
        assertEquals("", jiraPullRequestReference("", CodeHost.GITHUB))
    }
}
