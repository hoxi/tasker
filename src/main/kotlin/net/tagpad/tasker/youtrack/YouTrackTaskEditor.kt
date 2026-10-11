package net.tagpad.tasker.youtrack

import com.google.gson.JsonObject
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.ColorIcon
import net.tagpad.tasker.CodeHost
import net.tagpad.tasker.CommentReaction
import net.tagpad.tasker.EditableComment
import net.tagpad.tasker.JSON_CONTENT_TYPE
import net.tagpad.tasker.LinkedPullRequest
import net.tagpad.tasker.ReactableComment
import net.tagpad.tasker.TaskEditor
import net.tagpad.tasker.TaskExtras
import net.tagpad.tasker.TaskLabel
import net.tagpad.tasker.TaskPriority
import net.tagpad.tasker.baseUrlOrNull
import net.tagpad.tasker.basicAuth
import net.tagpad.tasker.credentialsOrNull
import net.tagpad.tasker.currentUserId
import net.tagpad.tasker.idOrEmpty
import net.tagpad.tasker.objectOrNull
import net.tagpad.tasker.parseHexColor
import net.tagpad.tasker.readJsonObject
import net.tagpad.tasker.readJsonObjects
import net.tagpad.tasker.sendJson
import net.tagpad.tasker.stringOrEmpty
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

    override val suppliesComments: Boolean get() = true

    /**
     * Read here because the connector never does: `YouTrackTask.getComments()` returns an empty array
     * unconditionally, and its issue query doesn't ask for comments to begin with.
     *
     * `textPreview` is YouTrack's own HTML rendering of the markdown, which is what the pane expects a
     * comment body to be; the raw `text` is only the fallback, escaped.
     */
    override fun comments(task: Task): List<Comment> {
        val fields = "id,text,textPreview,created,deleted,author(id,fullName,login,avatarUrl)," +
            "reactions(reaction,author(id))"
        val me = currentUser()
        return readJsonObjects("${requireEndpoint(task)}/comments?fields=$fields", headers())
            .filterNot { it.get("deleted")?.takeIf { d -> d.isJsonPrimitive }?.asBoolean == true }
            .map { YouTrackComment(it, me) }
    }

    /**
     * Priority and tags, both with YouTrack's colours.
     *
     * The other fields need no request: YouTrack is the one provider whose connector fills in
     * [Task.getCustomProperties], and the pane shows those already. Priority is among them, but only as
     * text; read here it comes with its colour, which stands in for an icon — YouTrack draws none.
     */
    override fun extras(task: Task): TaskExtras {
        val fields = "customFields(name,value(name,color(background,foreground)))," +
            "tags(name,color(background,foreground))"
        val issue = readJsonObject("${endpoint(task) ?: return TaskExtras()}?fields=$fields", headers())

        val priority = issue.get("customFields")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.firstOrNull { it.stringOrEmpty("name").equals("Priority", ignoreCase = true) }
            ?.objectOrNull("value")
            ?.let { value ->
                val name = value.stringOrEmpty("name").takeIf { it.isNotBlank() } ?: return@let null
                val color = parseHexColor(value.objectOrNull("color")?.stringOrEmpty("background"))
                TaskPriority(name, color?.let { ColorIcon(16, 16, 10, 10, it, false, 3) })
            }
        val tags = issue.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.mapNotNull { tag ->
                val name = tag.stringOrEmpty("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val style = tag.objectOrNull("color")
                TaskLabel(
                    name,
                    background = parseHexColor(style?.stringOrEmpty("background")),
                    foreground = parseHexColor(style?.stringOrEmpty("foreground")),
                )
            }
            .orEmpty()
        return TaskExtras(priority = priority, labels = tags)
    }

    override fun canEditComment(task: Task, comment: Comment): Boolean =
        (comment as? YouTrackComment)?.isMine == true && endpoint(task) != null

    /** Like issues, comments are updated with a POST to the entity itself. */
    override fun editComment(task: Task, comment: Comment, body: String) {
        val id = (comment as? YouTrackComment)?.commentId?.takeIf { it.isNotBlank() }
            ?: error("This comment can't be addressed on ${repository.presentableName}")
        val payload = JsonObject().apply { addProperty("text", body) }
        HttpRequests.post("${requireEndpoint(task)}/comments/$id", JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /**
     * Pull requests from the issue's VCS changes, which hold commits and pull requests in one list told
     * apart by `$type`. YouTrack reports no state for a pull request, so none is shown.
     *
     * The pull request fields are asked for on the whole list. Should a server refuse fields that its
     * commits don't have, the base ones are asked for instead, and the title taken from the text.
     */
    override fun pullRequests(task: Task): List<LinkedPullRequest> {
        val endpoint = endpoint(task) ?: return emptyList()
        val full = "\$type,id,idReadable,idExternal,title,url,urls,text"
        val base = "\$type,id,urls,text"
        fun read(fields: String) =
            readJsonObjects("$endpoint/vcsChanges?fields=$fields&\$top=$MAX_VCS_CHANGES", headers())
        val changes = runCatching { read(full) }.getOrElse { read(base) }

        return changes
            .filter { it.stringOrEmpty("\$type") == "PullRequest" }
            .mapNotNull { pr ->
                val urls = pr.get("urls")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
                val url = pr.stringOrEmpty("url").ifBlank { urls.firstOrNull().orEmpty() }.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val host = when {
                    "github" in url -> CodeHost.GITHUB
                    "gitlab" in url || "/-/merge_requests/" in url -> CodeHost.GITLAB
                    "bitbucket" in url -> CodeHost.BITBUCKET
                    else -> CodeHost.OTHER
                }
                val number = pr.stringOrEmpty("idExternal")
                val reference = pr.stringOrEmpty("idReadable").ifBlank {
                    if (number.isBlank()) "" else if (host == CodeHost.GITLAB) "!$number" else "#$number"
                }
                val title = pr.stringOrEmpty("title")
                    .ifBlank { pr.stringOrEmpty("text").lineSequence().firstOrNull().orEmpty() }
                LinkedPullRequest(host, reference, title, url)
            }
    }

    /** The current user's entity id, which is how comment and reaction authors are matched to them. */
    private fun currentUser(): String? = repository.currentUserId {
        readJsonObject("${repository.baseUrlOrNull() ?: return@currentUserId null}/api/users/me?fields=id", headers())
            .idOrEmpty("id")
    }

    private inner class YouTrackComment(json: JsonObject, me: String?) : Comment(), ReactableComment, EditableComment {
        private val author = json.objectOrNull("author")

        override val commentId: String = json.idOrEmpty("id")

        override val isMine: Boolean = me != null && author?.idOrEmpty("id") == me

        /** Markdown, as YouTrack stores it and takes it back. */
        override val source: String = json.stringOrEmpty("text")

        /** One entry per person per reaction, folded into a tally per reaction in first-seen order. */
        override val reactions: List<CommentReaction> =
            json.get("reactions")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                .orEmpty()
                .groupBy { it.stringOrEmpty("reaction") }
                .filterKeys { it.isNotBlank() }
                .map { (name, entries) ->
                    CommentReaction(
                        emoji = REACTION_EMOJI[name] ?: ":$name:",
                        count = entries.size,
                        reactedByMe = me != null && entries.any { it.objectOrNull("author")?.idOrEmpty("id") == me },
                    )
                }
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

    private companion object {
        /** VCS changes read per issue; commits count towards it too, and pull requests are among them. */
        const val MAX_VCS_CHANGES = 200

        /**
         * YouTrack names its reactions rather than sending the emoji, and draws its own artwork for each,
         * so this is the nearest Unicode emoji to every name its API documentation lists. A name added
         * since falls back to `:name:` in the pane, which still says what it is.
         */
        val REACTION_EMOJI: Map<String, String> = mapOf(
            "yes" to "✅", "no" to "❌", "question" to "❓", "comment" to "💬", "waiting" to "⏳",
            "thanks" to "🙏", "thank-you" to "🙏", "thumbs-up" to "👍", "thumbs-down" to "👎",
            "plus-one" to "➕", "minus-one" to "➖", "ok" to "👌", "okay" to "🆗", "100" to "💯",
            "strong" to "💪", "clapping" to "👏", "congratulations" to "🎉", "fist" to "👊",
            "raised-hand" to "✋", "wave" to "👋", "rock" to "🤘", "crossed-fingers" to "🤞",
            "glad" to "😊", "grinning" to "😀", "joy" to "😂", "tongue-out" to "😛", "wink" to "😉",
            "saint" to "😇", "cool" to "😎", "nerd" to "🤓", "relieved" to "😌", "surprised" to "😮",
            "worry" to "😟", "grimacing" to "😬", "scared" to "😱", "sick" to "🤢", "tired" to "😫",
            "sleepy" to "😪", "tears" to "😢", "mind-blown" to "🤯", "cat-in-love" to "😻",
            "scared-cat" to "🙀", "eyes" to "👀", "rocket" to "🚀", "teddybear" to "🧸", "red-heart" to "❤️",
            "zero" to "0️⃣", "one" to "1️⃣", "two" to "2️⃣", "three" to "3️⃣", "four" to "4️⃣",
            "five" to "5️⃣", "six" to "6️⃣", "seven" to "7️⃣", "eight" to "8️⃣", "nine" to "9️⃣",
        )
    }
}
