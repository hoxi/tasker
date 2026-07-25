package net.tagpad.tasker

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Collects the one piece of text a task edit needs: a new summary, a replacement description, or the
 * body of a comment.
 *
 * A summary is a single line and gets a text field; descriptions and comments are routinely
 * paragraphs, so they get a scrollable text area.
 */
class TaskTextInputDialog(
    project: Project,
    dialogTitle: String,
    private val label: String,
    initialText: String,
    private val multiline: Boolean,
    private val allowBlank: Boolean,
) : DialogWrapper(project) {

    // Not named `field`: inside the `text` accessor below that would resolve to Kotlin's backing-field
    // keyword rather than this property.
    private val lineField = if (multiline) null else JBTextField().apply {
        text = initialText
        columns = 48
    }

    private val area = if (!multiline) null else JBTextArea().apply {
        text = initialText
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = dialogTitle
        init()
    }

    val text: String get() = (lineField?.text ?: area?.text).orEmpty()

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(JBLabel(label), BorderLayout.NORTH)
        val body: JComponent = area
            ?.let { JBScrollPane(it).apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(280)) } }
            ?: lineField!!
        add(body, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent? = lineField ?: area

    override fun doValidate(): ValidationInfo? =
        if (allowBlank || text.isNotBlank()) null
        else ValidationInfo("Cannot be empty", getPreferredFocusedComponent())
}
