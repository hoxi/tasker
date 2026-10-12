package net.tagpad.tasker

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
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
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Shape
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusEvent
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
import javax.swing.text.Element
import javax.swing.text.Position
import javax.swing.text.View
import javax.swing.text.html.ObjectView
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

        fun saveComment(comment: Comment, text: String)
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

    /**
     * The status pill, then one pill per tag or label. Wrapping, so a task with many labels grows a line
     * instead of pushing them out of sight.
     */
    private val badgeRow = JPanel(WrapLayout(FlowLayout.LEFT).apply { hgap = 0; vgap = JBUI.scale(4) }).apply {
        isOpaque = false
    }
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
        add(badgeRow, c.apply { gridy = 2; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insetsTop(10) })
        add(metaRow, c.apply { gridy = 3; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insetsTop(14) })
    }

    // ---- body ------------------------------------------------------------------------------------

    private val descriptionPen = PenButton("Edit description") { beginDescriptionEdit() }
    private val descriptionText = WrappingText(JBFont.regular())

    private val descriptionEditor = InlineEditor(JBFont.regular(), ::commitDescriptionEdit, ::cancelDescriptionEdit)

    /** Holds the description's read view or its editor, whichever is current. */
    private val descriptionBody = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(descriptionText, BorderLayout.CENTER)
    }

    private val descriptionSection = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(headingRow("DESCRIPTION", descriptionPen), BorderLayout.NORTH)
        add(descriptionBody, BorderLayout.CENTER)
    }

    private val pullRequestsHeading = sectionLabel("PULL REQUESTS")
    private val pullRequestsList = JPanel(VerticalLayout(4)).apply { isOpaque = false }

    /** Linked pull and merge requests; hidden while there are none, rather than announcing an empty list. */
    private val pullRequestsSection = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        isOpaque = false
        isVisible = false
        add(pullRequestsHeading, BorderLayout.NORTH)
        add(pullRequestsList, BorderLayout.CENTER)
    }

    private val commentsHeading = sectionLabel("COMMENTS")

    /**
     * One [CommentView] per comment, or a single message while there are none to show. Each comment is
     * its own pane, rather than one document for all of them, so one can turn into an editor in place.
     */
    private val commentsList = JPanel(VerticalLayout(0)).apply { isOpaque = false }

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
        add(pullRequestsSection, c.apply { gridy = 1; insets = JBUI.insetsTop(16) })
        add(commentsHeading, c.apply { gridy = 2; insets = JBUI.insetsTop(16) })
        add(commentsList, c.apply { gridy = 3; insets = JBUI.insetsTop(6) })
        // Soaks up leftover height so a short task's content stays at the top.
        add(Box.createVerticalGlue(), c.apply { gridy = 4; weighty = 1.0; fill = GridBagConstraints.BOTH; insets = JBUI.emptyInsets() })
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

    /**
     * Guards in-progress edits against the [show] that follows every write and every refresh: a
     * [TaskKey], so the same id on another server counts as another task, or the bare id when [show]
     * isn't told the server.
     */
    private var currentTask: Any? = null
    private var editingSummary = false
    private var editingDescription = false

    /** The description as last rendered, so cancelling an edit can restore it. */
    private var shownDescription = ""

    /** The summary as last rendered, for the same reason. */
    private var shownSummary = ""

    /** The task on display and its adapter, which decide per comment whether it gets a pen. */
    private var shownTask: Task? = null
    private var shownEditor: TaskEditor? = null

    /** The comment being rewritten in place, if any. */
    private var editingComment: CommentView? = null

    /**
     * Comments that arrived while one was being edited. Rendering them would replace the view the edit
     * lives in, so they wait for the edit to end.
     */
    private var pendingComments: Pair<List<Comment>, Boolean>? = null

    init {
        bindKey(summaryText, KeyEvent.VK_ENTER, "commitSummary") { commitSummaryEdit() }
        bindKey(summaryText, KeyEvent.VK_ESCAPE, "cancelSummary") { cancelSummaryEdit() }
        // Plain Enter sends; Shift+Enter isn't bound here so it falls through to inserting a newline.
        bindKey(commentField, KeyEvent.VK_ENTER, "sendComment") { sendComment() }
        bindKey(commentField, KeyEvent.VK_ESCAPE, "cancelComment") { cancelComment() }

        add(cards, BorderLayout.CENTER)
        show(null)
    }

    /**
     * [extra] are fields the caller resolved that the task itself can't carry — see [TaskEditor.extras],
     * as are [priority] and [labels]. They arrive late for some providers, which simply means another
     * [show] for the same task once they land. [status] overrides the task's own, for trackers whose
     * [Task.getState] loses the name — see [TaskDecoration]. [comments] likewise stands in for
     * [Task.getComments] when the caller fetched them separately — see [TaskEditor.comments].
     */
    fun show(
        task: Task?,
        repository: TaskRepository? = null,
        loadingComments: Boolean = false,
        extra: List<TaskProperty> = emptyList(),
        status: String? = null,
        comments: List<Comment>? = null,
        priority: TaskPriority? = null,
        labels: List<TaskLabel> = emptyList(),
        pullRequests: List<LinkedPullRequest> = emptyList(),
    ) {
        if (task == null) {
            issueUrl = null
            currentTask = null
            cardLayout.show(cards, CARD_EMPTY)
            return
        }

        /*
         * Moving to a different task abandons anything half-typed; staying on the same one must not,
         * because a save triggers a re-read that comes straight back through here.
         */
        val shown: Any = repository?.let { TaskKey(it, task.id) } ?: task.id
        if (shown != currentTask) {
            currentTask = shown
            exitSummaryEdit()
            exitDescriptionEdit()
            exitCommentEdit()
            commentField.text = ""
        }

        shownTask = task
        updateEditActions(task, repository)

        idLabel.text = task.presentableId
        issueUrl = task.issueUrl?.takeIf { it.isNotBlank() }
        browserLink.isVisible = issueUrl != null
        shownSummary = task.summary.orEmpty()
        if (!editingSummary) summaryText.text = shownSummary
        statusBadge.setStatus(status ?: statusText(task))
        renderBadges(labels)
        refreshDates()
        renderMetaFields(task, extra, priority)

        shownDescription = task.description.orEmpty()
        if (!editingDescription) renderDescription()

        val shownComments = comments ?: task.comments.toList()
        commentsHeading.text = commentsHeadingText(shownComments, loadingComments)
        renderPullRequests(pullRequests)
        if (editingComment == null) renderComments(shownComments, loadingComments)
        else pendingComments = shownComments to loadingComments

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
    private fun renderMetaFields(task: Task, extra: List<TaskProperty>, priority: TaskPriority?) {
        metaRow.removeAll()
        metaRow.add(createdField)
        metaRow.add(updatedField)
        priority?.let { metaRow.add(MetaField("Priority").apply { setValue(it.name, it.icon) }) }

        for (property in nativeProperties(task) + extra) {
            // YouTrack's connector reports Priority as text too; the adapter's, with its colour, wins.
            if (priority != null && property.label.equals("Priority", ignoreCase = true)) continue
            metaRow.add(MetaField(property.label).apply { setValue(property.value) })
        }
    }

    /** The status pill first, then the task's labels in the tracker's order. */
    private fun renderBadges(labels: List<TaskLabel>) {
        badgeRow.removeAll()
        badgeRow.add(statusBadge)
        for (label in labels) {
            badgeRow.add(StatusBadge().apply {
                setLabel(label.name, label.background, label.foreground)
                font = JBFont.small()
            })
        }
        badgeRow.revalidate()
        badgeRow.repaint()
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
        summaryText.text = shownSummary
    }

    private fun exitSummaryEdit() {
        editingSummary = false
        summaryText.setEditing(false)
    }

    private fun beginDescriptionEdit() {
        if (editingDescription) return
        /*
         * The text to edit may have to be fetched: GitLab's Task carries no description even when the
         * issue has one. The callback only fires on success, so a failed read leaves read mode intact.
         *
         * The read takes a round trip, and the selection may move on in the meantime. An editor opened
         * then would hold the old task's description over the new task, and saving it would overwrite
         * the new task's — so an answer for a task no longer shown is dropped.
         */
        val requestedFor = currentTask
        edits.loadDescription { current ->
            if (currentTask != requestedFor || editingDescription) return@loadDescription
            editingDescription = true
            swap(descriptionBody, descriptionEditor)
            descriptionEditor.start(current)
        }
    }

    private fun commitDescriptionEdit(text: String) {
        if (!editingDescription) return
        exitDescriptionEdit()
        edits.saveDescription(text)
    }

    private fun cancelDescriptionEdit() {
        if (!editingDescription) return
        exitDescriptionEdit()
    }

    private fun exitDescriptionEdit() {
        if (!editingDescription) return
        val heldFocus = holdsFocus(descriptionSection)

        editingDescription = false
        swap(descriptionBody, descriptionText)
        renderDescription()

        if (heldFocus) parkFocus()
    }

    /**
     * Gives the focus to the pane's body column, which is focusable for exactly this: it draws nothing
     * when focused, and unlike every text and button inside it, it is never removed or replaced.
     *
     * Swing hands the focus of a removed component to whatever comes next in the traversal order, which
     * from here wraps round to the toolbar — the task limit spinner, left selected for no reason. Every
     * edit removes its editor, and every write re-renders the comments, so a focus parked on anything
     * inside them would be lost again a moment later. Call it after the removal, so it is the last word.
     */
    fun parkFocus() {
        bodyColumn.requestFocusInWindow()
    }

    /**
     * Whether the focus is inside [component], which is about to be removed — see [parkFocus]. Only focus
     * held there is taken back: the same exits run when the selection moves to another task, and stealing
     * the focus off the list then would be the worse bug.
     */
    private fun holdsFocus(component: JComponent): Boolean =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?.let { SwingUtilities.isDescendingFrom(it, component) } == true

    /** Makes [content] the one thing [holder] shows. */
    private fun swap(holder: JPanel, content: JComponent) {
        holder.removeAll()
        holder.add(content, BorderLayout.CENTER)
        holder.revalidate()
        holder.repaint()
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
     * Esc in the composer, the way it works in the title and description editors: the draft is
     * discarded and the focus leaves the composer.
     */
    private fun cancelComment() {
        commentField.text = ""
        parkFocus()
    }

    /** Turns [view] into an editor in place, starting from the comment's text as the tracker stores it. */
    private fun beginCommentEdit(view: CommentView) {
        val source = (view.comment as? EditableComment)?.source ?: return
        if (editingComment === view) return
        exitCommentEdit() // one comment at a time
        editingComment = view
        view.setEditing(true)
        view.editor.start(source)
    }

    private fun commitCommentEdit(view: CommentView, text: String) {
        if (editingComment !== view) return
        val body = text.trim()
        // Blank would erase the comment rather than edit it; stay in the editor instead.
        if (body.isEmpty()) return
        exitCommentEdit()
        edits.saveComment(view.comment, body)
    }

    private fun cancelCommentEdit() = exitCommentEdit()

    /** Back to reading, then catches up on any comments that arrived during the edit. */
    private fun exitCommentEdit() {
        val view = editingComment ?: return
        val heldFocus = holdsFocus(view)
        editingComment = null
        view.setEditing(false)

        pendingComments?.let { (comments, loading) ->
            pendingComments = null
            renderComments(comments, loading)
        }
        if (heldFocus) parkFocus()
    }

    /**
     * An action is offered only when the provider has an adapter *and* that adapter can address this
     * particular task; the messages distinguish the two, since "YouTrack can't do this" and "this issue
     * has no usable url" are different problems.
     */
    private fun updateEditActions(task: Task, repository: TaskRepository?) {
        val editor = repository?.let(TaskEditors::forRepository)
        shownEditor = editor
        val reason =
            if (editor == null) "Not supported for ${repository?.repositoryType?.name ?: "this server"}"
            else "Not available for this task"

        summaryPen.setState(editor?.canRename(task) == true, reason)
        descriptionPen.setState(editor?.canEditDescription(task) == true, reason)

        val canComment = editor?.canComment(task) == true
        commentField.isEnabled = canComment
        sendButton.isEnabled = canComment
        // The composer has no label of its own, so its empty text carries the explanation.
        commentField.emptyText.text = if (canComment) "Add a comment…" else reason
    }

    // ---- pull requests ---------------------------------------------------------------------------

    private fun renderPullRequests(pullRequests: List<LinkedPullRequest>) {
        pullRequestsList.removeAll()
        pullRequests.forEach { pullRequestsList.add(pullRequestRow(it)) }
        pullRequestsHeading.text =
            if (pullRequests.isEmpty()) "PULL REQUESTS" else "PULL REQUESTS (${pullRequests.size})"
        pullRequestsSection.isVisible = pullRequests.isNotEmpty()
        pullRequestsSection.revalidate()
        pullRequestsSection.repaint()
    }

    /**
     * One linked request: host icon, then reference and title as a link to the request on its host, then
     * its state. GitHub has an icon in the platform's set; GitLab and the rest get the generic merge one.
     */
    private fun pullRequestRow(pr: LinkedPullRequest): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        val icon = if (pr.host == CodeHost.GITHUB) AllIcons.Vcs.Vendors.Github else AllIcons.Vcs.Merge
        add(JBLabel(icon), BorderLayout.WEST)

        val text = listOf(pr.reference, pr.title).filter { it.isNotBlank() }.joinToString("  ")
        val link = ActionLink(text.ifBlank { pr.url }) { BrowserUtil.browse(pr.url) }.apply { toolTipText = pr.url }
        add(link, BorderLayout.CENTER)

        val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        pr.state?.let { state -> trailing.add(StatusBadge().apply { setPullRequestState(state) }) }
        if (pr.host == CodeHost.GITLAB) gitLabToolWindowLink()?.let(trailing::add)
        add(trailing, BorderLayout.EAST)
    }

    /**
     * Opens the bundled GitLab plugin's Merge Requests tool window, through the action it registers for
     * exactly that. Not the request itself: the plugin's way to open one by number is internal API.
     * Null when the plugin isn't there, so the link only shows where it can work.
     */
    private fun gitLabToolWindowLink(): JComponent? {
        val action = ActionManager.getInstance().getAction(GITLAB_MERGE_REQUESTS_ACTION) ?: return null
        return ActionLink("Show in IDE") {
            ActionManager.getInstance().tryToExecute(action, null, this, ActionPlaces.UNKNOWN, true)
        }.apply {
            font = JBFont.small()
            toolTipText = "Open GitLab's Merge Requests tool window"
            border = JBUI.Borders.emptyLeft(8)
        }
    }

    // ---- comment rendering -----------------------------------------------------------------------

    private fun commentsHeadingText(comments: List<Comment>, loadingComments: Boolean): String =
        if (loadingComments || comments.isEmpty()) "COMMENTS" else "COMMENTS (${comments.size})"

    private fun renderComments(comments: List<Comment>, loadingComments: Boolean) {
        val heldFocus = holdsFocus(commentsList)
        commentsList.removeAll()
        when {
            loadingComments -> commentsList.add(messagePane("Loading…"))
            comments.isEmpty() -> commentsList.add(messagePane("No comments."))
            else -> for ((comment, depth) in threadComments(comments)) commentsList.add(CommentView(comment, depth))
        }
        commentsList.revalidate()
        commentsList.repaint()
        if (heldFocus) parkFocus()
    }

    private fun messagePane(message: String): JEditorPane =
        htmlPane().apply { setHtml(this, "<div class='muted'>${esc(message)}</div>") }

    /** Loads [body] into [pane] with the pane's stylesheet, scrolled to its start. */
    private fun setHtml(pane: JEditorPane, body: String) {
        pane.text = "<html><head><style>${css()}</style></head><body>$body</body></html>"
        pane.caretPosition = 0
    }

    /** A read-only HTML pane for comment content, whose links open in the browser. */
    private fun htmlPane(): JEditorPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder()
            .withViewFactoryExtensions(::withoutEmbeddedComponents, ExtendableHTMLViewFactory.Extensions.WORD_WRAP)
            .build()
        isEditable = false
        isOpaque = false
        /*
         * Let the HTML inherit the component font, so sizes track the IDE's font settings and DPI
         * instead of being hardcoded in the stylesheet.
         */
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.regular()
        border = JBUI.Borders.empty()
        addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val target = e.url?.toString() ?: e.description
            if (!target.isNullOrBlank()) BrowserUtil.browse(target)
        }
        /*
         * Wrapped text can't report a height until it has a width, and nothing else re-asks once the
         * column hands it one. Same problem WrappingText solves for itself.
         */
        addComponentListener(object : ComponentAdapter() {
            private var measuredAt = -1
            override fun componentResized(e: ComponentEvent) {
                if (width == measuredAt) return
                measuredAt = width
                revalidate()
            }
        })
    }

    /**
     * Renders an `<object>` element as nothing at all.
     *
     * Swing's HTML support still honours the tag, by instantiating whatever component class its `classid`
     * names and calling setters from its parameters — markup no comment has any business carrying, and
     * comments are written by anyone with access to the issue. Replacing the view before it is attached
     * means the component is never created.
     */
    private fun withoutEmbeddedComponents(element: Element, view: View): View? =
        if (view is ObjectView) EmptyView(element) else null

    /** A view that takes no space and paints nothing. */
    private class EmptyView(element: Element) : View(element) {
        override fun getPreferredSpan(axis: Int): Float = 0f
        override fun paint(g: Graphics, allocation: Shape) = Unit
        override fun modelToView(pos: Int, a: Shape, b: Position.Bias): Shape = a
        override fun viewToModel(x: Float, y: Float, a: Shape, biasReturn: Array<Position.Bias>): Int = startOffset
    }

    /**
     * A comment's byline — avatar, author, date — in our own markup rather than [Comment.appendTo]'s,
     * whose default emits an unstyled `<hr><b>Author:</b>…` block we can't restyle. Empty when the
     * comment has none of the three.
     */
    private fun bylineHtml(comment: Comment): String {
        val byline = listOfNotNull(
            comment.author?.takeIf { it.isNotBlank() },
            // Exact: a "5 minutes ago" here would stay put while the comments are shown.
            comment.date?.let(::formatExactDate)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val avatar = avatarUrl(comment)
        if (avatar == null && byline.isEmpty()) return ""

        val sb = StringBuilder("<div class='byline'>")
        if (avatar != null) {
            val size = JBUI.scale(AVATAR_SIZE)
            sb.append("<img src='").append(esc(avatar))
                .append("' width='").append(size).append("' height='").append(size)
                .append("' align='middle'>&nbsp;")
        }
        return sb.append(esc(byline)).append("</div>").toString()
    }

    /**
     * A comment's text and reactions.
     *
     * The text is **not** escaped: trackers hand back rendered HTML here (GitHub's comment text is the
     * issue's `body_html`), which is also why the platform's own renderer passes it through untouched.
     * The description is the opposite case — raw markdown — which is part of why it lives outside these
     * panes entirely.
     */
    private fun bodyHtml(comment: Comment): String {
        /*
         * A tracker without an adapter hands us its connector's comment text as is, which for nearly all
         * of them — Redmine, Trello, Bugzilla, Mantis — is plain text, written by whoever commented. So
         * it is escaped, which also keeps any markup in it from reaching the pane, and its line breaks
         * become <br>. Adapters' comments are HTML already, rendered and sanitized by the tracker.
         */
        val text = comment.text.orEmpty().let { if (shownEditor == null) esc(it).replace("\n", "<br>") else it }
        val sb = StringBuilder("<div class='comment'>")
        sb.append("<div class='text'>").append(text).append("</div>")
        val reactions = (comment as? ReactableComment)?.reactions.orEmpty().filter { it.count > 0 }
        if (reactions.isNotEmpty()) {
            sb.append("<div class='reactions'>")
            for (reaction in reactions) {
                val style = if (reaction.reactedByMe) "reaction mine" else "reaction"
                sb.append("<span class='").append(style).append("'>")
                    .append(esc(reaction.emoji)).append("&nbsp;").append(reaction.count).append("</span>&nbsp;&nbsp; ")
            }
            sb.append("</div>")
        }
        return sb.append("</div>").toString()
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
        /*
         * The url comes out of an HTML attribute, so it is still entity-encoded; bylineHtml encodes it
         * again, and a url carrying "&amp;" would arrive at the server as "&amp;amp;".
         */
        return IMG_SRC.find(header)?.groupValues?.get(1)?.let(StringUtil::unescapeXmlEntities)
    }

    /**
     * Sizes are relative keywords, not point values, so the body follows each [htmlPane]'s font. Colors
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
            .reactions { font-size: smaller; color: $muted; margin-top: 2px; margin-bottom: 0; }
            .mine { color: $link; font-weight: bold; }
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
            /*
             * Breathing room around the 16px glyph, so the highlight reads as a button rather than as a
             * box drawn tight around the icon. InplaceButton centers its icon in whatever size the
             * component ends up with, so growing it is all that is needed.
             */
            val pad = JBUI.scale(6)
            preferredSize = Dimension(icon.iconWidth + pad, icon.iconHeight + pad)
            /*
             * Routed through setEnabled rather than assigning the cursor here, so the starting state and
             * every later change come from one place.
             */
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
            /*
             * While typing, the field keeps the whole column: a box creeping wider a character at a time
             * would be more distracting than one that simply looks like a field from the start.
             */
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
            isFocusable = true // see parkFocus
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = r.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    /** A caption above its value — the shape used for the Created / Updated columns. */
    /**
     * Redraws the shown task's dates, which may read "5 minutes ago" and so age; the exact date and time is
     * in their tooltip.
     */
    fun refreshDates() {
        val task = shownTask ?: return
        createdField.setValue(formatDate(task.created), tooltip = formatExactDate(task.created))
        updatedField.setValue(formatDate(task.updated), tooltip = formatExactDate(task.updated))
    }

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

        fun setValue(text: String, icon: Icon? = null, tooltip: String? = null) {
            val known = text.isNotBlank()
            value.text = if (known) text else "—"
            value.icon = icon.takeIf { known }
            value.toolTipText = tooltip.takeIf { known && it != text }
            value.foreground = if (known) UIUtil.getLabelForeground() else muted()
        }
    }

    /**
     * The editing half of an in-place edit: a text area in edit mode with Save and Cancel under it, and
     * Esc to cancel. Shared by the description and every comment, so the two edit alike.
     *
     * Enter is left to insert a newline, since both hold multi-line text.
     */
    private inner class InlineEditor(
        textFont: Font,
        onSave: (String) -> Unit,
        onCancel: () -> Unit,
    ) : JPanel(BorderLayout()) {

        private val textArea = WrappingText(textFont).apply { setEditing(true) }

        init {
            isOpaque = false
            add(textArea, BorderLayout.CENTER)
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(JButton("Save").apply { font = JBFont.small(); addActionListener { onSave(textArea.text) } })
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(JButton("Cancel").apply { font = JBFont.small(); addActionListener { onCancel() } })
                    add(Box.createHorizontalGlue())
                },
                BorderLayout.SOUTH,
            )
            bindKey(textArea, KeyEvent.VK_ESCAPE, "cancelEdit") { onCancel() }
        }

        /** Fills the editor with [text] and gives it the focus. */
        fun start(text: String) {
            textArea.text = text
            textArea.requestFocusInWindow()
        }
    }

    /**
     * One comment: a byline row, then its text and reactions. While it is being edited an
     * [InlineEditor] takes the text's place, under a byline that stays put.
     *
     * The byline is a pane of its own, sized to its content, so the pen can be a real [PenButton] tucked
     * in after it — with the hover highlight and the full click target the title's and description's
     * pens have, neither of which an icon drawn inside the HTML could offer.
     *
     * A reply is indented to start where its parent's text does, past the avatar, so the thread reads
     * as one block — capped, so a long chain of replies can't push the text out of a narrow pane.
     */
    private inner class CommentView(val comment: Comment, depth: Int) : JPanel(BorderLayout()) {

        private val body = htmlPane().apply { setHtml(this, bodyHtml(comment)) }

        val editor = InlineEditor(JBFont.regular(), { commitCommentEdit(this, it) }, ::cancelCommentEdit).apply {
            // The gap a comment's text leaves below itself, kept while the editor stands in for it.
            border = JBUI.Borders.emptyBottom(14)
        }

        init {
            isOpaque = false
            val indent = JBUI.scale(AVATAR_SIZE + REPLY_INDENT_GAP) * depth.coerceAtMost(MAX_REPLY_DEPTH)
            border = JBUI.Borders.emptyLeft(indent)

            // FlowLayout centres each component in the row's height, so the pen lines up with the avatar and text.
            val bylineRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
            bylineHtml(comment).takeIf { it.isNotEmpty() }?.let { html ->
                bylineRow.add(htmlPane().apply { setHtml(this, html) })
            }
            val task = shownTask
            if (task != null && shownEditor?.canEditComment(task, comment) == true) {
                bylineRow.add(Box.createHorizontalStrut(JBUI.scale(4)))
                bylineRow.add(PenButton("Edit comment") { beginCommentEdit(this) }.apply { setState(true, "") })
            }
            if (bylineRow.componentCount > 0) add(bylineRow, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

        fun setEditing(editing: Boolean) {
            remove(if (editing) body else editor)
            add(if (editing) editor else body, BorderLayout.CENTER)
            revalidate()
            repaint()
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
            /*
             * Turning editable off leaves a focused text area exactly as it was: caret blinking and any
             * selection still painted, since DefaultCaret only consults isEditable when focus arrives.
             * Read mode should look like a label, so drop both here rather than wait for focus to move.
             */
            if (!editing) select(0, 0)
            caret.isVisible = editing && isFocusOwner
            revalidate()
            repaint()
        }

        /**
         * Read mode keeps its caret hidden even when it gains the focus — which it does on purpose, as the
         * place a cancelled edit parks the focus. The platform's caret shows itself on focus whether or not
         * the text is editable, so it is put away again once every listener, the caret's own included, has
         * had the event.
         */
        override fun processFocusEvent(e: FocusEvent) {
            super.processFocusEvent(e)
            if (e.id == FocusEvent.FOCUS_GAINED && !isEditable) caret.isVisible = false
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
            /*
             * A couple of pixels of slack: FontMetrics rounds per string, and rounding short by one would
             * wrap the last word onto a line of its own.
             */
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

        /** Added to [AVATAR_SIZE] for a reply's indent: the non-breaking space after the avatar, roughly. */
        const val REPLY_INDENT_GAP = 4

        /** Registered by the bundled GitLab plugin; opens its Merge Requests tool window. */
        const val GITLAB_MERGE_REQUESTS_ACTION = "GitLab.Merge.Request.Show.List"

        /** Replies deeper than this stop indenting further. */
        const val MAX_REPLY_DEPTH = 3

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

/**
 * Orders comments so each reply follows the comment it answers, paired with its depth in the thread.
 *
 * Top-level comments keep the order the tracker gave them, and so do the replies under each one.
 * A reply whose parent isn't in the list, or that points at itself, is treated as top-level. Should
 * the parent links ever form a loop, the comments caught in it are appended at the end, unindented,
 * rather than dropped.
 */
internal fun threadComments(comments: List<Comment>): List<Pair<Comment, Int>> {
    val ids = comments.mapNotNullTo(HashSet()) { (it as? ThreadedComment)?.commentId }
    fun parentOf(comment: Comment): String? =
        (comment as? ThreadedComment)?.takeIf { it.parentId != it.commentId }?.parentId?.takeIf { it in ids }

    val replies = comments.groupBy(::parentOf)
    val ordered = ArrayList<Pair<Comment, Int>>(comments.size)
    val visited = HashSet<Comment>()
    fun visit(comment: Comment, depth: Int) {
        if (!visited.add(comment)) return
        ordered += comment to depth
        val id = (comment as? ThreadedComment)?.commentId ?: return
        replies[id]?.forEach { visit(it, depth + 1) }
    }
    replies[null]?.forEach { visit(it, 0) }
    comments.forEach { visit(it, 0) }
    return ordered
}
