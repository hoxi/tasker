package net.tagpad.tasker

import com.google.gson.JsonObject
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.impl.BaseRepository
import com.intellij.util.io.HttpRequests
import java.net.URI
import java.net.URISyntaxException

/**
 * Edits GitHub issues over their REST API, since the tasks API can't express any of these operations.
 *
 * Credentials come from the server the user already configured under Settings | Tools | Tasks: GitHub
 * keeps its token in the password field — `GithubRepository.getExecutor()` hands `getPassword()`
 * straight to its API executor — so nothing extra needs configuring here.
 */
class GitHubTaskEditor(private val repository: TaskRepository) : TaskEditor {

    override val providerName: String get() = "GitHub"

    // One endpoint serves all three operations, so a task is editable exactly when we can address it.
    override fun canRename(task: Task): Boolean = endpoint(task) != null

    override fun canEditDescription(task: Task): Boolean = endpoint(task) != null

    override fun canComment(task: Task): Boolean = endpoint(task) != null

    override fun rename(task: Task, summary: String): Unit = patchIssue(task, "title", summary)

    override fun setDescription(task: Task, description: String): Unit = patchIssue(task, "body", description)

    override fun addComment(task: Task, body: String) {
        val payload = JsonObject().apply { addProperty("body", body) }
        HttpRequests.post("${requireEndpoint(task)}/comments", JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    private fun patchIssue(task: Task, field: String, value: String) {
        val payload = JsonObject().apply { addProperty(field, value) }
        HttpRequests.patch(requireEndpoint(task), JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    private fun headers(): Map<String, String> {
        val token = token() ?: error("No GitHub token configured for ${repository.presentableName}")
        return mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to API_VERSION,
        )
    }

    /**
     * Maps a task to its issue endpoint, e.g. `https://github.com/owner/repo/issues/7` becomes
     * `https://api.github.com/repos/owner/repo/issues/7`. Null means "not addressable", which is what
     * drives the greyed-out state.
     *
     * Derived from [Task.getIssueUrl] rather than [Task.getId], because the id is `repoName + "-" +
     * number` and can't be split back apart when the repo name itself contains a dash.
     */
    private fun endpoint(task: Task): String? {
        if (token() == null) return null
        val issueUrl = task.issueUrl?.takeIf { it.isNotBlank() } ?: return null

        val uri = try {
            URI(issueUrl)
        } catch (e: URISyntaxException) {
            return null
        }
        val host = uri.host ?: return null
        val path = uri.path?.trim('/')?.split('/') ?: return null
        if (path.size < 4 || path[2] != "issues" || path[3].toIntOrNull() == null) return null

        // github.com is served from a separate API host; Enterprise mounts the same API under /api/v3.
        val apiBase =
            if (host.equals("github.com", ignoreCase = true) || host.equals("www.github.com", ignoreCase = true)) {
                "https://api.github.com"
            } else {
                "https://$host/api/v3"
            }
        return "$apiBase/repos/${path[0]}/${path[1]}/issues/${path[3]}"
    }

    private fun requireEndpoint(task: Task): String =
        endpoint(task) ?: error("${task.presentableId} has no usable GitHub issue url")

    private fun token(): String? = (repository as? BaseRepository)?.password?.takeIf { it.isNotBlank() }

    private companion object {
        const val API_VERSION = "2022-11-28"
    }
}
