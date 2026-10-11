package net.tagpad.tasker

import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import javax.swing.Icon

/** A label and its value, as the details pane shows it. */
data class TaskProperty(val label: String, val value: String)

/**
 * What a row shows that [Task] gets wrong or leaves out, resolved for a whole list at once.
 *
 * [status] replaces [statusText] when set, [icon] replaces [Task.getIcon], and [properties] join the
 * details pane's meta row ahead of anything [TaskEditor.extraProperties] fetches later.
 */
data class TaskDecoration(
    val status: String? = null,
    val icon: Icon? = null,
    val properties: List<TaskProperty> = emptyList(),
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
     * Fields the tracker holds that [Task] has nowhere to put — assignee, time spent and the like.
     *
     * [Task] stops at id, summary, description, dates and state. Everything else a tracker knows about
     * an issue is either dropped at that boundary or funnelled through [Task.getCustomProperties], which
     * of the bundled providers only YouTrack fills in. So for the rest we read it back ourselves, off
     * the endpoint this adapter already knows how to address and authenticate.
     *
     * Shown in the order returned. Hits the network, so call it off the EDT. Returning nothing is the
     * honest answer for a tracker that exposes nothing extra.
     */
    @Throws(Exception::class)
    fun extraProperties(task: Task): List<TaskProperty> = emptyList()

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
     * [TaskDecoration]s for a freshly fetched list, keyed by [Task.getId].
     *
     * Unlike [extraProperties], this feeds the list itself — the Status column and the row icon — so it
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

    // Repository types are package-private, so they can't be referenced directly — but each getName()
    // returns a literal rather than a localized bundle key, which makes matching on the string safe. A
    // rename on JetBrains' side greys the actions out; it can't misfire.
    private const val GITHUB = "GitHub"

    /** Lowercase "l" is not a typo: `GitlabRepositoryType.getName()` returns "Gitlab". */
    private const val GITLAB = "Gitlab"

    private const val YOUTRACK = "YouTrack"

    /** All caps, as `JiraRepositoryType.getName()` spells it. */
    private const val JIRA = "JIRA"
}
