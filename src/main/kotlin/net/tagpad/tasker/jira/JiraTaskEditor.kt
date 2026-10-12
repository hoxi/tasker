package net.tagpad.tasker.jira

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.util.io.HttpRequests
import net.tagpad.tasker.CodeHost
import net.tagpad.tasker.EditableComment
import net.tagpad.tasker.JSON_CONTENT_TYPE
import net.tagpad.tasker.LinkKind
import net.tagpad.tasker.LinkedIssue
import net.tagpad.tasker.LinkedPullRequest
import net.tagpad.tasker.PullRequestState
import net.tagpad.tasker.TaskDecoration
import net.tagpad.tasker.TaskDetails
import net.tagpad.tasker.TaskAssignee
import net.tagpad.tasker.TaskEditor
import net.tagpad.tasker.TaskEditors
import net.tagpad.tasker.TaskExtras
import net.tagpad.tasker.TaskLabel
import net.tagpad.tasker.TaskPriority
import net.tagpad.tasker.ThreadedComment
import net.tagpad.tasker.addIfPresent
import net.tagpad.tasker.attempt
import net.tagpad.tasker.baseUrlOrNull
import net.tagpad.tasker.basicAuth
import net.tagpad.tasker.credentialsOrNull
import net.tagpad.tasker.currentUserId
import net.tagpad.tasker.idOrEmpty
import net.tagpad.tasker.objectOrNull
import net.tagpad.tasker.readJsonObject
import net.tagpad.tasker.readJsonObjects
import net.tagpad.tasker.sendJson
import net.tagpad.tasker.stringOrEmpty
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
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
     * Read fresh rather than taken from the task, so the editor opens on what the server holds now rather
     * than on a list fetch that may be minutes old — and from the same v2 endpoint [setDescription]
     * writes to, so the wiki markup round-trips.
     */
    override fun currentDescription(task: Task): String =
        readJsonObject("${requireEndpoint(task)}?fields=description", headers())
            .objectOrNull("fields")?.stringOrEmpty("description").orEmpty()

    override val suppliesComments: Boolean get() = true

    /**
     * Read here rather than taken from the task, for two things the connector drops. Its comments carry
     * the raw wiki markup, where the pane wants HTML — `renderedBody` is Jira's own rendering of it. And
     * its author model keeps only names, so there is no avatar to show.
     */
    override fun comments(task: Task): List<Comment> {
        val response = readJsonObject(
            "${requireEndpoint(task)}/comment?expand=renderedBody&maxResults=$MAX_COMMENTS",
            headers(),
        )
        val me = currentUser()
        return response.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let { json -> JiraComment(json, me) } }
            .orEmpty()
    }

    override val readsDetailsAtOnce: Boolean get() = true

    /**
     * [extras] and the comments in one request: the issue with just those fields, and Jira's own HTML
     * rendering of them alongside. The comments embedded in an issue can stop short of the whole thread on
     * a long one, and a server may leave the rendering out; either way they are read from the comment
     * endpoint instead, as [comments] does.
     */
    override fun details(task: Task): TaskDetails {
        val url = "${requireEndpoint(task)}?fields=$EXTRA_FIELDS,comment&expand=renderedFields"
        val issue = readJsonObject(url, headers())
        rememberIssueId(issue)
        val fields = issue.objectOrNull("fields") ?: JsonObject()
        return TaskDetails(
            extras = attempt { extrasFrom(fields) },
            comments = attempt { embeddedComments(issue) ?: comments(task) },
        )
    }

    /** The comments [issue] carries, with their rendering; null when that isn't all of them, or isn't rendered. */
    private fun embeddedComments(issue: JsonObject): List<Comment>? {
        val held = issue.objectOrNull("fields")?.objectOrNull("comment") ?: return null
        val raw = held.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: return null
        val total = held.get("total")?.asIntOrNull() ?: raw.size
        if (raw.size < total) return null
        val rendered = issue.objectOrNull("renderedFields")?.objectOrNull("comment")?.get("comments")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.associate { it.idOrEmpty("id") to it.stringOrEmpty("body") }
            ?: return null
        val me = currentUser()
        return raw.map { comment ->
            val html = rendered[comment.idOrEmpty("id")].orEmpty()
            JiraComment(comment.deepCopy().apply { addProperty("renderedBody", html) }, me)
        }
    }

    override fun canEditComment(task: Task, comment: Comment): Boolean =
        (comment as? JiraComment)?.isMine == true && issueEndpoint(task) != null

    override fun editComment(task: Task, comment: Comment, body: String) {
        val id = (comment as? JiraComment)?.commentId?.takeIf { it.isNotBlank() }
            ?: error("This comment can't be addressed on ${repository.presentableName}")
        val payload = JsonObject().apply { addProperty("body", body) }
        HttpRequests.put("${requireEndpoint(task)}/comment/$id", JSON_CONTENT_TYPE).sendJson(payload, headers())
    }

    /** The current user, identified the way comment authors are — see [userId]. */
    private fun currentUser(): String? = repository.currentUserId {
        userId(readJsonObject("${apiBase() ?: return@currentUserId null}/myself", headers()))
    }

    /**
     * One comment, with its place in the thread when Jira gives one away.
     *
     * Jira Cloud shows replies in its web UI, but Atlassian shipped them without any API change: the
     * documented comment endpoints return a flat list, in creation order, with no parent link. The
     * reply lookup below covers the field names an API that did expose it would plausibly use, so it
     * starts working without a Tasker release should Atlassian add one. Until then every Jira comment
     * is top-level.
     */
    private class JiraComment(json: JsonObject, me: String?) : Comment(), ThreadedComment, EditableComment {
        override val commentId: String = json.idOrEmpty("id")
        override val parentId: String? =
            (json.idOrEmpty("parentId").ifBlank { json.objectOrNull("parent")?.idOrEmpty("id").orEmpty() })
                .takeIf { it.isNotBlank() }

        private val author = json.objectOrNull("author")

        override val isMine: Boolean = me != null && author?.let(::userId) == me

        /** Wiki markup, which is what v2 takes back on an edit. */
        override val source: String = json.stringOrEmpty("body")
        private val html = json.stringOrEmpty("renderedBody").ifBlank {
            StringUtil.escapeXmlEntities(json.stringOrEmpty("body")).replace("\n", "<br>")
        }
        private val created = parseDate(json.stringOrEmpty("created"))

        /** The largest of the four sizes Jira offers, so it stays sharp when the pane scales it down. */
        private val avatar = author?.objectOrNull("avatarUrls")?.stringOrEmpty("48x48")?.takeIf { it.isNotBlank() }

        override fun getText(): String = html

        override fun getAuthor(): String? = author?.stringOrEmpty("displayName")?.takeIf { it.isNotBlank() }

        override fun getDate(): Date? = created

        /** Emits the avatar ahead of the body, which is where the details pane looks for one. */
        override fun appendTo(builder: StringBuilder) {
            if (avatar != null) builder.append("<img src='").append(StringUtil.escapeXmlEntities(avatar)).append("'>")
            builder.append(html)
        }
    }

    /**
     * Pull and merge requests from the issue's Development panel, whichever code host integration fills
     * it — the GitLab for Jira app, GitHub for Jira, Bitbucket.
     *
     * The panel's data is only reachable through `/rest/dev-status`, which Atlassian doesn't document or
     * support; the shapes read here are those community examples show. So everything is read leniently,
     * and any failure is the panel's to swallow — it costs the section, never the rest of the pane.
     *
     * The summary comes first because the detail call needs an application type per code host, and the
     * summary says which ones have anything linked.
     */
    override fun pullRequests(task: Task): List<LinkedPullRequest> {
        val endpoint = issueEndpoint(task) ?: return emptyList()
        val site = repository.baseUrlOrNull() ?: return emptyList()
        // dev-status wants the numeric issue id, not the key; the list fetch has usually learnt it already.
        val issueId = ISSUE_IDS[endpoint]
            ?: readJsonObject("$endpoint?fields=summary", headers()).also(::rememberIssueId).idOrEmpty("id")
                .takeIf { it.isNotBlank() } ?: return emptyList()

        val summary = readJsonObject("$site/rest/dev-status/latest/issue/summary?issueId=$issueId", headers())
        val byType = summary.objectOrNull("summary")?.objectOrNull("pullrequest")?.objectOrNull("byInstanceType")
            ?: return emptyList()
        val types = byType.entrySet()
            .filter { (_, value) -> value.isJsonObject && (value.asJsonObject.get("count")?.asIntOrNull() ?: 0) > 0 }
            .map { it.key }

        val found = LinkedHashMap<String, LinkedPullRequest>()
        for (type in types) {
            val host = jiraCodeHost(type)
            val appType = URLEncoder.encode(type, StandardCharsets.UTF_8)
            val query = "issueId=$issueId&applicationType=$appType&dataType=pullrequest"
            val detail = readJsonObject("$site/rest/dev-status/latest/issue/detail?$query", headers())
            val entries = detail.get("detail")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (entry in entries) {
                val requests = entry.takeIf { it.isJsonObject }?.asJsonObject?.get("pullRequests")
                    ?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                for (element in requests) {
                    val pr = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val url = pr.stringOrEmpty("url").takeIf { it.isNotBlank() } ?: continue
                    found[url] = LinkedPullRequest(
                        host = host,
                        reference = jiraPullRequestReference(pr.idOrEmpty("id"), host),
                        title = pr.stringOrEmpty("name"),
                        url = url,
                        state = when (pr.stringOrEmpty("status").uppercase()) {
                            "OPEN" -> PullRequestState.OPEN
                            "MERGED" -> PullRequestState.MERGED
                            "DECLINED", "CLOSED" -> PullRequestState.CLOSED
                            "DRAFT" -> PullRequestState.DRAFT
                            else -> null
                        },
                    )
                }
            }
        }
        return found.values.toList()
    }

    /**
     * People, priority and labels, which are only worth a request once a task is actually looked at.
     *
     * The priority icon is Jira's own artwork for that level, through the same cache as issue-type icons.
     * Labels are bare strings in Jira, with no colour of their own.
     */
    override fun extras(task: Task): TaskExtras {
        val endpoint = issueEndpoint(task) ?: return TaskExtras()
        val fields = readJsonObject("$endpoint?fields=$EXTRA_FIELDS", headers())
            .objectOrNull("fields") ?: return TaskExtras()
        return extrasFrom(fields)
    }

    private fun extrasFrom(fields: JsonObject): TaskExtras {
        val priority = fields.objectOrNull("priority")?.let { p ->
            p.stringOrEmpty("name").takeIf { it.isNotBlank() }
                ?.let { TaskPriority(it, icon(p.stringOrEmpty("iconUrl"))) }
        }
        val labels = fields.get("labels")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank) }
            ?.map { TaskLabel(it) }
            .orEmpty()
        return TaskExtras(
            properties = buildList {
                addIfPresent("Assignee", fields.objectOrNull("assignee")?.stringOrEmpty("displayName"))
                addIfPresent("Reporter", fields.objectOrNull("reporter")?.stringOrEmpty("displayName"))
            },
            priority = priority,
            labels = labels,
        )
    }

    /**
     * Status, issue type, assignee, priority and linked issues for the whole list, a page of keys per request.
     *
     * The status is the workflow's own name — "In Review", "Selected for Development" — which `JiraTask`
     * squeezes into [com.intellij.tasks.TaskState] by numeric id. Ids are per-site on Cloud, so most
     * statuses there come out as OTHER and would be shown as plain "Open". Assignee and priority ride
     * along in the same request, for sorting the list by them, and so do the links, so that selecting a
     * task costs no request for them.
     *
     * The epic comes from `parent` on Cloud. Server and Data Center keep it in the "Epic Link" custom field
     * instead, which holds only the epic's key: epics not in the list themselves are looked up afterwards,
     * in one search per page of them.
     */
    override fun decorate(tasks: List<Task>): Map<String, TaskDecoration> {
        val base = apiBase() ?: return emptyMap()
        val keys = tasks.map { it.id }.filter { KEY.matches(it) }.distinct()
        if (keys.isEmpty() || repository.credentialsOrNull() == null) return emptyMap()

        val me = currentUser()
        val ranks = priorityRanks(base)
        val epicField = if (isCloud()) null else epicLinkField(base)
        val fields = "summary,status,issuetype,assignee,priority,parent,subtasks,issuelinks" +
            (epicField?.let { ",$it" } ?: "")
        val found = HashMap<String, JsonObject>()
        for (page in keys.chunked(PAGE_SIZE)) {
            for (issue in search(base, "key in (${page.joinToString(",")})", fields, page.size)) {
                val key = issue.stringOrEmpty("key").takeIf { it.isNotBlank() } ?: continue
                found[key] = issue
                rememberIssueId(issue)
            }
        }

        val epicKeys = found.mapValues { (_, issue) ->
            epicField?.let { issue.objectOrNull("fields")?.stringOrEmpty(it) }?.takeIf { KEY.matches(it) }
        }
        val epics = epicIssues(base, epicKeys.values.filterNotNull().toSet(), found)
        return found.mapValues { (key, issue) ->
            val epic = epicKeys[key]?.let { epics[it] }
            decoration(issue.objectOrNull("fields") ?: JsonObject(), me, ranks, listOfNotNull(epic))
        }
    }

    /** The issues [jql] finds, with [fields]. Cloud retired `/search` for `/search/jql`; the others only have it. */
    private fun search(base: String, jql: String, fields: String, max: Int): List<JsonObject> {
        val searchUrl = if (isCloud()) "$base/search/jql" else "$base/search"
        val query = URLEncoder.encode(jql, StandardCharsets.UTF_8)
        val response = readJsonObject("$searchUrl?jql=$query&fields=$fields&maxResults=$max", headers())
        return response.get("issues")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .orEmpty()
    }

    /**
     * The epics named by [keys], as links: from [known] when the epic is in the list itself, otherwise read
     * in one search per page of them. An epic that can't be read is left out, rather than failing the list's
     * decorations with it.
     */
    private fun epicIssues(base: String, keys: Set<String>, known: Map<String, JsonObject>): Map<String, LinkedIssue> {
        val result = HashMap<String, LinkedIssue>()
        keys.forEach { key -> known[key]?.let { result[key] = linkedIssue(LinkKind.EPIC, it) } }
        for (page in (keys - result.keys).chunked(PAGE_SIZE)) {
            val issues = runCatching { search(base, "key in (${page.joinToString(",")})", LINKED_FIELDS, page.size) }
                .onFailure { LOG.info("Could not read epics from ${repository.presentableName}", it) }
                .getOrDefault(emptyList())
            for (issue in issues) result[issue.stringOrEmpty("key")] = linkedIssue(LinkKind.EPIC, issue)
        }
        return result
    }

    /**
     * The id of the site's "Epic Link" field — `customfield_10008`, or whatever this site gave it — found by
     * the Jira Software type it was created with rather than by its name, which sites translate and rename.
     * Read once per site and session; null on a site without Jira Software, or when it can't be read.
     */
    private fun epicLinkField(base: String): String? = EPIC_LINK_FIELDS.getOrPut(base) {
        val id = runCatching {
            readJsonObjects("$base/field", headers())
                .firstOrNull { it.objectOrNull("schema")?.stringOrEmpty("custom") == EPIC_LINK_TYPE }
                ?.idOrEmpty("id")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        Optional.ofNullable(id)
    }.orElse(null)

    /**
     * Everything linked to the issue whose [fields] these are: any [epics] found for it elsewhere, its parent
     * — the epic, on Cloud, when the parent's type sits a level above standard issues — its sub-tasks, and its
     * issue links, each in the wording of its direction as seen from this issue.
     */
    private fun links(fields: JsonObject, epics: List<LinkedIssue>): List<LinkedIssue> = buildList {
        addAll(epics)
        fields.objectOrNull("parent")?.let { parent ->
            val type = parent.objectOrNull("fields")?.objectOrNull("issuetype")
            val level = type?.get("hierarchyLevel")?.asIntOrNull() ?: 0
            val isEpic = level > 0 || type?.stringOrEmpty("name").equals("Epic", ignoreCase = true)
            add(linkedIssue(if (isEpic) LinkKind.EPIC else LinkKind.PARENT, parent))
        }
        fields.get("subtasks")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.forEach { add(linkedIssue(LinkKind.SUBTASK, it)) }
        fields.get("issuelinks")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.forEach { link ->
                val type = link.objectOrNull("type")
                val outward = link.objectOrNull("outwardIssue")
                val other = outward ?: link.objectOrNull("inwardIssue") ?: return@forEach
                val wording = type?.stringOrEmpty(if (outward != null) "outward" else "inward")
                    ?.ifBlank { type.stringOrEmpty("name") }.orEmpty()
                add(linkedIssue(LinkKind.OTHER, other, wording))
            }
    }

    /** A linked issue as Jira nests one: its key, and a `fields` object with summary, status and type. */
    private fun linkedIssue(kind: LinkKind, issue: JsonObject, relation: String = ""): LinkedIssue {
        val key = issue.stringOrEmpty("key")
        val fields = issue.objectOrNull("fields")
        val status = fields?.objectOrNull("status")
        return LinkedIssue(
            kind = kind,
            id = key,
            summary = fields?.stringOrEmpty("summary").orEmpty(),
            relation = relation,
            status = status?.stringOrEmpty("name")?.takeIf { it.isNotBlank() },
            resolved = status?.objectOrNull("statusCategory")?.stringOrEmpty("key") == "done",
            url = repository.baseUrlOrNull()?.let { "$it/browse/$key" },
            icon = fields?.objectOrNull("issuetype")?.stringOrEmpty("iconUrl")?.let(::icon),
        )
    }

    private fun decoration(
        fields: JsonObject,
        me: String?,
        ranks: Map<String, Int>,
        epics: List<LinkedIssue>,
    ): TaskDecoration {
        val type = fields.objectOrNull("issuetype")
        val assignee = fields.objectOrNull("assignee")
        val priority = fields.objectOrNull("priority")
        return TaskDecoration(
            status = fields.objectOrNull("status")?.stringOrEmpty("name")?.takeIf { it.isNotBlank() },
            icon = type?.stringOrEmpty("iconUrl")?.let(::icon),
            properties = buildList { addIfPresent("Type", type?.stringOrEmpty("name")) },
            assignees = listOfNotNull(
                assignee?.stringOrEmpty("displayName")?.takeIf { it.isNotBlank() }
                    ?.let { TaskAssignee(it, isMe = me != null && userId(assignee) == me) },
            ),
            priority = priority?.stringOrEmpty("name")?.takeIf { it.isNotBlank() }?.let { name ->
                TaskPriority(name, icon(priority.stringOrEmpty("iconUrl")), ranks[priority.idOrEmpty("id")])
            },
            links = links(fields, epics),
        )
    }

    /**
     * Each priority's place in the site's list, most urgent first, by priority id. Jira returns its
     * priorities in their configured order, which is the order its own boards sort by; the names alone say
     * nothing about it, and sites rename and add levels freely. Read once per site and session; empty when
     * it can't be read, which leaves priorities unsortable rather than the list unloadable.
     */
    private fun priorityRanks(base: String): Map<String, Int> = PRIORITY_RANKS.getOrPut(base) {
        runCatching {
            readJsonObjects("$base/priority", headers())
                .mapIndexedNotNull { index, p -> p.idOrEmpty("id").takeIf { it.isNotBlank() }?.let { it to index } }
                .toMap()
        }.getOrElse { return emptyMap() }
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

    /** Keeps [issue]'s numeric id for [pullRequests], keyed by its endpoint, which names the site and the key. */
    private fun rememberIssueId(issue: JsonObject) {
        val id = issue.idOrEmpty("id").takeIf { it.isNotBlank() } ?: return
        val key = issue.stringOrEmpty("key").takeIf { KEY.matches(it) } ?: return
        apiBase()?.let { ISSUE_IDS["$it/issue/$key"] = id }
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

        /** Comments per issue. One page, and a generous one: a thread longer than this is rare. */
        const val MAX_COMMENTS = 1000

        /** Jira's timestamp shape, e.g. `2026-10-11T14:03:12.345+0200`. */
        val JIRA_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

        fun JsonElement.asIntOrNull(): Int? =
            takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

        /**
         * A user's identity: `accountId` on Cloud, where it is all a user object is guaranteed to carry,
         * and `key` or `name` on Server and Data Center, which predate account ids.
         */
        fun userId(user: JsonObject): String? =
            listOf("accountId", "key", "name")
                .firstNotNullOfOrNull { user.stringOrEmpty(it).takeIf(String::isNotBlank) }

        fun parseDate(text: String): Date? =
            runCatching { Date.from(OffsetDateTime.parse(text, JIRA_TIMESTAMP).toInstant()) }.getOrNull()

        /** Shared across instances — [TaskEditors] builds a fresh editor per call. */
        val ICONS = ConcurrentHashMap<String, Optional<Icon>>()

        /** Priority order per site, keyed by API base; see [priorityRanks]. */
        val PRIORITY_RANKS = ConcurrentHashMap<String, Map<String, Int>>()

        /** The "Epic Link" field's id per site, keyed by API base; see [epicLinkField]. */
        val EPIC_LINK_FIELDS = ConcurrentHashMap<String, Optional<String>>()

        /** The custom field type Jira Software creates "Epic Link" with, on Server and Data Center. */
        const val EPIC_LINK_TYPE = "com.pyxis.greenhopper.jira:gh-epic-link"

        /** What [extras] reads. */
        const val EXTRA_FIELDS = "assignee,reporter,priority,labels"

        /**
         * Each issue's numeric id by its REST endpoint, as the list fetch and the detail reads come across
         * them, so [pullRequests] needn't ask. Ids never change, so nothing here goes stale.
         */
        val ISSUE_IDS = ConcurrentHashMap<String, String>()

        /** What a linked issue is shown with. */
        const val LINKED_FIELDS = "summary,status,issuetype"
    }
}

/**
 * The code host behind a dev-status application type. "stash" is Bitbucket's old name, which the
 * API kept; "githube" is GitHub Enterprise.
 */
internal fun jiraCodeHost(applicationType: String): CodeHost {
    val type = applicationType.lowercase()
    return when {
        "gitlab" in type -> CodeHost.GITLAB
        "github" in type -> CodeHost.GITHUB
        "bitbucket" in type || "stash" in type -> CodeHost.BITBUCKET
        else -> CodeHost.OTHER
    }
}

/** dev-status ids usually arrive as `!12` or `#12` already; a bare number gets its host's prefix. */
internal fun jiraPullRequestReference(id: String, host: CodeHost): String = when {
    id.isBlank() -> ""
    id.first() in "!#" -> id
    host == CodeHost.GITLAB -> "!$id"
    else -> "#$id"
}
