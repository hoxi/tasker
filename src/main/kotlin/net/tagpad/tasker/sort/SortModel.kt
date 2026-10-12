package net.tagpad.tasker.sort

import com.intellij.openapi.Disposable
import com.intellij.util.EventDispatcher
import java.util.EventListener

/**
 * The current sort, as every sort control sees it — the column headers, the sort popup, and whatever comes
 * next. Controls read [spec], replace it through [update], and hear about each other's changes through
 * [addListener]; none of them knows the others exist, or what the sort is applied to.
 *
 * [choices] is what can be added to a sort; [people] the names an assignee rule can offer. Both describe the
 * data, so the owner of the data supplies them.
 */
interface SortModel {

    val spec: SortSpec

    /** Replaces the sort. Listeners are told only when [spec] actually changes. */
    fun update(spec: SortSpec)

    /** Calls [listener] with each new spec, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: (SortSpec) -> Unit)

    /** The rules that can be added, in the order to offer them. */
    val choices: List<SortRuleChoice>

    /** Names seen among the rows' assignees, for an assignee rule to suggest. */
    fun people(): List<String>
}

/**
 * Something that can be added to a sort: [label] to offer it under, [allServers] false when only some trackers
 * report it, and [create] for the rule it starts as. [isUsed] tells whether [spec] already has it, so a
 * control can leave it out.
 */
class SortRuleChoice(
    val label: String,
    val allServers: Boolean,
    val isUsed: (SortSpec) -> Boolean,
    val create: () -> SortRule,
) {
    companion object {

        /** A choice per field, starting ascending — except dates, which nearly always want the newest first. */
        fun forField(field: SortField<*>): SortRuleChoice = SortRuleChoice(
            label = field.displayName,
            allServers = field.allServers,
            isUsed = { spec -> spec.rules.any { it is FieldSortRule && it.field === field } },
            create = {
                val direction = if (field.kind == FieldKind.DATE) SortDirection.DESCENDING else SortDirection.ASCENDING
                FieldSortRule(field, direction)
            },
        )

        /** The assignee ranking, starting as "my issues first". */
        val ASSIGNEE: SortRuleChoice = SortRuleChoice(
            label = "Assignee",
            allServers = false,
            isUsed = { spec -> spec.rules.any { it is AssigneeSortRule } },
            create = { AssigneeSortRule(listOf(PersonMatcher.Me)) },
        )

        /** Every field, then the assignee ranking: what every server reports first, then what only some do. */
        val DEFAULT: List<SortRuleChoice> =
            SortFields.ALL.filter { it.allServers }.map(::forField) +
                ASSIGNEE +
                SortFields.ALL.filterNot { it.allServers }.map(::forField)
    }
}

/**
 * A [SortModel] that keeps the spec in memory and hands every change to [persist] as well as to listeners.
 * [peopleSource] is asked afresh each time, since the rows it reads change.
 */
class DefaultSortModel(
    initial: SortSpec,
    override val choices: List<SortRuleChoice> = SortRuleChoice.DEFAULT,
    private val persist: (SortSpec) -> Unit = {},
    private val peopleSource: () -> List<String> = { emptyList() },
) : SortModel {

    private val listeners = EventDispatcher.create(SpecListener::class.java)

    override var spec: SortSpec = initial
        private set

    override fun update(spec: SortSpec) {
        if (spec == this.spec) return
        this.spec = spec
        persist(spec)
        listeners.multicaster.specChanged(spec)
    }

    override fun addListener(parent: Disposable, listener: (SortSpec) -> Unit) {
        listeners.addListener(SpecListener(listener), parent)
    }

    override fun people(): List<String> = peopleSource()

    fun interface SpecListener : EventListener {
        fun specChanged(spec: SortSpec)
    }
}
