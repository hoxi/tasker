package net.tagpad.tasker.sort.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import net.tagpad.tasker.sort.SortModel
import net.tagpad.tasker.sort.SortRule
import net.tagpad.tasker.sort.SortRuleChoice
import net.tagpad.tasker.sort.SortSpec
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * The sort popup: one row per rule, in order — drag handle, place, what to sort by, how, remove — then
 * "+ Add sort", and Reset, Restore and Done at the foot. Done keeps the sort as the model's saved one, and
 * Restore goes back to it — after a stray header click, say.
 *
 * It works entirely through [SortModel]: it shows the model's spec, writes every change straight back to it
 * (the sort applies live, as in Notion or Airtable), and follows changes made elsewhere while it is open, such
 * as a header click. What can be added comes from [SortModel.choices], and each rule's own controls from
 * [RuleEditors] — so neither the fields nor the kinds of rule are known here.
 */
class SortPopup(private val model: SortModel) {

    private val rows = ReorderPanel(VerticalLayout(6))

    private val emptyHint = JBLabel("Sorted in each server's own order.").apply {
        foreground = NamedColorUtil.getInactiveTextColor()
    }

    private val addLink = ActionLink("Add sort") { chooseRuleToAdd() }.apply { setIcon(AllIcons.General.Add, false) }

    private val restoreLink = ActionLink("Restore") { model.saved?.let { apply(it) } }.apply {
        toolTipText = "Go back to the sort as it was when Done was last pressed"
    }

    private var popup: JBPopup? = null

    /** Set while this popup writes to the model, so it doesn't redraw for its own change. */
    private var applying = false

