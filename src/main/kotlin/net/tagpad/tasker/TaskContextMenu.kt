package net.tagpad.tasker

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.tasks.CustomTaskState
import com.intellij.ui.GroupHeaderSeparator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * One row of the task context menu: switching to the task, a state it can move to, or an explanation of
 * why there is nothing to pick.
 *
 * Folding the "can't" cases into the list itself — unsupported provider, no states, failed fetch —
 * means a right-click always produces visible feedback rather than silently doing nothing.
 */
sealed interface TaskMenuItem {
    data class Switch(val label: String) : TaskMenuItem
    data class State(val state: CustomTaskState) : TaskMenuItem
    data class Message(val text: String) : TaskMenuItem
}

/**
 * Shows the task menu at [at]: switching to the task first, then the states it can move to under a
 * caption of their own.
 *
 * [currentStatus] is preselected when it matches one of the offered states, so the popup opens on where
 * the task is now. Picking a [TaskMenuItem.Message] does nothing.
 */
fun showTaskContextMenu(
    items: List<TaskMenuItem>,
    currentStatus: String?,
    at: RelativePoint,
    onSwitch: () -> Unit,
    onStateChosen: (CustomTaskState) -> Unit,
) {
    val builder = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(items)
        .setRenderer(TaskItemRenderer(items))
        .setItemChosenCallback { item ->
            when (item) {
                is TaskMenuItem.Switch -> onSwitch()
                is TaskMenuItem.State -> onStateChosen(item.state)
                is TaskMenuItem.Message -> Unit
            }
        }

    items.filterIsInstance<TaskMenuItem.State>()
        .firstOrNull { it.state.presentableName.equals(currentStatus, ignoreCase = true) }
        ?.let { builder.setSelectedValue(it, true) }

    builder.createPopup().show(at)
}

/**
 * Draws states as the same lozenge the list uses, so a status reads identically wherever it appears.
 *
 * The status half also gets a caption. The popup chooser builder has no notion of separators, so the
 * caption is drawn as part of the row it sits above — which is how the platform's own grouped list
 * popups do it too.
 */
private class TaskItemRenderer(items: List<TaskMenuItem>) : ListCellRenderer<TaskMenuItem> {

    /** The row the caption belongs above: the first non-switch one, as long as something precedes it. */
    private val captionIndex = items.indexOfFirst { it !is TaskMenuItem.Switch }.takeIf { it > 0 }

    private val plainRow = RowWidgets()

    /**
     * A second set of widgets for the captioned row. They cannot be shared with [plainRow]: a Swing
     * component has one parent, so the list would pull the same badge in and out of [captioned] as it
     * worked down the rows.
     */
    private val captionedRow = RowWidgets()

    /**
     * Opaque, and painted in the plain list background rather than the selected one.
     *
     * The caption shares a cell with the first status, and the popup list highlights a hovered cell
     * edge to edge. A see-through wrapper would let that highlight run up behind the caption, making it
     * look selectable; filling the strip ourselves stops the highlight at the row it belongs to. The
     * platform's own grouped renderer does the same thing.
     */
    private val captioned = JPanel(BorderLayout()).apply {
        isOpaque = true
        // Same left inset as the rows below, so the caption lines up with the text it introduces.
        add(GroupHeaderSeparator(JBUI.insets(6, 10)).apply { caption = "Set status" }, BorderLayout.NORTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out TaskMenuItem>,
        value: TaskMenuItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (index != captionIndex) return plainRow.render(value, list, isSelected)

        val body = captionedRow.render(value, list, isSelected)
        captioned.background = list.background
        val layout = captioned.layout as BorderLayout
        if (layout.getLayoutComponent(BorderLayout.CENTER) !== body) {
            layout.getLayoutComponent(BorderLayout.CENTER)?.let(captioned::remove)
            captioned.add(body, BorderLayout.CENTER)
        }
        return captioned
    }

    /** The three shapes a row can take. One instance renders one row at a time, as Swing renderers do. */
    private class RowWidgets {

        private val badge = StateBadge()

        private val message = JBLabel().apply {
            font = JBFont.small()
            foreground = NamedColorUtil.getInactiveTextColor()
            border = JBUI.Borders.empty(6, 10)
        }

        private val switchLabel = JBLabel().apply {
            border = JBUI.Borders.empty(6, 10)
        }

        fun render(value: TaskMenuItem, list: JList<out TaskMenuItem>, isSelected: Boolean): Component = when (value) {
            is TaskMenuItem.Switch -> switchLabel.also {
                it.text = value.label
                it.isOpaque = true
                it.background = if (isSelected) list.selectionBackground else list.background
                it.foreground = if (isSelected) list.selectionForeground else UIUtil.getLabelForeground()
            }
            is TaskMenuItem.State -> badge.also {
                it.render(value.state, if (isSelected) list.selectionBackground else list.background)
            }
            // Never highlighted: there is nothing here to choose.
            is TaskMenuItem.Message -> message.also {
                it.text = value.text
                it.isOpaque = true
                it.background = list.background
            }
        }
    }

    /** Exists to reach [StatusBadge.backdrop], which is protected, and to loosen the chip's row spacing. */
    private class StateBadge : StatusBadge() {

        fun render(state: CustomTaskState, background: Color) {
            setStatus(state.presentableName)
            backdrop = background
        }

        override fun getPreferredSize(): Dimension = super.getPreferredSize().also {
            it.width += JBUI.scale(12)
            it.height += JBUI.scale(8)
        }
    }
}
