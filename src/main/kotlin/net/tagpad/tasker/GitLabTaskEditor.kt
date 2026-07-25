package net.tagpad.tasker

import com.google.gson.JsonObject
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Edits GitLab issues over the v4 REST API.
 *
 * Authentication mirrors `GitlabRepository`, which sends the configured password as a `PRIVATE-TOKEN`
 * header, so a server whose issue list already loads will accept these writes unchanged.
 */
class GitLabTaskEditor(private val repository: TaskRepository) : TaskEditor {

    override val providerName: String get() = "GitLab"

    override fun canRename(task: Task): Boolean = endpoint(task) != null

    override fun canEditDescription(task: Task): Boolean = endpoint(task) != null

    override fun canComment(task: Task): Boolean = endpoint(task) != null

    override fun rename(task: Task, summary: String): Unit = updateIssue(task, "title", summary)

    override fun setDescription(task: Task, description: String): Unit = updateIssue(task, "description", description)

    override fun addComment(task: Task, body: String) {
        val payload = JsonObject().apply { addProperty("body", body) }
        HttpRequests.post("${requireEndpoint(task)}/notes", JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /**
     * Read back from the server rather than trusting the task.
     *
     * `GitlabTask.getDescription()` is hardcoded to return null even though the issue behind it has a
     * description, so prefilling the edit dialog from the task would open it blank and turn a save into
     * a silent wipe of whatever was there.
     */
    override fun currentDescription(task: Task): String =
        readJsonObject(requireEndpoint(task), headers()).stringOrEmpty("description")

    private fun updateIssue(task: Task, field: String, value: String) {
        val payload = JsonObject().apply { addProperty(field, value) }
        HttpRequests.put(requireEndpoint(task), JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    private fun headers(): Map<String, String> {
        val (_, token) = repository.credentialsOrNull() ?: error("No GitLab token configured for ${repository.presentableName}")
        return mapOf("PRIVATE-TOKEN" to token, "Accept" to JSON_CONTENT_TYPE)
    }

    /**
     * `https://host/group/project/issues/42` becomes
     * `https://host/api/v4/projects/group%2Fproject/issues/42`.
     *
     * The project has to be identified by its url-encoded path because the numeric project id never
     * reaches us — [Task] doesn't expose it. This is the same approach `GitlabRepository.updateTimeSpent`
     * takes, extended to tolerate the `/-/` separator that newer GitLab web urls insert.
     */
    private fun endpoint(task: Task): String? {
        if (repository.credentialsOrNull() == null) return null
        val base = repository.baseUrlOrNull() ?: return null
        val issueUrl = task.issueUrl?.takeIf { it.isNotBlank() } ?: return null
        val match = ISSUE_URL.matchEntire(issueUrl) ?: return null

        val project = URLEncoder.encode(match.groupValues[1], StandardCharsets.UTF_8)
        return "$base/api/v4/projects/$project/issues/${match.groupValues[2]}"
    }

    private fun requireEndpoint(task: Task): String =
        endpoint(task) ?: error("${task.presentableId} has no usable GitLab issue url")

    private companion object {
        private val ISSUE_URL = Regex("""https?://[^/]+/(.+?)(?:/-)?/issues/(\d+)""")
    }
}
