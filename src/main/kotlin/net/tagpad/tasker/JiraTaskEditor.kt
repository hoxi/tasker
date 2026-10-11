package net.tagpad.tasker

import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Edits Jira issues over REST API v2, and reads back what `JiraTask` keeps to itself.
 *
 * The connector's own `JiraRestTask` does hold the issue type, status and icon url, but it lives in
 * the `intellij.tasks.jira` content module, whose classloader a plugin depending on
 * `com.intellij.tasks` cannot see. So everything here goes over the wire instead — v2 rather than v3
 * because v2 still takes plain strings for summary, description and comment bodies on both Cloud and
 * Server, where v3 insists on Atlassian Document Format.
 */
class JiraTaskEditor(private val repository: TaskRepository) : TaskEditor {

    override val providerName: String get() = "Jira"

    override fun canRename(task: Task): Boolean = issueEndpoint(task) != null

    override fun canEditDescription(task: Task): Boolean = issueEndpoint(task) != null

    override fun canComment(task: Task): Boolean = issueEndpoint(task) != null

    override fun rename(task: Task, summary: String): Unit = updateField(task, "summary", summary)

    override fun setDescription(task: Task, description: String): Unit = updateField(task, "description", description)

    override fun addComment(task: Task, body: String) {
        val payload = JsonObject().apply { addProperty("body", body) }
        HttpRequests.post("${requireEndpoint(task)}/comment", JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /**
     * Read fresh rather than taken from the task: on Cloud the connector searches through API v3, which
     * returns the description as a document tree rather than text. v2 hands back the same field as the
     * wiki markup that [setDescription] writes, so an edit round-trips.
     */
    override fun currentDescription(task: Task): String =
        readJsonObject("${requireEndpoint(task)}?fields=description", headers())
            .objectOrNull("fields")?.stringOrEmpty("description").orEmpty()

    /** People, which are only worth a request once a task is actually looked at. */
    override fun extraProperties(task: Task): List<TaskProperty> {
        val fields = readJsonObject("${issueEndpoint(task) ?: return emptyList()}?fields=assignee,reporter", headers())
            .objectOrNull("fields") ?: return emptyList()

        return buildList {
            addIfPresent("Assignee", fields.objectOrNull("assignee")?.stringOrEmpty("displayName"))
            addIfPresent("Reporter", fields.objectOrNull("reporter")?.stringOrEmpty("displayName"))
        }
    }

    /**
     * Status, issue type and priority for the whole list, a page of keys per request.
     *
     * The status is the workflow's own name — "In Review", "Selected for Development" — which `JiraTask`
     * squeezes into [com.intellij.tasks.TaskState] by numeric id. Ids are per-site on Cloud, so most
     * statuses there come out as OTHER and would be shown as plain "Open".
     */
    override fun decorate(tasks: List<Task>): Map<String, TaskDecoration> {
        val base = apiBase() ?: return emptyMap()
        val keys = tasks.map { it.id }.filter { KEY.matches(it) }.distinct()
        if (keys.isEmpty() || repository.credentialsOrNull() == null) return emptyMap()

        // Cloud retired /search in favour of /search/jql; Server and Data Center only have the former.
        val searchUrl = if (isCloud()) "$base/search/jql" else "$base/search"
        val result = HashMap<String, TaskDecoration>()
        for (page in keys.chunked(PAGE_SIZE)) {
            val jql = URLEncoder.encode("key in (${page.joinToString(",")})", StandardCharsets.UTF_8)
            val url = "$searchUrl?jql=$jql&fields=status,issuetype,priority&maxResults=${page.size}"
            val response = readJsonObject(url, headers())
            val issues = response.get("issues")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (element in issues) {
                val issue = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val key = issue.stringOrEmpty("key").takeIf { it.isNotBlank() } ?: continue
                result[key] = decoration(issue.objectOrNull("fields") ?: continue)
            }
        }
        return result
    }

    private fun decoration(fields: JsonObject): TaskDecoration {
        val type = fields.objectOrNull("issuetype")
        val priority = fields.objectOrNull("priority")
        return TaskDecoration(
            status = fields.objectOrNull("status")?.stringOrEmpty("name")?.takeIf { it.isNotBlank() },
            icon = type?.stringOrEmpty("iconUrl")?.let(::icon),
            properties = buildList {
                addIfPresent("Type", type?.stringOrEmpty("name"))
                addIfPresent("Priority", priority?.stringOrEmpty("name"))
            },
        )
    }

    /**
     * The icon at [url], downloaded once and then served from disk.
     *
     * Cloud serves issue-type icons as SVG, which is exactly what the connector's `ImageIcon(url)`
     * cannot decode. [IconLoader] can, but it picks the decoder by file extension, and these urls have
     * none — so the bytes go to a file named for what the server said they are.
     */
    private fun icon(url: String): Icon? {
        if (url.isBlank()) return null
        ICONS[url]?.let { return it.orElse(null) }

        val icon = try {
            val file = iconFile(url)
            IconLoader.findIcon(file.toUri().toURL())?.takeIf { it.iconWidth > 0 && it.iconHeight > 0 }
        } catch (e: Exception) {
            LOG.debug("Could not load Jira icon $url", e)
            null
        }
        // A miss is remembered too, so a broken icon costs one request per session rather than one per row.
        ICONS[url] = Optional.ofNullable(icon)
        return icon
    }

    private fun iconFile(url: String): Path {
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
        val dir = Path.of(PathManager.getSystemPath(), "tasker", "jira-icons")
        Files.list(dir.also(Files::createDirectories)).use { files ->
            files.filter { it.fileName.toString().startsWith("$name.") }.findFirst().orElse(null)?.let { return it }
        }

        // Same-site urls need our credentials; Atlassian's own CDN would refuse them, so it gets none.
        val headers = if (sameHost(url)) headers() else emptyMap()
        return HttpRequests.request(url).tuner { c -> headers.forEach(c::setRequestProperty) }.connect { request ->
            val contentType = request.connection.contentType.orEmpty()
            val extension = if ("svg" in contentType) "svg" else "png"
            val target = dir.resolve("$name.$extension")
            Files.write(target, request.readBytes(null))
            target
        }
    }

    private fun updateField(task: Task, field: String, value: String) {
        val payload = JsonObject().apply { add("fields", JsonObject().apply { addProperty(field, value) }) }
        HttpRequests.put(requireEndpoint(task), JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /**
     * Mirrors how the connector authenticates. Cloud only accepts email plus API token over Basic;
     * Server and Data Center also take a personal access token as a Bearer, which is what the
     * "Use personal access token" checkbox switches to.
     */
    private fun headers(): Map<String, String> {
        val (username, secret) = repository.credentialsOrNull()
            ?: error("No Jira credentials configured for ${repository.presentableName}")
        val authorization =
            if (!isCloud() && repositoryFlag("isUseBearerTokenAuthentication")) "Bearer $secret"
            else basicAuth(username, secret)
        return mapOf("Authorization" to authorization, "Accept" to JSON_CONTENT_TYPE)
    }

    /** `<server>/rest/api/2/issue/<KEY>`; the task id is the issue key. */
    private fun issueEndpoint(task: Task): String? {
        if (repository.credentialsOrNull() == null) return null
        val key = task.id.takeIf { KEY.matches(it) } ?: return null
        return "${apiBase() ?: return null}/issue/$key"
    }

    private fun requireEndpoint(task: Task): String =
        issueEndpoint(task) ?: error("${task.presentableId} cannot be addressed on ${repository.presentableName}")

    private fun apiBase(): String? = repository.baseUrlOrNull()?.let { "$it/rest/api/2" }

    /**
     * The connector learns this from the server itself on its first request, which the list fetch has
     * always made by the time we get here. The hostname is only a fallback for a repository class that
     * no longer answers.
     */
    private fun isCloud(): Boolean =
        repositoryFlag("isInCloud") || host(repository.url)?.endsWith(".atlassian.net", ignoreCase = true) == true

    /**
     * Reads a boolean getter off `JiraRepository` by name, since its class is out of our classloader's
     * reach. False when it isn't there.
     */
    private fun repositoryFlag(getter: String): Boolean =
        runCatching { repository.javaClass.getMethod(getter).invoke(repository) as? Boolean }.getOrNull() == true

    private fun sameHost(url: String): Boolean {
        val iconHost = host(url) ?: return false
        return iconHost.equals(host(repository.url), ignoreCase = true)
    }

    private fun host(url: String?): String? = runCatching { URI(url.orEmpty()).host }.getOrNull()

    private companion object {
        val LOG = logger<JiraTaskEditor>()

        /** Jira's issue key shape, which is also all that is safe to splice into a url or JQL unquoted. */
        val KEY = Regex("[A-Za-z][A-Za-z0-9_]*-\\d+")

        const val PAGE_SIZE = 50

        /** Shared across instances — [TaskEditors] builds a fresh editor per call. */
        val ICONS = ConcurrentHashMap<String, Optional<Icon>>()
    }
}
