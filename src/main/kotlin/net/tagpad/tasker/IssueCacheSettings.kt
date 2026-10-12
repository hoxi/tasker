package net.tagpad.tasker

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.tasks.TaskManager

/**
 * Task Management's own "Update issue cache every N minutes" setting, under Settings | Tools | Tasks,
 * which Tasker follows for how long anything it read from a server counts as current.
 *
 * Read from `TaskManagerImpl`'s state, whose `updateEnabled` and `updateInterval` are public fields of a
 * public settings class. Read by name rather than compiled against, like the Jira adapter's flags: should
 * either ever go, Tasker falls back to the platform's defaults instead of failing to load.
 */
internal class IssueCacheSettings(val enabled: Boolean, val intervalMinutes: Int) {

    /** How long data counts as current: the interval, or for good while updating is switched off. */
    val maxAgeMs: Long get() = if (enabled) intervalMinutes.coerceAtLeast(1) * 60_000L else Long.MAX_VALUE

    companion object {
        private val LOG = logger<IssueCacheSettings>()

        /** The platform's own defaults, from `TaskManagerImpl.Config`. */
        private val DEFAULTS = IssueCacheSettings(enabled = true, intervalMinutes = 20)

        /** The settings as they are now; cheap enough to call whenever an age is checked. */
        fun read(project: Project): IssueCacheSettings = try {
            val manager = TaskManager.getManager(project)
            val state = manager.javaClass.getMethod("getState").invoke(manager) ?: DEFAULTS
            if (state === DEFAULTS) {
                DEFAULTS
            } else {
                IssueCacheSettings(
                    enabled = state.javaClass.getField("updateEnabled").getBoolean(state),
                    intervalMinutes = state.javaClass.getField("updateInterval").getInt(state),
                )
            }
        } catch (e: ReflectiveOperationException) {
            LOG.debug("Task Management's cache settings are unreadable; using its defaults", e)
            DEFAULTS
        }
    }
}
