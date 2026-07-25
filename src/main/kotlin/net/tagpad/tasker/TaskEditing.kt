package net.tagpad.tasker

import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository

/**
 * What a task server lets us change, and how.
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
}

/** Resolves the adapter for a server, or null when nothing on it can be edited. */
object TaskEditors {

    fun forRepository(repository: TaskRepository): TaskEditor? =
        when (repository.repositoryType?.name) {
            GITHUB -> GitHubTaskEditor(repository)
            GITLAB -> GitLabTaskEditor(repository)
            YOUTRACK -> YouTrackTaskEditor(repository)
            else -> null
        }

    // Repository types are package-private, so they can't be referenced directly — but each getName()
    // returns a literal rather than a localized bundle key, which makes matching on the string safe. A
    // rename on JetBrains' side greys the actions out; it can't misfire.
    private const val GITHUB = "GitHub"

    /** Lowercase "l" is not a typo: `GitlabRepositoryType.getName()` returns "Gitlab". */
    private const val GITLAB = "Gitlab"

    private const val YOUTRACK = "YouTrack"
}
