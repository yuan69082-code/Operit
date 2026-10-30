package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** Select complete records for one input. Selection never changes a stored value or definition. */
object InternalStateSelection {
    const val DEFAULT_DETAIL_CHARACTERS = 8000

    data class Result(
        val payload: JSONObject,
        val decisions: JSONArray,
        val selectedIds: List<String>,
        val eventIds: Set<String>,
        val nextCursor: Long,
    )

    private data class Item(
        val key: String,
        val section: String,
        val value: JSONObject,
        val relevance: Int,
        val at: Long,
        val unresolved: Boolean,
        val ownAttention: Boolean,
        val rotating: Boolean,
    ) {
        val characters: Int = value.toString().length
        fun detail(reason: String): JSONObject = JSONObject(value.toString()).put("selection_reason", reason)
    }

    private val sections = listOf("states", "important_changes", "experiences", "candidates", "thoughts")

    /**
     * The complete short state catalogue is mandatory. Only full detail records compete for the
     * character budget; this is deliberately not presented as an exact model-token measurement.
     * All candidates are inspected before budgeting, including old todos and due thoughts.
     */
    fun select(
        data: JSONObject,
        chat: String,
        now: Long,
        currentInput: String,
        candidates: List<JSONObject>,
        thoughts: List<JSONObject>,
        currentEventId: String? = null,
        detailCharacters: Int = DEFAULT_DETAIL_CHARACTERS,
    ): Result {
        require(detailCharacters >= 0)
        // snapshot can instantiate an untouched dimension; preview/selection must remain pure.
        val view = JSONObject(data.toString())
        val states = InternalStateEngine.snapshot(view, chat, now)
        val byId = states.associateBy { it.getString("id") }
        val definitions = InternalStateEngine.definitions(view)
        val catalogue = JSONArray(definitions.map { definition ->
            JSONObject().put("id", definition.getString("id"))
                .put("name", definition.getString("name"))
                .put("kind", InternalStateEngine.kind(definition))
                .put("time_rules_status", if (InternalStateTimeRules.isDefined(definition)) "defined" else "undefined")
                .put("version", definition.getInt("version"))
                .put("min", definition.getDouble("min"))
                .put("max", definition.getDouble("max"))
                .put("scope", definition.getString("scope"))
                .put("active", definition.getBoolean("active"))
                .put("private", definition.getBoolean("private"))
                .put("share", definition.getBoolean("share"))
                .put("participates", definition.getBoolean("participates"))
                .also { identity ->
                    if (definition.has("object_ref")) identity.put("object_ref", definition.get("object_ref"))
                    val state = byId[definition.getString("id")]
                    state?.opt("object_ref")?.let { identity.put("object_ref", it) }
                    val objectDisclosure = state?.optJSONObject("disclosure")?.optJSONObject("fields")
                        ?.optJSONObject("object_ref")
                    if (identity.has("object_ref") && objectDisclosure != null) {
                        // The catalogue shows this one semantic field even without the full
                        // state. Keep its original privacy boundary without copying other fields.
                        identity.put("disclosure", JSONObject().put("fields", JSONObject()
                            .put("object_ref", JSONObject(objectDisclosure.toString()))))
                    }
                }
        })
        val query = terms(currentInput)
        fun relevance(value: JSONObject): Int = terms(searchable(value)).count { it in query }
        val items = mutableListOf<Item>()
        states.forEach { state ->
            val semanticAt = state.optLong("experience_at")
            items += Item("state:${state.getString("id")}", "states", JSONObject(state.toString()),
                relevance(state), semanticAt, hasUnresolved(state),
                state.getBoolean("participates") && (hasText(state, "desire") || hasText(state, "choice") ||
                    abs(state.getDouble("intensity")) > 1e-9), true)
        }
        val events = InternalStateEngine.rows(view, "events").filter { it.optLong("at") <= now }
        events.filter { it.getString("id") != currentEventId }.forEach { event ->
            val changes = InternalStateEngine.rows(event, "changes").filter {
                it.getString("scope") in setOf("global", chat)
            }
            val eventRelevance = relevance(event)
            if ((event.optString("chat") == chat || changes.isNotEmpty()) &&
                (changes.isNotEmpty() || eventRelevance > 0)) {
                val value = JSONObject(event.toString()).put("changes", JSONArray(changes))
                    .put("disclosure", InternalStateEngine.eventDisclosure(view, event))
                items += Item("event:${event.getString("id")}", "important_changes", value,
                    eventRelevance, event.getLong("at"), false, false, false)
            }
        }
        InternalStateEngine.rows(view, "experiences").filter {
            it.getString("scope") in setOf("global", chat) && it.getLong("at") <= now
        }.forEach { experience ->
            val current = byId[experience.getString("dimension")]
            val experienceEvent = InternalStateEngine.experienceEventId(experience)
            // Published model reports used frame_id; user edits have a real event ID and must
            // never acquire a fabricated model frame merely to fit the selection pipeline.
            val currentEvent = if (current?.has("experience_event_id") == true) {
                current.getString("experience_event_id")
            } else if (current?.has("report_frame_id") == true) {
                "state_report:${current.getString("report_frame_id")}"
            } else null
            val currentExperience = currentEvent == experienceEvent
            val value = JSONObject(experience.toString()).put("current_experience", currentExperience)
                .put("disclosure", InternalStateEngine.experienceDisclosure(view, experience))
            items += Item("experience:$experienceEvent:${experience.getString("dimension")}",
                "experiences", value, relevance(experience), experience.getLong("at"),
                currentExperience && hasUnresolved(experience),
                currentExperience && (hasText(experience, "desire") || hasText(experience, "choice")), false)
        }
        InternalStateEngine.rank(states, candidates).forEach { candidate ->
            val action = candidate.getString("action")
            val id = candidate.getString("id")
            items += Item("candidate:$action:$id", "candidates", candidate, relevance(candidate),
                candidate.optLong("updated"), hasUnresolved(candidate), candidate.getDouble("score") != 0.0,
                id != action || action !in InternalStateEngine.actions)
        }
        thoughts.forEach { thought ->
            val related = relevance(thought)
            // A directly relevant sunken thought may be reconsidered early. Dropped/already
            // spoken thoughts are history, not silently reactivated by the selector.
            if (thought.optString("state") == "sink" && (thought.optLong("surface_at") <= now || related > 0)) {
                items += Item("thought:${thought.getString("id")}", "thoughts", JSONObject(thought.toString()),
                    related, thought.optLong("updated"), false, true, true)
            }
        }
        require(items.map { it.key }.distinct().size == items.size) { "内部状态候选标识重复" }

        val selected = linkedMapOf<String, Pair<Item, String>>()
        var used = 0
        fun include(item: Item, reason: String): Boolean {
            if (item.key in selected) return false
            val characters = item.detail(reason).toString().length
            if (characters > detailCharacters - used) return false
            selected[item.key] = item to reason
            used += characters
            return true
        }
        val cursor = data.optLong("selection_cursor").coerceAtLeast(0)
        val rotating = items.filter { it.rotating }.sortedBy { it.key }
        var nextCursor = cursor
        if (rotating.isNotEmpty() && detailCharacters > 0) {
            val start = (cursor % rotating.size).toInt()
            val order = rotating.drop(start) + rotating.take(start)
            val position = order.indexOfFirst { it.detail("ROTATION").toString().length <= detailCharacters }
            if (position >= 0) {
                // One real opportunity is reserved before the relevance/recency queues can
                // fill the budget. High intensity or a repeated fresh concern cannot own it.
                include(order[position], "ROTATION")
                nextCursor = ((start + position + 1) % rotating.size).toLong()
            }
        }
        val ranked = compareByDescending<Item> { it.relevance }.thenByDescending { it.at }.thenBy { it.key }
        val lanes = listOf(
            "CURRENT_INPUT_RELEVANCE" to items.filter { it.relevance > 0 }.sortedWith(ranked),
            "UNRESOLVED" to items.filter { it.unresolved }.sortedWith(ranked),
            "RECENT_CHANGE" to items.filter { it.section in setOf("important_changes", "experiences") }
                .sortedWith(compareByDescending<Item> { it.at }.thenBy { it.key }),
            "OWN_ATTENTION" to items.filter { it.ownAttention }.sortedWith(ranked),
        )
        val positions = IntArray(lanes.size)
        var added: Boolean
        do {
            added = false
            lanes.forEachIndexed { index, (reason, lane) ->
                while (positions[index] < lane.size) {
                    val item = lane[positions[index]++]
                    if (include(item, reason)) { added = true; break }
                }
            }
        } while (added)
        items.sortedWith(ranked).forEach { include(it, "AVAILABLE_DETAIL") }

        val decisions = JSONArray(items.map { item ->
            JSONObject().put("id", item.key).put("selected", item.key in selected)
                .put("reason", selected[item.key]?.second ?: if (item.characters > detailCharacters)
                    "DETAIL_EXCEEDS_BUDGET" else "DETAIL_BUDGET")
                .put("characters", item.characters)
        })
        val payload = JSONObject().put("state_catalog", catalogue)
        sections.forEach { section ->
            payload.put(section, JSONArray(selected.values.filter { it.first.section == section }.map { (item, reason) ->
                item.detail(reason)
            }))
        }
        val omitted = items.filter { it.key !in selected }.groupingBy { it.section }.eachCount()
        payload.put("selection", JSONObject()
            .put("budget_unit", "characters_not_tokens")
            .put("detail_budget_characters", detailCharacters)
            .put("detail_budget_applies_to", "sum_of_selected_record_json_characters")
            .put("selected_record_characters", used)
            .put("catalogue_characters", catalogue.toString().length)
            .put("catalogue_complete", true)
            .put("candidate_count", items.size)
            .put("selected_count", selected.size)
            .put("omitted_counts", JSONObject(omitted))
            .put("omission_is_not_deletion", true))

        val availableEvents = events.map { it.getString("id") }.toSet()
        val evidence = linkedSetOf<String>()
        currentEventId?.let { require(it in availableEvents); evidence += it }
        selected.values.forEach { (item, _) ->
            if (item.section == "important_changes") evidence += item.value.getString("id")
            val references = item.value.optJSONArray("evidence")
            if (references != null) for (index in 0 until references.length()) {
                val ref = references.getString(index)
                if (ref in availableEvents) evidence += ref
            }
        }
        return Result(payload, decisions, selected.keys.toList(), evidence, nextCursor)
    }

    private fun hasText(value: JSONObject, key: String): Boolean =
        value.has(key) && !value.isNull(key) && value.opt(key).toString().isNotBlank() && value.opt(key) != false

    private fun hasUnresolved(value: JSONObject) = hasText(value, "unresolved") || hasText(value, "conflict")

    private fun searchable(value: JSONObject): String = listOf(
        "id", "name", "description", "detail", "title", "next", "progress", "text", "reason",
        "object_ref", "unresolved", "desire", "choice", "effects", "reasons",
    ).filter { value.has(it) && !value.isNull(it) }.joinToString(" ") { value.get(it).toString() }

    private fun terms(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val result = Regex("[a-z0-9_]+|[\\p{L}&&[^\\p{IsHan}]]+").findAll(lower)
            .map { it.value }.filter { it.length > 1 }.toMutableSet()
        Regex("[\\p{IsHan}]+").findAll(lower).forEach { match ->
            if (match.value.length == 1) result += match.value
            else match.value.windowed(2).forEach { result += it }
        }
        return result
    }
}
