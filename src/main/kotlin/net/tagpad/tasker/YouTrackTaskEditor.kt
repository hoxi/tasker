package net.tagpad.tasker

import com.google.gson.JsonObject
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests

/**
 * Edits YouTrack issues over the `/api` REST endpoints.
 *
 * `YouTrackRepository` forces `isUseHttpAuthentication()` to true and labels its password field
 * "Token", so the platform reads this server with Basic auth over username plus permanent token. We
 * authenticate the same way, which keeps writes working wherever the issue list already does.
 */
class YouTrackTaskEditor(private val repository: TaskRepository) : TaskEditor {

    override val providerName: String get() = "YouTrack"

    override fun canRename(task: Task): Boolean = endpoint(task) != null

    override fun canEditDescription(task: Task): Boolean = endpoint(task) != null

    override fun canComment(task: Task): Boolean = endpoint(task) != null

    override fun rename(task: Task, summary: String): Unit = updateIssue(task, "summary", summary)

    override fun setDescription(task: Task, description: String): Unit = updateIssue(task, "description", description)

    override fun addComment(task: Task, body: String) {
        // YouTrack names the comment body "text", not "body".
        val payload = JsonObject().apply { addProperty("text", body) }
        HttpRequests.post("${requireEndpoint(task)}/comments", JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /** YouTrack updates entities with POST rather than PUT or PATCH. */
    private fun updateIssue(task: Task, field: String, value: String) {
        val payload = JsonObject().apply { addProperty(field, value) }
        HttpRequests.post(requireEndpoint(task), JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    private fun headers(): Map<String, String> {
        val (username, token) = repository.credentialsOrNull()
            ?: error("No YouTrack token configured for ${repository.presentableName}")
        return mapOf(
            "Authorization" to basicAuth(username, token),
            "Accept" to JSON_CONTENT_TYPE,
        )
    }

    /**
     * `<server>/api/issues/<id>`, built from the configured server url rather than the issue url —
     * `YouTrackTask.getIssueUrl()` is itself just the server url plus a web path, so the repository is
     * the more direct source. The REST API accepts the entity id that [Task.getId] carries.
     */
    private fun endpoint(task: Task): String? {
        if (repository.credentialsOrNull() == null) return null
        val base = repository.baseUrlOrNull() ?: return null
        val id = task.id.takeIf { it.isNotBlank() } ?: return null
        return "$base/api/issues/$id"
    }

    private fun requireEndpoint(task: Task): String =
        endpoint(task) ?: error("${task.presentableId} cannot be addressed on ${repository.presentableName}")
}
