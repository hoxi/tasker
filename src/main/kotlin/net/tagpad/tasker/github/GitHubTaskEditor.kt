package net.tagpad.tasker.github

import com.google.gson.JsonObject
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.impl.BaseRepository
import com.intellij.util.io.HttpRequests
import net.tagpad.tasker.CodeHost
import net.tagpad.tasker.CommentReaction
import net.tagpad.tasker.EditableComment
import net.tagpad.tasker.JSON_CONTENT_TYPE
import net.tagpad.tasker.LinkedPullRequest
import net.tagpad.tasker.PullRequestState
import net.tagpad.tasker.ReactableComment
import net.tagpad.tasker.TaskEditor
import net.tagpad.tasker.TaskExtras
import net.tagpad.tasker.TaskLabel
import net.tagpad.tasker.addIfPresent
import net.tagpad.tasker.currentUserId
import net.tagpad.tasker.idOrEmpty
import net.tagpad.tasker.mapArray
import net.tagpad.tasker.objectOrNull
import net.tagpad.tasker.parseHexColor
import net.tagpad.tasker.readJsonObject
import net.tagpad.tasker.readJsonObjects
import net.tagpad.tasker.sendJson
import net.tagpad.tasker.stringOrEmpty
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.util.Date

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

    override val suppliesComments: Boolean get() = true

    /**
     * Read again rather than taken from the task, for the reactions: the connector's comment model keeps
     * the rendered body, author and avatar, and drops the `reactions` summary GitHub sends alongside.
     *
     * Null without a token, which leaves the connector's own comments in place — it reads public
     * repositories anonymously, where this adapter has no endpoint to call.
     */
    override fun comments(task: Task): List<Comment>? {
        val endpoint = endpoint(task) ?: return null
        // The full media type returns body_html, rendered as on github.com, and the markdown body an edit starts from.
        val headers = headers() + ("Accept" to "application/vnd.github.full+json")
        val me = currentUser(endpoint)

        val comments = ArrayList<Comment>()
        for (page in 1..MAX_COMMENT_PAGES) {
            val batch = readJsonObjects("$endpoint/comments?per_page=$COMMENTS_PER_PAGE&page=$page", headers)
            batch.mapTo(comments) { GitHubComment(it, me) }
            if (batch.size < COMMENTS_PER_PAGE) break
        }
        return comments
    }

    /**
     * Pull requests that mention the issue, from its timeline's `cross-referenced` events — which is
     * also where github.com's own "mentioned this" entries come from. A pull request linked only through
     * the sidebar's Development box, without ever mentioning the issue, isn't in the REST timeline.
     */
    override fun pullRequests(task: Task): List<LinkedPullRequest> {
        val endpoint = endpoint(task) ?: return emptyList()
        val issueRepo = endpoint.substringAfter("/repos/").substringBefore("/issues/")

        val found = LinkedHashMap<String, LinkedPullRequest>()
        for (page in 1..MAX_TIMELINE_PAGES) {
            val events = readJsonObjects("$endpoint/timeline?per_page=$COMMENTS_PER_PAGE&page=$page", headers())
            for (event in events) {
                if (event.stringOrEmpty("event") != "cross-referenced") continue
                val source = event.objectOrNull("source")?.objectOrNull("issue") ?: continue
                val pr = source.objectOrNull("pull_request") ?: continue
                val url = source.stringOrEmpty("html_url").takeIf { it.isNotBlank() } ?: continue

                val repo = source.objectOrNull("repository")?.stringOrEmpty("full_name").orEmpty()
                val number = source.idOrEmpty("number")
                val state = when {
                    pr.stringOrEmpty("merged_at").isNotBlank() -> PullRequestState.MERGED
                    source.stringOrEmpty("state") == "closed" -> PullRequestState.CLOSED
                    source.get("draft")?.takeIf { it.isJsonPrimitive }?.asBoolean == true -> PullRequestState.DRAFT
                    else -> PullRequestState.OPEN
                }
                found[url] = LinkedPullRequest(
                    host = CodeHost.GITHUB,
                    reference = if (repo.isBlank() || repo.equals(issueRepo, ignoreCase = true)) "#$number"
                    else "$repo#$number",
                    title = source.stringOrEmpty("title"),
                    url = url,
                    state = state,
                )
            }
            if (events.size < COMMENTS_PER_PAGE) break
        }
        return found.values.toList()
    }

    override fun canEditComment(task: Task, comment: Comment): Boolean =
        (comment as? GitHubComment)?.let { it.isMine && it.apiUrl != null } == true && token() != null

    /**
     * Sent to the comment's own API url. GitHub addresses comments per repository rather than per issue,
     * so this is the one write that can't be derived from the issue endpoint.
     */
    override fun editComment(task: Task, comment: Comment, body: String) {
        val url = (comment as? GitHubComment)?.apiUrl ?: error("This comment can't be addressed on GitHub")
        val payload = JsonObject().apply { addProperty("body", body) }
        HttpRequests.patch(url, JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /** The token owner's login, from the API host the issue [endpoint] lives on. */
    private fun currentUser(endpoint: String): String? = repository.currentUserId {
        readJsonObject(endpoint.substringBefore("/repos/") + "/user", headers()).stringOrEmpty("login")
    }

    private class GitHubComment(json: JsonObject, me: String?) : Comment(), ReactableComment, EditableComment {
        private val user = json.objectOrNull("user")

        override val commentId: String = json.idOrEmpty("id")

        override val isMine: Boolean = me != null && user?.stringOrEmpty("login").equals(me, ignoreCase = true)

        /** Markdown, as GitHub stores it and takes it back. */
        override val source: String = json.stringOrEmpty("body")

        val apiUrl: String? = json.stringOrEmpty("url").takeIf { it.isNotBlank() }
        private val html = json.stringOrEmpty("body_html").ifBlank {
            StringUtil.escapeXmlEntities(json.stringOrEmpty("body")).replace("\n", "<br>")
        }
        private val created = runCatching { Date.from(Instant.parse(json.stringOrEmpty("created_at"))) }.getOrNull()
        private val avatar = user?.stringOrEmpty("avatar_url")?.takeIf { it.isNotBlank() }

        /**
         * Counts only: the summary doesn't say who reacted, so none can be marked as the user's own
         * without a further request per comment.
         */
        override val reactions: List<CommentReaction> = json.objectOrNull("reactions")?.let { summary ->
            REACTION_EMOJI.mapNotNull { (key, emoji) ->
                val count = summary.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                CommentReaction(emoji, count).takeIf { count > 0 }
            }
        }.orEmpty()

        override fun getText(): String = html

        override fun getAuthor(): String? = user?.stringOrEmpty("login")?.takeIf { it.isNotBlank() }

        override fun getDate(): Date? = created

        /** Emits the avatar ahead of the body, which is where the details pane looks for one. */
        override fun appendTo(builder: StringBuilder) {
            if (avatar != null) builder.append("<img src='").append(StringUtil.escapeXmlEntities(avatar)).append("'>")
            builder.append(html)
        }
    }

    /**
     * Assignee and labels, from the issue itself.
     *
     * No time spent: GitHub's issue API has no time tracking at all, so there is nothing to read. What a
     * GitHub row shows for time, if anything, is whatever this IDE clocked locally. No priority either:
     * issues have none, only GitHub Projects fields do, which this endpoint doesn't reach.
     */
    override fun extras(task: Task): TaskExtras {
        val issue = readJsonObject(endpoint(task) ?: return TaskExtras(), headers())

        val labels = issue.get("labels")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.mapNotNull { label ->
                label.stringOrEmpty("name").takeIf { it.isNotBlank() }
                    ?.let { TaskLabel(it, parseHexColor(label.stringOrEmpty("color"))) }
            }
            .orEmpty()
        return TaskExtras(
            properties = buildList {
                addIfPresent("Assignee", issue.mapArray("assignees") { it.stringOrEmpty("login") }.joinToString(", "))
            },
            labels = labels,
        )
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
        return githubIssueEndpoint(task.issueUrl?.takeIf { it.isNotBlank() } ?: return null)
    }

    private fun requireEndpoint(task: Task): String =
        endpoint(task) ?: error("${task.presentableId} has no usable GitHub issue url")

    private fun token(): String? = (repository as? BaseRepository)?.password?.takeIf { it.isNotBlank() }

    private companion object {
        const val API_VERSION = "2022-11-28"

        /** GitHub's maximum page size. */
        const val COMMENTS_PER_PAGE = 100

        /** A thousand comments; a thread past that is better read on github.com. */
        const val MAX_COMMENT_PAGES = 10

        /** Timeline events, in pages of [COMMENTS_PER_PAGE]; cross-references past these are rare. */
        const val MAX_TIMELINE_PAGES = 3

        /** GitHub's eight reactions, keyed as its API names them, in the order github.com shows them. */
        val REACTION_EMOJI: Map<String, String> = linkedMapOf(
            "+1" to "👍", "-1" to "👎", "laugh" to "😄", "hooray" to "🎉",
            "confused" to "😕", "heart" to "❤️", "rocket" to "🚀", "eyes" to "👀",
        )
    }
}

/** The API endpoint of the issue at [issueUrl] on github.com or a GitHub Enterprise host; null for anything else. */
internal fun githubIssueEndpoint(issueUrl: String): String? {
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
