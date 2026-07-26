package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.basic.BasicTextUI
import javax.swing.text.View
import kotlin.math.max
import kotlin.math.min

/**
 * Right-hand pane for the selected task: a fixed header (id, summary, status, dates) above a scrolling
 * body (description, comments), with a comment composer pinned at the foot.
 *
 * Almost everything is a real Swing component rather than HTML. The status pill has to be the very same
 * [StatusBadge] the list paints, and the summary and description are edited in place — neither is
 * expressible inside a read-only [JEditorPane]. Only the comments go through the editor pane, because
 * those genuinely arrive as markup (GitHub hands back rendered HTML with avatars).
 */
class TaskDetailsPanel(private val edits: EditRequests) : JPanel(BorderLayout()) {

    /**
     * What the pane asks of its owner. Loading is split from saving because entering description edit
     * mode can require a server read first — see [EditRequests.loadDescription].
     */
    interface EditRequests {
        /**
         * Resolves the description to edit, off the EDT, and calls back on the EDT. Not invoked at all
         * if the read fails, which is deliberate: an editor opened on empty text would look like an
         * empty description, and saving it would wipe the real one.
         */
        fun loadDescription(onLoaded: (String) -> Unit)

        fun saveSummary(text: String)

        fun saveDescription(text: String)

        fun postComment(text: String)
    }

    // ---- header ----------------------------------------------------------------------------------

    private val idLabel = JBLabel().apply {
        font = JBFont.small().asBold()
        foreground = muted()
    }

    private val browserLink = ActionLink("Open in browser") { issueUrl?.let { url -> BrowserUtil.browse(url) } }.apply {
        setExternalLinkIcon()
        font = JBFont.small()
    }

    /** The title. Deliberately the platform heading font, so it reads as a heading and not as a field. */
    private val summaryText = WrappingText(JBFont.h2())
    private val summaryPen = PenButton("Rename") { beginSummaryEdit() }
    private val titleRow = TitleRow(summaryText, summaryPen)

    private val statusBadge = StatusBadge()
    private val createdField = MetaField("Created")
    private val updatedField = MetaField("Updated")

    /**
     * Created and Updated, then whatever else the tracker knows about the issue.
     *
     * Zero rows rather than one, so a task with more to say wraps onto further rows instead of squeezing
     * every field into a single line.
     */
    private val metaRow = JPanel(GridLayout(0, 2, JBUI.scale(16), JBUI.scale(10))).apply {
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
        add(titleRow, c.apply { gridx = 0; gridy = 1; gridwidth = 2; weightx = 1.0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insetsTop(6) })
        add(statusBadge, c.apply { gridy = 2; fill = GridBagConstraints.NONE; insets = JBUI.insetsTop(10) })
        add(metaRow, c.apply { gridy = 3; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insetsTop(14) })
    }

    // ---- body ------------------------------------------------------------------------------------

    private val descriptionPen = PenButton("Edit description") { beginDescriptionEdit() }
    private val descriptionText = WrappingText(JBFont.regular())

