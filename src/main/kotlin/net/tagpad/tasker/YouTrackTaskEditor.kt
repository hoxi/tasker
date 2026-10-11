package net.tagpad.tasker

import com.google.gson.JsonObject
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests
import java.util.Date

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

    /**
     * Read here because the connector never does: `YouTrackTask.getComments()` returns an empty array
     * unconditionally, and its issue query doesn't ask for comments to begin with.
     *
     * `textPreview` is YouTrack's own HTML rendering of the markdown, which is what the pane expects a
     * comment body to be; the raw `text` is only the fallback, escaped.
     */
    override fun comments(task: Task): List<Comment> {
        val fields = "text,textPreview,created,deleted,author(fullName,login,avatarUrl)"
        return readJsonObjects("${requireEndpoint(task)}/comments?fields=$fields", headers())
            .filterNot { it.get("deleted")?.takeIf { d -> d.isJsonPrimitive }?.asBoolean == true }
            .map(::YouTrackComment)
    }

    private inner class YouTrackComment(json: JsonObject) : Comment() {
        private val author = json.objectOrNull("author")
        private val html = json.stringOrEmpty("textPreview").ifBlank {
            StringUtil.escapeXmlEntities(json.stringOrEmpty("text")).replace("\n", "<br>")
        }
        private val created = json.get("created")?.takeIf { it.isJsonPrimitive }?.asLong?.let(::Date)

        /** Hub hands out avatars as server-relative paths. */
        private val avatar = author?.stringOrEmpty("avatarUrl")?.takeIf { it.isNotBlank() }?.let { url ->
            if (url.startsWith("/")) repository.baseUrlOrNull()?.let { it + url } else url
        }

        override fun getText(): String = html

        override fun getAuthor(): String? =
            author?.let { it.stringOrEmpty("fullName").ifBlank { it.stringOrEmpty("login") } }
                ?.takeIf { it.isNotBlank() }

        override fun getDate(): Date? = created

        /** Emits the avatar ahead of the body, which is where the details pane looks for one. */
        override fun appendTo(builder: StringBuilder) {
            if (avatar != null) builder.append("<img src='").append(StringUtil.escapeXmlEntities(avatar)).append("'>")
            builder.append(html)
        }
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
