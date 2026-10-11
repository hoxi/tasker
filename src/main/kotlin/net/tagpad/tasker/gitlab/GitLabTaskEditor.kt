package net.tagpad.tasker.gitlab

import com.google.gson.JsonObject
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests
import net.tagpad.tasker.CodeHost
import net.tagpad.tasker.JSON_CONTENT_TYPE
import net.tagpad.tasker.LinkedPullRequest
import net.tagpad.tasker.PullRequestState
import net.tagpad.tasker.TaskEditor
import net.tagpad.tasker.TaskExtras
import net.tagpad.tasker.TaskLabel
import net.tagpad.tasker.addIfPresent
import net.tagpad.tasker.baseUrlOrNull
import net.tagpad.tasker.credentialsOrNull
import net.tagpad.tasker.idOrEmpty
import net.tagpad.tasker.mapArray
import net.tagpad.tasker.objectOrNull
import net.tagpad.tasker.readJsonObject
import net.tagpad.tasker.readJsonObjects
import net.tagpad.tasker.sendJson
import net.tagpad.tasker.stringOrEmpty
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

    /**
     * Assignee and time, from the same issue payload [currentDescription] already reads.
     *
     * Time is the real thing here — what everyone has logged against the issue, not what this IDE
     * happened to observe. GitLab reports it pre-formatted in `human_total_time_spent`, so we show that
     * rather than rounding the seconds ourselves and disagreeing with GitLab's own UI.
     *
     * Labels arrive as bare names here; GitLab only sends their colours from a separate endpoint, so they
     * show uncoloured. Adding milestone or due date later is a line each: they arrive in this same object.
     */
    override fun extras(task: Task): TaskExtras {
        val issue = readJsonObject(endpoint(task) ?: return TaskExtras(), headers())
        val stats = issue.objectOrNull("time_stats")
        val labels = issue.get("labels")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank) }
            ?.map { TaskLabel(it) }
            .orEmpty()

        return TaskExtras(labels = labels, properties = buildList {
            /*
             * `assignees` is the current field; `assignee` is its long-deprecated singular predecessor,
             * still all that self-managed instances on older versions return.
             */
            val assignees = issue.mapArray("assignees") { it.stringOrEmpty("name") }
                .ifEmpty { listOfNotNull(issue.objectOrNull("assignee")?.stringOrEmpty("name")) }
            addIfPresent("Assignee", assignees.filter { it.isNotBlank() }.joinToString(", "))

            addIfPresent("Time spent", stats?.stringOrEmpty("human_total_time_spent"))
            addIfPresent("Estimate", stats?.stringOrEmpty("human_time_estimate"))
        })
    }

    /**
     * Merge requests that will close the issue (`closed_by`) and those that merely mention it
     * (`related_merge_requests`), the first group first. Either list failing on its own — an older
     * self-managed instance, say — just leaves its merge requests out.
     */
    override fun pullRequests(task: Task): List<LinkedPullRequest> {
        val endpoint = endpoint(task) ?: return emptyList()
        // The issue's project as a web path, e.g. https://host/group/project, to tell local merge requests from others.
        val issueProject = task.issueUrl?.substringBefore("/-/issues/")?.substringBefore("/issues/")

        val found = LinkedHashMap<String, LinkedPullRequest>()
        for (path in listOf("closed_by", "related_merge_requests")) {
            val mergeRequests = runCatching { readJsonObjects("$endpoint/$path", headers()) }.getOrNull() ?: continue
            for (mr in mergeRequests) {
                val url = mr.stringOrEmpty("web_url").takeIf { it.isNotBlank() } ?: continue
                if (url in found) continue
                val references = mr.objectOrNull("references")
                val local = issueProject != null && url.startsWith("$issueProject/")
                val reference = (if (local) references?.stringOrEmpty("short") else references?.stringOrEmpty("full"))
                    ?.takeIf { it.isNotBlank() } ?: "!${mr.idOrEmpty("iid")}"
                val draft = listOf("draft", "work_in_progress")
                    .any { mr.get(it)?.takeIf { e -> e.isJsonPrimitive }?.asBoolean == true }
                val state = when (mr.stringOrEmpty("state")) {
                    "merged" -> PullRequestState.MERGED
                    "closed", "locked" -> PullRequestState.CLOSED
                    else -> if (draft) PullRequestState.DRAFT else PullRequestState.OPEN
                }
                found[url] = LinkedPullRequest(CodeHost.GITLAB, reference, mr.stringOrEmpty("title"), url, state)
            }
        }
        return found.values.toList()
    }

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
        return gitLabIssueEndpoint(base, task.issueUrl?.takeIf { it.isNotBlank() } ?: return null)
    }

    private fun requireEndpoint(task: Task): String =
        endpoint(task) ?: error("${task.presentableId} has no usable GitLab issue url")
}

private val ISSUE_URL = Regex("""https?://[^/]+/(.+?)(?:/-)?/issues/(\d+)""")

/** The API endpoint, under the server root [base], of the issue at the web url [issueUrl]; null if it isn't one. */
internal fun gitLabIssueEndpoint(base: String, issueUrl: String): String? {
    val match = ISSUE_URL.matchEntire(issueUrl) ?: return null
    val project = URLEncoder.encode(match.groupValues[1], StandardCharsets.UTF_8)
    return "$base/api/v4/projects/$project/issues/${match.groupValues[2]}"
}