    private val descriptionEditButtons = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JButton("Save").apply { font = JBFont.small(); addActionListener { commitDescriptionEdit() } })
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(JButton("Cancel").apply { font = JBFont.small(); addActionListener { cancelDescriptionEdit() } })
        add(Box.createHorizontalGlue())
        isVisible = false
    }

    private val descriptionSection = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(headingRow("DESCRIPTION", descriptionPen), BorderLayout.NORTH)
        add(descriptionText, BorderLayout.CENTER)
        add(descriptionEditButtons, BorderLayout.SOUTH)
    }

    private val commentsHeading = sectionLabel("COMMENTS")

    private val commentsPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        // Let the HTML inherit the component font, so sizes track the IDE's font settings and DPI
        // instead of being hardcoded in the stylesheet.
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.regular()
        border = JBUI.Borders.empty()
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val target = e.url?.toString() ?: e.description
                if (!target.isNullOrBlank()) BrowserUtil.browse(target)
            }
        }
        // Wrapped text can't report a height until it has a width, and nothing else re-asks once the
        // column hands it one. Same problem WrappingText solves for itself.
        addComponentListener(object : ComponentAdapter() {
            private var measuredAt = -1
            override fun componentResized(e: ComponentEvent) {
                if (width == measuredAt) return
                measuredAt = width
                revalidate()
            }
        })
    }

    private val bodyColumn = BodyColumn().apply {
        border = JBUI.Borders.empty(10, 16, 16, 16)
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
        }
        add(descriptionSection, c)
        add(commentsHeading, c.apply { gridy = 1; insets = JBUI.insetsTop(16) })
        add(commentsPane, c.apply { gridy = 2; insets = JBUI.insetsTop(6) })
        // Soaks up leftover height so a short task's content stays at the top.
        add(Box.createVerticalGlue(), c.apply { gridy = 3; weighty = 1.0; fill = GridBagConstraints.BOTH; insets = JBUI.emptyInsets() })
    }

    // ---- composer --------------------------------------------------------------------------------

    private val commentField = JBTextArea().apply {
        rows = 2
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.regular()
        border = JBUI.Borders.empty(4)
    }

    private val sendButton = HoverButton("Add comment", SEND_ICON) { sendComment() }

    private val composer = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(8, 12),
        )
        add(
            JBScrollPane(commentField).apply {
                border = JBUI.Borders.customLine(JBColor.border())
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(sendButton, BorderLayout.NORTH) }, BorderLayout.EAST)
    }

    // ---- cards -----------------------------------------------------------------------------------

    private val placeholder = JBLabel("Select a task to see its details.", SwingConstants.CENTER).apply {
        foreground = muted()
    }

    private val taskCard = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(
            JBScrollPane(bodyColumn).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            },
            BorderLayout.CENTER,
        )
        add(composer, BorderLayout.SOUTH)
    }

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout).apply {
        add(placeholder, CARD_EMPTY)
        add(taskCard, CARD_TASK)
    }

    // ---- state -----------------------------------------------------------------------------------

    /** Target of [browserLink]; kept out of the listener so the link can be reused across selections. */
    private var issueUrl: String? = null

    /** Guards in-progress edits against the [show] that follows every write and every refresh. */
    private var currentTaskId: String? = null
    private var editingSummary = false
    private var editingDescription = false

    /** The description as last rendered, so cancelling an edit can restore it. */
    private var shownDescription = ""

    init {
        bindKey(summaryText, KeyEvent.VK_ENTER, "commitSummary") { commitSummaryEdit() }
        bindKey(summaryText, KeyEvent.VK_ESCAPE, "cancelSummary") { cancelSummaryEdit() }
        bindKey(descriptionText, KeyEvent.VK_ESCAPE, "cancelDescription") { cancelDescriptionEdit() }
        // Plain Enter sends; Shift+Enter isn't bound here so it falls through to inserting a newline.
        bindKey(commentField, KeyEvent.VK_ENTER, "sendComment") { sendComment() }

        add(cards, BorderLayout.CENTER)
        show(null)
    }

    /**
     * [extra] are fields the caller resolved that the task itself can't carry — see
     * [TaskEditor.extraProperties]. They arrive late for some providers, which simply means another
     * [show] for the same task once they land.
     */
    fun show(
        task: Task?,
        repository: TaskRepository? = null,
        loadingComments: Boolean = false,
        extra: List<TaskProperty> = emptyList(),
    ) {
        if (task == null) {
            issueUrl = null
            currentTaskId = null
            cardLayout.show(cards, CARD_EMPTY)
            return
        }

        // Moving to a different task abandons anything half-typed; staying on the same one must not,
        // because a save triggers a re-read that comes straight back through here.
        if (task.id != currentTaskId) {
            currentTaskId = task.id
            exitSummaryEdit()
            exitDescriptionEdit()
            commentField.text = ""
        }

        updateEditActions(task, repository)

        idLabel.text = task.presentableId
        issueUrl = task.issueUrl?.takeIf { it.isNotBlank() }
        browserLink.isVisible = issueUrl != null
        if (!editingSummary) summaryText.text = task.summary
        statusBadge.setStatus(statusText(task))
        createdField.setValue(formatDate(task.created))
        updatedField.setValue(formatDate(task.updated))
        renderMetaFields(task, extra)

        shownDescription = task.description.orEmpty()
        if (!editingDescription) renderDescription()

        commentsHeading.text = commentsHeadingText(task, loadingComments)
        commentsPane.text = renderComments(task, loadingComments)
        commentsPane.caretPosition = 0

        cardLayout.show(cards, CARD_TASK)
        header.revalidate()
        header.repaint()
        bodyColumn.revalidate()
        bodyColumn.repaint()
    }

    /**
     * Rebuilds the meta row: Created and Updated always, then everything else the tracker exposes.
     *
     * Fields are recreated rather than reused because their number varies by provider and by issue —
     * YouTrack alone reports four, GitHub one, a task with no assignee none at all.
     */
    private fun renderMetaFields(task: Task, extra: List<TaskProperty>) {
        metaRow.removeAll()
        metaRow.add(createdField)
        metaRow.add(updatedField)

        for (property in nativeProperties(task) + extra) {
            metaRow.add(MetaField(property.label).apply { setValue(property.value) })
        }
    }

    /**
     * What the tracker already handed us on the task, via [Task.getCustomProperties].
     *
     * [Task.getPropertiesToShowInPreview] is the tracker's own shortlist, and is honoured for both
     * membership and order; nothing in the platform reads it, so we are its first consumer. A tracker
     * that fills the map but leaves the shortlist empty gets all of it.
     *
     * State is dropped: the badge above already shows it, and showing it twice under two different
     * labels would just look like a bug.
     */
    private fun nativeProperties(task: Task): List<TaskProperty> {
        val properties = task.customProperties
        val order = task.propertiesToShowInPreview.ifEmpty { properties.keys.toList() }

        return order.mapNotNull { key ->
            if (key.equals("state", ignoreCase = true)) return@mapNotNull null
            val property = properties[key] ?: return@mapNotNull null
            property.value.takeIf { it.isNotBlank() }?.let { TaskProperty(property.displayName, it) }
        }
    }

    // ---- editing ---------------------------------------------------------------------------------

    private fun beginSummaryEdit() {
        if (editingSummary) return
        editingSummary = true
        summaryText.setEditing(true)
        summaryText.selectAll()
        summaryText.requestFocusInWindow()
    }

    private fun commitSummaryEdit() {
        if (!editingSummary) return
        val text = summaryText.text.trim()
        exitSummaryEdit()
        // A blank title isn't meaningful anywhere, and every tracker would reject it.
        if (text.isNotEmpty()) edits.saveSummary(text)
    }

    private fun cancelSummaryEdit() {
        if (!editingSummary) return
        exitSummaryEdit()
    }

    private fun exitSummaryEdit() {
        editingSummary = false
        summaryText.setEditing(false)
    }

    private fun beginDescriptionEdit() {
        if (editingDescription) return
        // The text to edit may have to be fetched: GitLab's Task carries no description even when the
        // issue has one. The callback only fires on success, so a failed read leaves read mode intact.
        edits.loadDescription { current ->
            editingDescription = true
            descriptionText.setEditing(true)
            descriptionText.text = current
            descriptionEditButtons.isVisible = true
            descriptionText.requestFocusInWindow()
            bodyColumn.revalidate()
        }
    }

    private fun commitDescriptionEdit() {
        if (!editingDescription) return
        val text = descriptionText.text
        exitDescriptionEdit()
        edits.saveDescription(text)
    }

    private fun cancelDescriptionEdit() {
        if (!editingDescription) return
        exitDescriptionEdit()
    }

    private fun exitDescriptionEdit() {
        // Save and Cancel are about to be hidden. Swing hands the focus of a component it hides to
        // whatever comes next in the traversal order, which is nowhere near here — the toolbar's task
        // limit spinner, in practice, left wearing a focus ring for no reason. Take the focus back.
        // Only when the edit actually held it: this same exit runs when the selection moves to another
        // task, and stealing focus off the list at that point would be the worse bug.
        val heldFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?.let { SwingUtilities.isDescendingFrom(it, descriptionSection) } == true

        editingDescription = false
        descriptionText.setEditing(false)
        descriptionEditButtons.isVisible = false
        renderDescription()
        bodyColumn.revalidate()

        // A read-only text area draws no caret and, in read mode, no border either, so this parks the
        // focus without marking anything.
        if (heldFocus) descriptionText.requestFocusInWindow()
    }

    /** Read mode. An absent description still shows the section, since the pen is how you add one. */
    private fun renderDescription() {
        val present = shownDescription.isNotBlank()
        descriptionText.text = if (present) shownDescription else "No description"
        descriptionText.foreground = if (present) UIUtil.getLabelForeground() else muted()
    }

    private fun sendComment() {
        if (!commentField.isEnabled) return
        val text = commentField.text.trim()
        if (text.isEmpty()) return
        commentField.text = ""
        edits.postComment(text)
    }

    /**
     * An action is offered only when the provider has an adapter *and* that adapter can address this
     * particular task; the messages distinguish the two, since "YouTrack can't do this" and "this issue
     * has no usable url" are different problems.
     */
    private fun updateEditActions(task: Task, repository: TaskRepository?) {
        val editor = repository?.let(TaskEditors::forRepository)
        val reason =
            if (editor == null) "Not supported for ${repository?.repositoryType?.name ?: "this server"}"
            else "Not available for this task"

        summaryPen.setState(editor?.canRename(task) == true, reason)
        descriptionPen.setState(editor?.canEditDescription(task) == true, reason)

        val canComment = editor?.canComment(task) == true
        commentField.isEnabled = canComment
        sendButton.isEnabled = canComment
        // The composer has no label of its own, so the empty text carries the explanation the greyed
        // links used to give.
        commentField.emptyText.text = if (canComment) "Add a comment…" else reason
    }

    // ---- comment rendering -----------------------------------------------------------------------

    private fun commentsHeadingText(task: Task, loadingComments: Boolean): String = when {
        loadingComments -> "COMMENTS"
        task.comments.isEmpty() -> "COMMENTS"
        else -> "COMMENTS (${task.comments.size})"
    }

    private fun renderComments(task: Task, loadingComments: Boolean): String {
        val sb = StringBuilder("<html><head><style>").append(css()).append("</style></head><body>")
        when {
            loadingComments -> sb.append("<div class='muted'>Loading…</div>")
            task.comments.isEmpty() -> sb.append("<div class='muted'>No comments.</div>")
            else -> for (comment in task.comments) appendComment(sb, comment)
        }
        return sb.append("</body></html>").toString()
    }

    /**
     * Renders a comment with our own byline instead of [Comment.appendTo], whose default emits an
     * unstyled `<hr><b>Author:</b>…` block we can't restyle.
     *
     * The body is **not** escaped: trackers hand back rendered HTML here (GitHub's comment text is the
     * issue's `body_html`), which is also why the platform's own renderer passes it through untouched.
     * The description is the opposite case — raw markdown — which is part of why it now lives outside
     * this pane entirely.
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
     * Sizes are relative keywords, not point values, so the body follows [commentsPane]'s font. Colors
     * have to be baked in per render — the stylesheet can't reference theme colors symbolically — which
     * is fine, since a theme switch re-renders the pane anyway.
     */
    private fun css(): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(muted())
        val link = hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return """
            body { color: $fg; }
            a { color: $link; text-decoration: none; }
            .text { margin-top: 0; margin-bottom: 4px; }
            .muted { color: $muted; }
            .comment { margin-top: 0; margin-bottom: 14px; }
            .byline { font-size: smaller; color: $muted; margin-top: 0; margin-bottom: 3px; }
        """.trimIndent()
    }

    private fun esc(s: String): String = StringUtil.escapeXmlEntities(s)

    private fun hex(color: Color): String = "#" + ColorUtil.toHex(color)

    // ---- small building blocks -------------------------------------------------------------------

    private fun headingRow(caption: String, pen: PenButton): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(sectionLabel(caption))
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(pen)
        add(Box.createHorizontalGlue())
    }

    private fun sectionLabel(caption: String) = JBLabel(caption).apply {
        font = JBFont.small().asBold()
        foreground = muted()
    }

    /** Binds a keystroke on a focused component, without disturbing the rest of its input map. */
    private fun bindKey(component: JComponent, keyCode: Int, name: String, action: () -> Unit) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    /**
     * An icon button that admits to being one.
     *
     * [InplaceButton] offers no hover feedback out of the box: its `paintHover` hook is empty, and the
     * single-icon constructor registers the same icon for hovered as for regular, so nothing changes
     * under the pointer. The platform's own hand cursor for it is commented out in the source, too.
     */
    private class HoverButton(tooltip: String, icon: Icon, action: () -> Unit) :
        InplaceButton(tooltip, icon, ActionListener { action() }) {

        init {
            // Breathing room around the 16px glyph, so the highlight reads as a button rather than as a
            // box drawn tight around the icon. InplaceButton centers its icon in whatever size the
            // component ends up with, so growing it is all that is needed.
            val pad = JBUI.scale(6)
            preferredSize = Dimension(icon.iconWidth + pad, icon.iconHeight + pad)
            // Routed through setEnabled rather than assigning the cursor here, so the starting state and
            // every later change come from one place.
            isEnabled = true
        }

        override fun paintHover(g: Graphics) = paintHover(g, JBUI.CurrentTheme.ActionButton.hoverBackground())

        /** Paired with the enabled state so a greyed-out icon doesn't keep promising a click. */
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        }
    }

    /**
     * A pen, in a wrapper panel.
     *
     * [HoverButton] greys its icon when disabled on its own, but Swing withholds mouse events from
     * disabled components, so a tooltip set on the button itself would never appear. The enabled wrapper
     * carries it.
     */
    private class PenButton(private val label: String, action: () -> Unit) : JPanel(BorderLayout()) {

        private val button = HoverButton(label, AllIcons.Actions.Edit, action)

        init {
            isOpaque = false
            add(button, BorderLayout.CENTER)
        }

        fun setState(enabled: Boolean, disabledReason: String) {
            button.isEnabled = enabled
            toolTipText = if (enabled) label else disabledReason
        }

        /** A panel's maximum size is unbounded, which would let BoxLayout stretch the pen. */
        override fun getMaximumSize(): Dimension = preferredSize
    }

    /**
     * The title, with its pen tucked against the end of the text.
     *
     * A wrapping text area claims every pixel of the column it is handed, so under an ordinary layout
     * the pen would sit against the far edge of the header however short the title is. Measure the text
     * unwrapped instead and give it the whole column only when it needs one: a long title still wraps,
     * and its pen still ends up top right, level with the first line.
     */
    private class TitleRow(private val text: WrappingText, private val pen: JComponent) : JPanel() {

        private val gap: Int get() = JBUI.scale(6)

        init {
            layout = null
            isOpaque = false
            add(text)
            add(pen)
        }

        override fun doLayout() {
            val textWidth = textWidth(width)
            val penSize = pen.preferredSize
            text.setBounds(0, 0, textWidth, text.preferredHeightAt(textWidth))
            pen.setBounds(textWidth + gap, penTop(penSize.height), penSize.width, penSize.height)
        }

        override fun getPreferredSize(): Dimension {
            val penSize = pen.preferredSize
            if (width <= 0) {
                val natural = text.naturalWidth()
                return Dimension(natural + gap + penSize.width, max(text.preferredHeightAt(natural), penSize.height))
            }
            val textWidth = textWidth(width)
            return Dimension(width, max(text.preferredHeightAt(textWidth), penTop(penSize.height) + penSize.height))
        }

        /** Only as wide as the text actually needs, up to whatever is left once the pen has its place. */
        private fun textWidth(available: Int): Int {
            val room = max(JBUI.scale(60), available - gap - pen.preferredSize.width)
            // While typing, the field keeps the whole column: a box creeping wider a character at a time
            // would be more distracting than one that simply looks like a field from the start.
            return if (text.isEditable) room else min(text.naturalWidth(), room)
        }

        /** Centered on the title's first line, so a title that wraps doesn't drag the pen down with it. */
        private fun penTop(penHeight: Int): Int =
            text.insets.top + max(0, (text.getFontMetrics(text.font).height - penHeight) / 2)
    }

    /**
     * The scroll pane's contents.
     *
     * Tracking the viewport width is the load-bearing part: left to itself the nested [JEditorPane]
     * reports the width of its longest unbroken line as its preferred width, so it would never wrap and
     * the pane would scroll sideways instead.
     */
    private class BodyColumn : JPanel(GridBagLayout()), Scrollable {
        init {
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = r.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

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
     * A word-wrapping text block that doubles as its own editor.
     *
     * Toggling [setEditing] in place, rather than swapping in a separate field, keeps the text at the
     * same size and position when an edit starts — the layout doesn't jump.
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

        fun setEditing(editing: Boolean) {
            isEditable = editing
            isOpaque = editing
            background = if (editing) UIUtil.getTextFieldBackground() else null
            border = if (editing) {
                BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(2, 4))
            } else {
                null
            }
            foreground = UIUtil.getLabelForeground()
            revalidate()
            repaint()
        }

        override fun getPreferredSize(): Dimension {
            if (width <= 0) return super.getPreferredSize()
            return Dimension(width, preferredHeightAt(width))
        }

        /** The height the text needs at [w] pixels wide — answerable before it has ever been that wide. */
        fun preferredHeightAt(w: Int): Int {
            val rootView = (ui as? BasicTextUI)?.getRootView(this) ?: return super.getPreferredSize().height
            rootView.setSize(w.toFloat(), Float.MAX_VALUE)
            return rootView.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        }

        /** The width the text would take if nothing wrapped it. */
        fun naturalWidth(): Int {
            val metrics = getFontMetrics(font)
            // A couple of pixels of slack: FontMetrics rounds per string, and rounding short by one would
            // wrap the last word onto a line of its own.
            return text.lineSequence().maxOf(metrics::stringWidth) + insets.left + insets.right + JBUI.scale(2)
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

        /**
         * The composer's paper plane. Ships with the plugin — AllIcons has no send icon, only navigation
         * arrows, which read as "next" rather than "post this". The `_dark` companion file is picked up
         * by name, so this one constant covers both themes.
         */
        val SEND_ICON: Icon = IconLoader.getIcon("/icons/send.svg", TaskDetailsPanel::class.java)

        /** Resolved per call rather than cached, so captions re-color on a theme switch. */
        fun muted(): Color = NamedColorUtil.getInactiveTextColor()
    }
}
