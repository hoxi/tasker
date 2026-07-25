package net.tagpad.tasker

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.basic.BasicTextUI
import javax.swing.text.View

/**
 * Right-hand pane for the selected task: a fixed header (id, summary, status, dates) above a scrolling
 * body (description, comments).
 *
 * The header is built from real Swing components rather than HTML because the status pill has to be the
 * very same [StatusBadge] the list paints, and Swing's HTML renderer has no rounded corners. Only the
 * body — free-form issue text — goes through the editor pane.
 */
class TaskDetailsPanel : JPanel(BorderLayout()) {

    private val idLabel = JBLabel().apply {
        font = JBFont.small().asBold()
        foreground = muted()
    }

    private val browserLink = ActionLink("Open in browser") { issueUrl?.let { url -> BrowserUtil.browse(url) } }.apply {
        setExternalLinkIcon()
        font = JBFont.small()
    }

    /** The title. Deliberately the platform heading font, so it reads as a heading and not as a field. */
    private val summaryLabel = WrappingText(JBFont.h2())

    private val statusBadge = StatusBadge()
    private val createdField = MetaField("Created")
    private val updatedField = MetaField("Updated")

    private val bodyPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        // Let the HTML inherit the component font, so sizes track the IDE's font settings and DPI
        // instead of being hardcoded in the stylesheet.
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.regular()
        border = JBUI.Borders.empty(4, 16, 16, 16)
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val target = e.url?.toString() ?: e.description
                if (!target.isNullOrBlank()) BrowserUtil.browse(target)
            }
        }
    }

    private val metaRow = JPanel(GridLayout(1, 2, JBUI.scale(16), 0)).apply {
        isOpaque = false
        add(createdField)
        add(updatedField)
    }

    private val header = JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(14, 16),
        )
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        add(idLabel, c)
        add(browserLink, c.apply { gridx = 1; weightx = 0.0; anchor = GridBagConstraints.EAST; fill = GridBagConstraints.NONE })
        add(summaryLabel, c.apply { gridx = 0; gridy = 1; gridwidth = 2; weightx = 1.0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insetsTop(6) })
        add(statusBadge, c.apply { gridy = 2; fill = GridBagConstraints.NONE; insets = JBUI.insetsTop(10) })
        add(metaRow, c.apply { gridy = 3; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insetsTop(14) })
    }

    private val placeholder = JBLabel("Select a task to see its details.", SwingConstants.CENTER).apply {
        foreground = muted()
    }

    private val taskCard = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(
            JBScrollPane(bodyPane).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            },
            BorderLayout.CENTER,
        )
    }

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout).apply {
        add(placeholder, CARD_EMPTY)
        add(taskCard, CARD_TASK)
    }

    /** Target of [browserLink]; kept out of the listener so the link can be reused across selections. */
    private var issueUrl: String? = null

    init {
        add(cards, BorderLayout.CENTER)
        show(null)
    }

    fun show(task: Task?, loadingComments: Boolean = false) {
        if (task == null) {
            issueUrl = null
            cardLayout.show(cards, CARD_EMPTY)
            return
        }

        idLabel.text = task.presentableId
        issueUrl = task.issueUrl?.takeIf { it.isNotBlank() }
        browserLink.isVisible = issueUrl != null
        summaryLabel.text = task.summary
        statusBadge.setStatus(statusText(task))
        createdField.setValue(formatDate(task.created))
        updatedField.setValue(formatDate(task.updated))

        bodyPane.text = renderBody(task, loadingComments)
        bodyPane.caretPosition = 0

        cardLayout.show(cards, CARD_TASK)
        // The badge's width and the summary's height both depend on the new text.
        header.revalidate()
        header.repaint()
    }

    private fun renderBody(task: Task, loadingComments: Boolean): String {
        val sb = StringBuilder("<html><head><style>").append(css()).append("</style></head><body>")

        val description = task.description?.takeIf { it.isNotBlank() }
        if (description != null) {
            sb.append("<div class='section'>DESCRIPTION</div>")
            sb.append("<div class='text'>").append(multiline(description)).append("</div>")
        }

        val comments = task.comments
        when {
            loadingComments -> sb.append("<div class='section'>COMMENTS</div><div class='muted'>Loading…</div>")
            comments.isNotEmpty() -> {
                sb.append("<div class='section'>COMMENTS (").append(comments.size).append(")</div>")
                for (comment in comments) appendComment(sb, comment)
            }
            description == null -> sb.append("<div class='muted'>No description or comments.</div>")
        }

        return sb.append("</body></html>").toString()
    }

    /**
     * Renders a comment with our own byline instead of [Comment.appendTo], whose default emits an
     * unstyled `<hr><b>Author:</b>…` block we can't restyle.
     *
     * The body is **not** escaped: trackers hand back rendered HTML here (GitHub's comment text is the
     * issue's `body_html`), which is also why the platform's own renderer passes it through untouched.
     * The description is the opposite case — that one arrives as raw markdown and is still escaped.
     */
    private fun appendComment(sb: StringBuilder, comment: Comment) {
        val byline = listOfNotNull(
            comment.author?.takeIf { it.isNotBlank() },
            comment.date?.let(::formatDate)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val avatar = avatarUrl(comment)

        sb.append("<div class='comment'>")
        if (avatar != null || byline.isNotEmpty()) {
            sb.append("<div class='byline'>")
            if (avatar != null) {
                val size = JBUI.scale(AVATAR_SIZE)
                sb.append("<img src='").append(esc(avatar))
                    .append("' width='").append(size).append("' height='").append(size)
                    .append("' align='middle'>&nbsp;")
            }
            sb.append(esc(byline)).append("</div>")
        }
        sb.append("<div class='text'>").append(comment.text.orEmpty()).append("</div>")
        sb.append("</div>")
    }

    /**
     * Recovers the commenter's avatar, which is reachable only through [Comment.appendTo] — GithubComment
     * keeps the url private and renders it into a header table itself.
     *
     * So: run the platform renderer, keep only what it emitted *before* the comment body, and take the
     * first image from that prefix. Restricting the search to the prefix is what stops an image inside
     * the comment text from being mistaken for an avatar; if the body can't be located, we'd rather show
     * no avatar than the wrong one.
     */
    private fun avatarUrl(comment: Comment): String? {
        val text = comment.text.orEmpty()
        val rendered = StringBuilder().also(comment::appendTo).toString()
        val header = when {
            text.isEmpty() -> rendered
            else -> rendered.indexOf(text).takeIf { it >= 0 }?.let { rendered.substring(0, it) } ?: return null
        }
        return IMG_SRC.find(header)?.groupValues?.get(1)
    }

    /**
     * Sizes are relative keywords, not point values, so the body follows [bodyPane]'s font. Colors have
     * to be baked in per render — the stylesheet can't reference theme colors symbolically — which is
     * fine, since a theme switch re-renders the pane anyway.
     */
    private fun css(): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(muted())
        val link = hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return """
            body { color: $fg; }
            a { color: $link; text-decoration: none; }
            .section { font-size: smaller; font-weight: bold; color: $muted; margin-top: 16px; margin-bottom: 6px; }
            .text { margin-top: 0; margin-bottom: 4px; }
            .muted { color: $muted; }
            .comment { margin-top: 0; margin-bottom: 14px; }
            .byline { font-size: smaller; color: $muted; margin-top: 0; margin-bottom: 3px; }
        """.trimIndent()
    }

    private fun multiline(text: String): String = esc(text).replace("\n", "<br>")

    private fun esc(s: String): String = StringUtil.escapeXmlEntities(s)

    private fun hex(color: Color): String = "#" + ColorUtil.toHex(color)

    /** A caption above its value — the shape used for the Created / Updated columns. */
    private class MetaField(caption: String) : JPanel(BorderLayout(0, JBUI.scale(2))) {

        private val value = JBLabel()

        init {
            isOpaque = false
            add(
                JBLabel(caption.uppercase()).apply {
                    font = JBFont.small().asBold()
                    foreground = muted()
                },
                BorderLayout.NORTH,
            )
            add(value, BorderLayout.CENTER)
        }

        fun setValue(text: String) {
            val known = text.isNotBlank()
            value.text = if (known) text else "—"
            value.foreground = if (known) UIUtil.getLabelForeground() else muted()
        }
    }

    /**
     * A read-only, word-wrapping text block.
     *
     * A wrapping [JTextArea] can only work out its height once it knows its width, but the layout
     * manager asks for a preferred size before assigning one. So measure the text view against the width
     * we were actually given, and re-measure whenever that width changes — the guard on [measuredAt]
     * matters, because re-measuring on every resize event would loop against our own revalidate.
     */
    private class WrappingText(textFont: Font) : JTextArea() {

        private var measuredAt = -1

        init {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            font = textFont
            foreground = UIUtil.getLabelForeground()
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (width == measuredAt) return
                    measuredAt = width
                    revalidate()
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val rootView = (ui as? BasicTextUI)?.getRootView(this)
            if (width <= 0 || rootView == null) return super.getPreferredSize()
            rootView.setSize(width.toFloat(), Float.MAX_VALUE)
            return Dimension(width, rootView.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom)
        }

        /**
         * A text area's default minimum is its unwrapped width, which would stop the splitter from ever
         * narrowing the pane past the longest summary. Wrapping text has no meaningful minimum width.
         */
        override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(60), preferredSize.height)
    }

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_TASK = "task"

        /** Avatar edge length, scaled. Half of the platform renderer's hardcoded 40. */
        const val AVATAR_SIZE = 20

        private val IMG_SRC = Regex("""<img[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /** Resolved per call rather than cached, so captions re-color on a theme switch. */
        fun muted(): Color = NamedColorUtil.getInactiveTextColor()
    }
}
