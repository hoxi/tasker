package net.tagpad.tasker

import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import net.tagpad.tasker.github.GitHubTaskEditor
import net.tagpad.tasker.gitlab.GitLabTaskEditor
import net.tagpad.tasker.jira.JiraTaskEditor
import net.tagpad.tasker.youtrack.YouTrackTaskEditor
import java.awt.Color
import javax.swing.Icon

/** A label and its value, as the details pane shows it. */
data class TaskProperty(val label: String, val value: String)

/**
 * How urgent a task is, as the tracker names it. [icon] is the tracker's own artwork for it where it
 * has some (Jira's arrows), or a swatch of the tracker's colour for it (YouTrack's); otherwise none.
 * [rank] is its place among the tracker's priorities, lower being more urgent, where the tracker orders
 * them; it is what sorting by priority goes by.
 */
data class TaskPriority(val name: String, val icon: Icon? = null, val rank: Int? = null)

/**
 * Someone a task is assigned to, by the name the tracker shows. [isMe] marks whoever the server's
 * credentials belong to, which only the tracker's adapter can tell.
 */
data class TaskAssignee(val name: String, val isMe: Boolean = false)

/**
 * A tag or label on a task. [background] is the tracker's colour for it, when it assigns one; the
 * pane picks a readable [foreground] itself when the tracker doesn't say.
 */
data class TaskLabel(val name: String, val background: Color? = null, val foreground: Color? = null)

/** Where a linked pull or merge request lives, which decides its icon and its "!12" or "#12" reference style. */
enum class CodeHost { GITHUB, GITLAB, BITBUCKET, OTHER }

/** A pull or merge request's state, normalised across code hosts. */
enum class PullRequestState { OPEN, DRAFT, MERGED, CLOSED }

/**
 * A pull or merge request linked to a task. [reference] is how its host writes it — `#45`, `!12`, or
 * with the repository in front when that differs from the task's. [state] is null when the tracker
 * doesn't say.
 */
data class LinkedPullRequest(
    val host: CodeHost,
    val reference: String,
    val title: String,
    val url: String,
    val state: PullRequestState? = null,
)

/**
 * Which part of the hierarchy a linked issue is, which decides the group it is shown under and in what order:
 * the epic and the parent above the task, its sub-tasks below it, then every other kind of link.
 */
enum class LinkKind { EPIC, PARENT, SUBTASK, OTHER }

/**
 * An issue linked to a task. [relation] is the tracker's own wording for an [LinkKind.OTHER] link, read from
 * the task's side — "blocks", "is blocked by", "relates to"; the other kinds are named by their kind.
 * [status] is the linked issue's own, null when the tracker doesn't say; [resolved] puts it after the open
 * ones. [url] opens it in the browser when it isn't in the list.
 */
data class LinkedIssue(
    val kind: LinkKind,
    val id: String,
    val summary: String,
    val relation: String = "",
    val status: String? = null,
    val resolved: Boolean = false,
    val url: String? = null,
    val icon: Icon? = null,
)

/** What [TaskEditor.extras] read for one task: fields for the meta row, its priority, its labels. */
data class TaskExtras(
    val properties: List<TaskProperty> = emptyList(),
    val priority: TaskPriority? = null,
    val labels: List<TaskLabel> = emptyList(),
) {
    fun isEmpty(): Boolean = properties.isEmpty() && priority == null && labels.isEmpty()
}

/**
 * What a row shows that [Task] gets wrong or leaves out, resolved for a whole list at once.
 *
 * [status] replaces [statusText] when set, [icon] replaces [Task.getIcon], and [properties] join the
 * details pane's meta row ahead of anything [TaskEditor.extras] fetches later. [assignees] and [priority]
 * are what the list is sorted by; null [assignees] means the adapter didn't say, as opposed to nobody.
 * [links] are the task's linked issues — epic, parent, sub-tasks and the rest — read with the list so that
 * selecting a task costs no request for them.
 */
data class TaskDecoration(
    val status: String? = null,
    val icon: Icon? = null,
    val properties: List<TaskProperty> = emptyList(),
    val assignees: List<TaskAssignee>? = null,
    val priority: TaskPriority? = null,
    val links: List<LinkedIssue> = emptyList(),
)

/**
 * What a task server lets us change, and what it can tell us that [Task] has no room for.
 *
 * This is a plugin-side abstraction rather than a platform one by necessity: [TaskRepository] can only
 * set a task's state and log time against it. It has no notion of editing a summary or a description,
 * and none of posting a comment — there is no capability flag for either, so no provider implements
 * them. Anything past those two writes has to go to the tracker's own REST API, which means one
 * adapter per provider.
 *
 * A provider with no adapter simply isn't editable, and the details pane greys its actions out.
 */
interface TaskEditor {

    /** Names the provider in "not supported" tooltips. */
    val providerName: String

    fun canRename(task: Task): Boolean

    fun canEditDescription(task: Task): Boolean

    fun canComment(task: Task): Boolean

    @Throws(Exception::class)
    fun rename(task: Task, summary: String)

    @Throws(Exception::class)
    fun setDescription(task: Task, description: String)

    @Throws(Exception::class)
    fun addComment(task: Task, body: String)

    /**
     * The description as the server holds it, for prefilling an edit. May hit the network, so call it
     * off the EDT.
     *
     * Defaults to whatever the task already carries. A provider whose [Task] omits the description has
     * to override this and fetch it — otherwise the dialog opens blank and saving wipes the real text.
     */
    @Throws(Exception::class)
    fun currentDescription(task: Task): String = task.description.orEmpty()

