package net.tagpad.tasker.sort.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.WrapLayout
import net.tagpad.tasker.sort.AssigneeSortRule
import net.tagpad.tasker.sort.FieldSortRule
import net.tagpad.tasker.sort.OthersPlacement
import net.tagpad.tasker.sort.PersonMatcher
import net.tagpad.tasker.sort.SortDirection
import net.tagpad.tasker.sort.SortModel
import net.tagpad.tasker.sort.SortRule
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The part of a sort row that edits its rule: a direction for a field, a ranking of people for the assignee
 * rule. Each change hands a new rule to the row, which applies it.
 */
internal interface RuleEditor {
    val component: JComponent
}

/**
 * Picks the editor for a rule. A `when` over the sealed [SortRule], so a new kind of rule doesn't compile
 * until it has an editor here — the popup itself never needs to know which kinds exist.
 */
internal object RuleEditors {

    fun create(rule: SortRule, model: SortModel, onChange: (SortRule) -> Unit): RuleEditor = when (rule) {
        is FieldSortRule -> FieldRuleEditor(rule, onChange)
        is AssigneeSortRule -> AssigneeRuleEditor(rule, model, onChange)
    }
}

/** A direction, worded for the field: "Newest first", "A → Z", "Highest first". */
private class FieldRuleEditor(rule: FieldSortRule, onChange: (SortRule) -> Unit) : RuleEditor {

    override val component: JComponent = ComboBox(SortDirection.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { rule.field.kind.label(it) }
        selectedItem = rule.direction
        addActionListener {
            val direction = selectedItem as? SortDirection ?: return@addActionListener
            if (direction != rule.direction) onChange(rule.copy(direction = direction))
        }
    }
}

/**
 * The people to put first, as chips in rank order — dragged to reorder, × to remove, + to add — and where
 * everyone else goes. Names to add come from [SortModel.people], the assignees of the rows on display.
 */
private class AssigneeRuleEditor(
    private var rule: AssigneeSortRule,
    private val model: SortModel,
    private val onChange: (SortRule) -> Unit,
) : RuleEditor {

    private val chips = ReorderPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)))

    private val addButton = InplaceButton("Add a person", AllIcons.General.Add, ActionListener { choosePerson() })

    private val others = ComboBox(OthersPlacement.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it.label }
        selectedItem = rule.others
        addActionListener {
            val placement = selectedItem as? OthersPlacement ?: return@addActionListener
            if (placement != rule.others) apply(rule.copy(others = placement))
        }
    }

    override val component: JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(chips)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel("Everyone else:").apply { foreground = NamedColorUtil.getInactiveTextColor() })
            add(others)
        })
    }

    init {
        renderChips()
    }

    private fun apply(updated: AssigneeSortRule) {
        rule = updated
        onChange(updated)
    }

    private fun renderChips() {
        chips.removeAll()
        rule.people.forEach { chips.add(Chip(it)) }
        chips.add(addButton)
        chips.revalidate()
        chips.repaint()
    }

    /** The ranking as the chips now stand, once one has been dropped somewhere new. */
    private fun readChipOrder() {
        apply(rule.copy(people = chips.components.filterIsInstance<Chip>().map { it.person }))
    }

    private fun choosePerson() {
        val present = rule.people.toSet()
        val candidates = (listOf(PersonMatcher.Me, PersonMatcher.Unassigned) + model.people().map(PersonMatcher::Named))
            .filterNot { candidate -> present.any { it.sameAs(candidate) } }
        if (candidates.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(candidates)
            .setRenderer(textListCellRenderer { label(it) })
            .setNamerForFiltering { label(it) }
            .setItemChosenCallback { person ->
                apply(rule.copy(people = rule.people + person))
                renderChips()
            }
            .createPopup()
            .showUnderneathOf(addButton)
    }

    private inner class Chip(val person: PersonMatcher) : JPanel(BorderLayout(JBUI.scale(2), 0)) {
        init {
            isOpaque = false
            border = JBUI.Borders.compound(
                RoundedLineBorder(JBColor.border(), JBUI.scale(10)),
                JBUI.Borders.empty(1, 6, 1, 2),
            )
            val name = JBLabel(label(person)).apply { font = JBFont.small() }
            add(name, BorderLayout.CENTER)
            add(InplaceButton("Remove", AllIcons.Actions.Close, ActionListener {
                apply(rule.copy(people = rule.people.filterNot { it.sameAs(person) }))
                renderChips()
            }), BorderLayout.EAST)
            // The whole chip grabs, bar its remove button.
            for (grip in listOf(name, this)) DragReorder.install(grip, this) { readChipOrder() }
        }
    }

    private companion object {
        fun label(person: PersonMatcher): String = when (person) {
            PersonMatcher.Me -> "Me"
            PersonMatcher.Unassigned -> "Unassigned"
            is PersonMatcher.Named -> person.name
        }

        /** Names compare as the matcher does, ignoring case. */
        fun PersonMatcher.sameAs(other: PersonMatcher): Boolean =
            if (this is PersonMatcher.Named && other is PersonMatcher.Named) name.equals(other.name, ignoreCase = true)
            else this == other
    }
}
