package net.tagpad.tasker

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.tasks.CustomTaskState
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * One row of the status popup: either a state the task can move to, or an explanation of why there is
 * nothing to pick.
 *
 * Folding the "can't" cases into the list itself — unsupported provider, no states, failed fetch —
 * means a right-click always produces visible feedback rather than silently doing nothing.
 */
sealed interface StateMenuItem {
    data class State(val state: CustomTaskState) : StateMenuItem
    data class Message(val text: String) : StateMenuItem
}

/**
 * Shows the status chooser at [at]. [currentStatus] is preselected when it matches one of the offered
 * states, so the popup opens on where the task is now. Picking a [StateMenuItem.Message] does nothing.
 */
fun showTaskStatePopup(
    items: List<StateMenuItem>,
    currentStatus: String?,
    at: RelativePoint,
    onChosen: (CustomTaskState) -> Unit,
) {
    val builder = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(items)
        .setTitle("Set Status")
        .setRenderer(StateItemRenderer())
        .setItemChosenCallback { item -> (item as? StateMenuItem.State)?.let { onChosen(it.state) } }

    items.filterIsInstance<StateMenuItem.State>()
        .firstOrNull { it.state.presentableName.equals(currentStatus, ignoreCase = true) }
        ?.let { builder.setSelectedValue(it, true) }

    builder.createPopup().show(at)
}

/** Draws states as the same lozenge the list uses, so a status reads identically wherever it appears. */
private class StateItemRenderer : ListCellRenderer<StateMenuItem> {

    private val badge = StateBadge()

    private val message = JBLabel().apply {
        font = JBFont.small()
        foreground = NamedColorUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(6, 10)
    }

    override fun getListCellRendererComponent(
        list: JList<out StateMenuItem>,
        value: StateMenuItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component = when (value) {
        is StateMenuItem.State -> badge.also {
            it.render(value.state, if (isSelected) list.selectionBackground else list.background)
        }
        is StateMenuItem.Message -> message.also {
            it.text = value.text
            it.isOpaque = true
            it.background = list.background
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
