package net.tagpad.tasker

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the "Tasks" tool window (docked lower-left via anchor=left, secondary=true in plugin.xml)
 * and populates it with a single panel that lists tasks grouped per configured task server.
 */
class TaskerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TaskerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // The panel listens for task activation, so it has to be torn down with the content rather than
        // left for the garbage collector — an orphaned listener would keep repainting a dead tree.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
