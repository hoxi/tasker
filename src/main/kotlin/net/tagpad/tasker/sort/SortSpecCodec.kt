package net.tagpad.tasker.sort

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Saves a [SortSpec] as JSON and reads it back.
 *
 * Each rule is an object with a "type" and whatever its [SortRuleCodec] writes. Reading is lenient on
 * purpose: a rule of an unknown type, a field that no longer exists, a malformed value — each is dropped on
 * its own, so a setting saved by a newer or older Tasker still restores everything it can.
 */
object SortSpecCodec {

    private val CODECS: List<SortRuleCodec<*>> = listOf(FieldRuleCodec, AssigneeRuleCodec)

    fun encode(spec: SortSpec): String =
        JsonArray().apply { spec.rules.mapNotNull(::encodeRule).forEach(::add) }.toString()

    fun decode(text: String?): SortSpec {
        if (text.isNullOrBlank()) return SortSpec()
        val array = runCatching { JsonParser.parseString(text).asJsonArray }.getOrNull() ?: return SortSpec()
        val rules = array.mapNotNull { element ->
            val json = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val type = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            val codec = CODECS.firstOrNull { it.type == type } ?: return@mapNotNull null
            runCatching { codec.read(json) }.getOrNull()
        }
        return SortSpec(rules)
    }

    private fun encodeRule(rule: SortRule): JsonObject? {
        val codec = CODECS.firstOrNull { it.handles(rule) } ?: return null
        return JsonObject().apply {
            addProperty("type", codec.type)
            codec.writeUnchecked(rule, this)
        }
    }
}

/** Saves and restores one kind of [SortRule]. [type] is stable: it is what a saved rule is recognised by. */
abstract class SortRuleCodec<R : SortRule>(val type: String, private val ruleClass: Class<R>) {

    fun handles(rule: SortRule): Boolean = ruleClass.isInstance(rule)

    internal fun writeUnchecked(rule: SortRule, json: JsonObject) = write(ruleClass.cast(rule), json)

    protected abstract fun write(rule: R, json: JsonObject)

    /** The rule [json] describes, or null when it no longer makes sense. */
    abstract fun read(json: JsonObject): R?
}

private object FieldRuleCodec : SortRuleCodec<FieldSortRule>("field", FieldSortRule::class.java) {

    override fun write(rule: FieldSortRule, json: JsonObject) {
        json.addProperty("field", rule.field.id)
        json.addProperty("direction", rule.direction.name)
    }

    override fun read(json: JsonObject): FieldSortRule? {
        val field = SortFields.byId(json.get("field")?.asString ?: return null) ?: return null
        val direction = SortDirection.valueOf(json.get("direction")?.asString ?: return null)
        return FieldSortRule(field, direction)
    }
}

private object AssigneeRuleCodec : SortRuleCodec<AssigneeSortRule>("assignee", AssigneeSortRule::class.java) {

    private const val ME = "@me"
    private const val UNASSIGNED = "@unassigned"
    private const val NAMED = "name:"

    override fun write(rule: AssigneeSortRule, json: JsonObject) {
        json.add("people", JsonArray().apply {
            rule.people.forEach { person ->
                add(
                    when (person) {
                        PersonMatcher.Me -> ME
                        PersonMatcher.Unassigned -> UNASSIGNED
                        is PersonMatcher.Named -> NAMED + person.name
                    },
                )
            }
        })
        json.addProperty("others", rule.others.name)
    }

    override fun read(json: JsonObject): AssigneeSortRule {
        val people = json.get("people")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                when (val token = element.takeIf { it.isJsonPrimitive }?.asString) {
                    ME -> PersonMatcher.Me
                    UNASSIGNED -> PersonMatcher.Unassigned
                    null -> null
                    else -> token.removePrefix(NAMED).takeIf { token.startsWith(NAMED) && it.isNotBlank() }
                        ?.let(PersonMatcher::Named)
                }
            }
            .orEmpty()
        val others = runCatching { OthersPlacement.valueOf(json.get("others").asString) }
            .getOrDefault(OthersPlacement.AFTER_BY_NAME)
        return AssigneeSortRule(people, others)
    }
}
