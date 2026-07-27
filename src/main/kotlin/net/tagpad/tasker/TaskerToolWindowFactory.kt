package net.tagpad.tasker

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the "Tasks" tool window (docked at the bottom via anchor=bottom in plugin.xml) and populates
 * it with a single panel that lists tasks grouped per configured task server.
 *
 * [DumbAware] because none of this needs indexes: the content comes from task servers over HTTP, and
 * nothing here reads PSI or asks a stub index anything. Without the marker the tool window sits
 * unavailable behind whatever indexing the IDE happens to be doing, which for a list of remote issues
 * would be waiting on work that can't affect the answer.
 */
class TaskerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TaskerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // The panel listens for task activation, so it has to be torn down with the content rather than
        // left for the garbage collector — an orphaned listener would keep repainting a dead tree.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