    /**
     * What the tracker holds that [Task] has nowhere to put — assignee, time spent, priority, labels.
     *
     * [Task] stops at id, summary, description, dates and state. Everything else a tracker knows about
     * an issue is either dropped at that boundary or funnelled through [Task.getCustomProperties], which
     * of the bundled providers only YouTrack fills in. So for the rest we read it back ourselves, off
     * the endpoint this adapter already knows how to address and authenticate — in one request, since
     * the pane asks for all of it at once when a task is selected.
     *
     * Properties are shown in the order returned. Hits the network, so call it off the EDT. Returning
     * nothing is the honest answer for a tracker that exposes nothing extra.
     */
    @Throws(Exception::class)
    fun extras(task: Task): TaskExtras = TaskExtras()

    /**
     * Pull and merge requests linked to the task, in the order the tracker lists them. A request of its
     * own, rather than part of [extras], because for most trackers it is a different endpoint — and for
     * Jira an unofficial one, which mustn't take the documented fields down with it when it fails.
     *
     * Hits the network, so call it off the EDT.
     */
    @Throws(Exception::class)
    fun pullRequests(task: Task): List<LinkedPullRequest> = emptyList()

    /**
     * The task's comments, for a provider whose [Task.getComments] doesn't carry them.
     *
     * Null, the default, means the task's own are the real ones. Hits the network, so call it off the EDT.
     */
    @Throws(Exception::class)
    fun comments(task: Task): List<Comment>? = null

    /**
     * Whether [comments] is overridden, so the pane knows to call it even for a task that already
     * carries comments — Jira's do, unrendered and without avatars. Answerable without the network.
     */
    val suppliesComments: Boolean get() = false

    /**
     * Whether [comment] can be rewritten by the current user, which decides whether it gets a pen.
     * Answerable without the network: whose comment it is was settled when it was read.
     */
    fun canEditComment(task: Task, comment: Comment): Boolean = false

    /** Replaces [comment]'s text with [body], in the same markup [EditableComment.source] was read in. */
    @Throws(Exception::class)
    fun editComment(task: Task, comment: Comment, body: String): Unit =
        throw UnsupportedOperationException("$providerName comments can't be edited")

    /**
     * [TaskDecoration]s for a freshly fetched list, keyed by [Task.getId].
     *
     * Unlike [extras], this feeds the list itself — the Status column and the row icon — so it
     * runs once per refresh for every row, and should batch rather than ask per task. Tasks missing
     * from the result just render as [Task] describes them. Hits the network, so call it off the EDT.
     */
    @Throws(Exception::class)
    fun decorate(tasks: List<Task>): Map<String, TaskDecoration> = emptyMap()
}

/**
 * A comment that knows its place in a thread, so the details pane can show replies under what they
 * answer. [com.intellij.tasks.Comment] has no notion of threads, so only comments an adapter builds
 * itself can carry one; anything else renders as a top-level comment.
 */
interface ThreadedComment {

    /** Unique among the task's comments. */
    val commentId: String

    /** The [commentId] this one replies to, or null for a top-level comment. */
    val parentId: String?
}

/**
 * One emoji's tally on a comment.
 *
 * [emoji] is what gets displayed: the emoji itself as Unicode text, or — for a tracker's custom emoji
 * that has no Unicode form — its short name, e.g. `:party-parrot:`. [reactedByMe] marks the ones the
 * current user added, which the pane highlights.
 */
data class CommentReaction(val emoji: String, val count: Int, val reactedByMe: Boolean = false)

/**
 * A comment that carries reactions. Like [ThreadedComment], only comments an adapter builds itself can
 * have them; the pane shows them in the order given, below the comment's text.
 */
interface ReactableComment {
    val reactions: List<CommentReaction>
}

/**
 * A comment that can be rewritten, from an adapter that read it with enough to do so.
 *
 * [source] is the text as the tracker stores it — markdown, or Jira's wiki markup — rather than the
 * rendered HTML the pane shows, since that is what an edit has to start from and send back.
 */
interface EditableComment {

    /** Unique among the task's comments. */
    val commentId: String

    /** Whether the current user wrote it; only those are offered for editing. */
    val isMine: Boolean

    val source: String
}

/** Resolves the adapter for a server, or null when nothing on it can be edited. */
object TaskEditors {

    fun forRepository(repository: TaskRepository): TaskEditor? =
        when (repository.repositoryType?.name) {
            GITHUB -> GitHubTaskEditor(repository)
            GITLAB -> GitLabTaskEditor(repository)
            YOUTRACK -> YouTrackTaskEditor(repository)
            JIRA -> JiraTaskEditor(repository)
            else -> null
        }

    /*
     * Repository types are package-private, so they can't be referenced directly — but each getName()
     * returns a literal rather than a localized bundle key, which makes matching on the string safe. A
     * rename on JetBrains' side greys the actions out; it can't misfire.
     */
    private const val GITHUB = "GitHub"

    /** Lowercase "l" is not a typo: `GitlabRepositoryType.getName()` returns "Gitlab". */
    private const val GITLAB = "Gitlab"

    private const val YOUTRACK = "YouTrack"

    /** All caps, as `JiraRepositoryType.getName()` spells it. */
    private const val JIRA = "JIRA"
}