    fun show(anchor: JComponent) {
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content(), addLink)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()
        popup = created
        // Follows changes made by the other sort controls while open, such as a header click.
        model.addListener(created) { spec ->
            if (!applying && spec != rulesShown()) render(spec) else updateRestore()
        }
        created.showUnderneathOf(anchor)
    }

    private fun content(): JComponent {
        render(model.spec)
        val header = JBLabel("Sort tasks").apply { font = JBFont.label().asBold() }
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.emptyTop(8))
            add(
                JBLabel("Ties keep the servers' order. Shift+click a column header to add it.").apply {
                    font = JBFont.small()
                    foreground = NamedColorUtil.getInactiveTextColor()
                },
                BorderLayout.WEST,
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(ActionLink("Reset") { apply(SortSpec()) })
                    add(restoreLink)
                    add(JButton("Done").apply {
                        toolTipText = "Keep this sort, to restore later"
                        addActionListener {
                            this@SortPopup.model.save()
                            popup?.cancel()
                        }
                    })
                },
                BorderLayout.EAST,
            )
        }
        return JPanel(VerticalLayout(10)).apply {
            border = JBUI.Borders.empty(10, 12)
            add(header)
            add(rows)
            add(addLink)
            add(footer)
        }
    }

    /** Rebuilds the rows from [spec]. */
    private fun render(spec: SortSpec) {
        rows.removeAll()
        if (spec.isEmpty) rows.add(emptyHint)
        spec.rules.forEach { rows.add(RuleRow(it, spec)) }
        addLink.isVisible = unusedChoices(spec).isNotEmpty()
        updateRestore()
        rows.revalidate()
        rows.repaint()
        popup?.pack(true, true)
    }

    /** The spec as the rows on screen describe it, in their on-screen order. */
    private fun rulesShown(): SortSpec = SortSpec(rows.components.filterIsInstance<RuleRow>().map { it.rule })

    /** Writes [spec] to the model, which applies it at once, and redraws the rows if their shape changed. */
    private fun apply(spec: SortSpec, rerender: Boolean = true) {
        applying = true
        try {
            model.update(spec)
        } finally {
            applying = false
        }
        if (rerender) render(spec) else updateRestore()
    }

    /** Restore is offered only when there is a saved sort to go back to, other than the one shown. */
    private fun updateRestore() {
        val saved = model.saved
        restoreLink.isEnabled = saved != null && saved != model.spec
    }

    private fun unusedChoices(spec: SortSpec): List<SortRuleChoice> = model.choices.filterNot { it.isUsed(spec) }

    private fun chooseRuleToAdd() {
        val choices = unusedChoices(rulesShown())
        if (choices.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setRenderer(ChoiceRenderer())
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { apply(SortSpec(rulesShown().rules + it.create())) }
            .createPopup()
            .showUnderneathOf(addLink)
    }

    /** The choice a rule was made from: the one that, given just this rule, counts as used. */
    private fun choiceFor(rule: SortRule): SortRuleChoice? =
        model.choices.firstOrNull { it.isUsed(SortSpec(listOf(rule))) }

    /** Renumbers the rows once one has been dropped somewhere new, and applies their new order. */
    private fun rowsReordered() {
        rows.components.filterIsInstance<RuleRow>().forEachIndexed { index, row -> row.setPlace(index + 1) }
        apply(rulesShown(), rerender = false)
    }

    /**
     * One rule: drag handle, its place in the order, what it sorts by — swapping that replaces the rule
     * with a fresh one of the new kind — the rule's own controls, and remove. Alt+↑/↓ moves it by keyboard.
     * [spec] is the whole sort it is drawn as part of, which decides what else it could sort by.
     */
    private inner class RuleRow(var rule: SortRule, spec: SortSpec) : JPanel(BorderLayout(JBUI.scale(6), 0)) {

        private val place = JBLabel().apply {
            foreground = NamedColorUtil.getInactiveTextColor()
            font = JBFont.small()
        }

        init {
            isOpaque = false
            /*
             * The handle and the number both grab the row, padded out to a comfortable target: a bare 16px
             * icon was too small to catch reliably.
             */
            val handle = JBLabel(AllIcons.General.Drag).apply {
                toolTipText = "Drag to reorder (Alt+↑/↓)"
                border = JBUI.Borders.empty(4, 2)
            }
            for (grip in listOf(handle, place)) DragReorder.install(grip, this) { rowsReordered() }

            val leading = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(handle)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(place)
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(choiceBox(spec))
            }
            val editor = RuleEditors.create(rule, model) { changed ->
                rule = changed
                apply(rulesShown(), rerender = false)
            }
            val remove = InplaceButton("Remove", AllIcons.Actions.Close, ActionListener {
                apply(SortSpec(rulesShown().rules.filterNot { it === rule }))
            })

            add(leading, BorderLayout.WEST)
            add(editor.component, BorderLayout.CENTER)
            add(remove, BorderLayout.EAST)
            setPlace(rows.componentCount + 1)
            bindMove(KeyEvent.VK_UP, -1)
            bindMove(KeyEvent.VK_DOWN, 1)
        }

        fun setPlace(position: Int) {
            place.text = position.toString()
        }

        /** What this row sorts by: its own choice, plus every one no other row uses. */
        private fun choiceBox(spec: SortSpec): JComponent {
            val own = choiceFor(rule)
            val others = SortSpec(spec.rules.filterNot { it === rule })
            val available = model.choices.filter { it === own || !it.isUsed(others) }
            return ComboBox(available.toTypedArray()).apply {
                renderer = ChoiceRenderer()
                selectedItem = own
                addActionListener {
                    val chosen = selectedItem as? SortRuleChoice ?: return@addActionListener
                    if (chosen === own) return@addActionListener
                    val fresh = chosen.create()
                    apply(SortSpec(rulesShown().rules.map { if (it === rule) fresh else it }))
                }
            }
        }

        private fun bindMove(key: Int, by: Int) {
            val name = "moveRule$by"
            val stroke = KeyStroke.getKeyStroke(key, KeyEvent.ALT_DOWN_MASK)
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val current = rulesShown().rules
                    val from = current.indexOfFirst { it === rule }
                    val to = from + by
                    if (from < 0 || to !in current.indices) return
                    apply(SortSpec(current.toMutableList().apply { add(to, removeAt(from)) }))
                }
            })
        }
    }

    /** A choice by its label, with a note on the ones only some trackers report. */
    private class ChoiceRenderer : ColoredListCellRenderer<SortRuleChoice>() {
        override fun customizeCellRenderer(
            list: JList<out SortRuleChoice>,
            value: SortRuleChoice?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            append(value.label)
            if (!value.allServers) append("  some servers only", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
