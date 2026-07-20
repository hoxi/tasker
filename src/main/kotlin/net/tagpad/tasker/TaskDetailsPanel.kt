package net.tagpad.tasker

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Task
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/** Right-hand pane that renders the selected task as HTML (fields, description, comments). */
class TaskDetailsPanel : JPanel(BorderLayout()) {

    private val editorPane = JEditorPane("text/html", "").apply {
        isEditable = false
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val target = e.url?.toString() ?: e.description
                if (!target.isNullOrBlank()) BrowserUtil.browse(target)
            }
        }
    }

    init {
        add(JBScrollPane(editorPane), BorderLayout.CENTER)
        show(null)
    }

    fun show(task: Task?, loadingComments: Boolean = false) {
        editorPane.text = if (task == null) html("<i>Select a task to see its details.</i>") else render(task, loadingComments)
        editorPane.caretPosition = 0
    }

    private fun render(task: Task, loadingComments: Boolean): String {
        val sb = StringBuilder()
        sb.append("<h2>").append(esc(task.presentableId)).append("</h2>")
        task.issueUrl?.takeIf { it.isNotBlank() }?.let {
            sb.append("<p><a href=\"").append(esc(it)).append("\">Open in browser</a></p>")
        }
        sb.append("<p><b>Summary:</b> ").append(esc(task.summary)).append("</p>")
        sb.append("<p><b>Status:</b> ").append(esc(statusText(task))).append("</p>")
        task.created?.let { sb.append("<p><b>Created:</b> ").append(esc(formatDate(it))).append("</p>") }
        task.updated?.let { sb.append("<p><b>Updated:</b> ").append(esc(formatDate(it))).append("</p>") }
        task.description?.takeIf { it.isNotBlank() }?.let {
            sb.append("<hr><b>Description</b><br>").append(esc(it).replace("\n", "<br>"))
        }
        if (loadingComments) {
            sb.append("<hr><i>Loading comments…</i>")
        } else {
            val comments = task.comments
            if (comments.isNotEmpty()) {
                sb.append("<hr><b>Comments (").append(comments.size).append(")</b>")
                // Comment.appendTo renders itself as an HTML fragment (author/date headers + text).
                for (comment in comments) comment.appendTo(sb)
            }
        }
        return html(sb.toString())
    }

    private fun esc(s: String): String = StringUtil.escapeXmlEntities(s)

    private fun html(body: String): String =
        "<html><body style=\"font-family:sans-serif; margin:6px;\">$body</body></html>"
}
