package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.util.ChatUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** One budget for serialized subject material, independent of the model's full input window.
 * Providers currently expose a local estimate, not a preflight tokenizer. Retain that estimate
 * for comparability, charge non-ASCII non-Han text by UTF-8 bytes, and reserve 25% uncertainty.
 * This is not a guaranteed error bound for an arbitrary provider tokenizer.
 */
internal object SubjectMaterialBudget {
    const val DEFAULT_TOKENS = 2048
    const val MAX_TOKENS = 16384
    // Includes the fixed Internal State header, operit_state and request-context envelopes.
    const val ENVELOPE_RESERVE = 128
    fun estimate(text: String): Long {
        var extra = 0L
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (cp > 127 && cp !in 0x4e00..0x9fff) {
                extra += String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            }
            i += width
        }
        return ceil((ChatUtils.estimateTokenCount(text) + extra) * 1.25).toLong()
    }

    private data class Lane(val path: String, val target: JSONArray, val rows: List<Any>)
    /** Complete objects only. Selection changes no persisted state, focus or priority. */
    fun select(source: JSONObject, requested: Int = DEFAULT_TOKENS): JSONObject {
        require(requested in 1..MAX_TOKENS)
        val result = JSONObject(source.toString())
        val lanes = mutableListOf<Lane>()
        fun lane(parent: JSONObject, field: String, path: String) {
            val a = parent.optJSONArray(field) ?: return
            val empty = JSONArray()
            parent.put(field, empty)
            lanes += Lane(path, empty, (0 until a.length()).map { a.get(it) })
        }
        val inner = result.getJSONObject("inner_structure")
        // Identity gets the first opportunity; if it exceeds the budget it remains accessible
        // by ID, explicitly omitted, never silently replaced with the initial role template.
        lane(inner, "identity", "inner_structure.identity")
        lane(inner, "selected", "inner_structure.selected")
        lane(result.getJSONObject("category_events"), "episodes", "category_events.episodes")
        lane(result.getJSONObject("category_events"), "focus", "category_events.focus")
        lane(result.getJSONObject("unions"), "items", "unions.items")
        lane(result, "retrieved_materials", "retrieved_materials")
        lane(result, "host_materials", "host_materials")
        for (key in listOf("states", "important_changes", "experiences", "thoughts", "candidates", "state_catalog"))
            lane(result, key, key)
        for (key in listOf("pending", "catalogue", "pending_ids")) lane(inner, key, "inner_structure.$key")
        result.remove("selection")
        inner.put("selection", "Whole records selected under shared token estimate; omitted identity remains authoritative. Retrieve by ID before assuming its content.")
        val omitted = JSONObject()
        val budget = JSONObject().put("limit_tokens", requested).put("estimated_tokens", requested)
            .put("method", "host_estimate_utf8_non_han_plus_25_percent")
            .put("exact", false).put("error_bound", "unknown_for_custom_tokenizers")
            .put("omitted_counts", omitted).put("omission_is_not_deletion", true)
            .put("retrieve", "companion_internal_state get(offset) includes inner; section=episode/focus/union/source; material locator tools")
        result.put("material_budget", budget)
        lanes.forEach { omitted.put(it.path, it.rows.size) }
        // This envelope is small and bounded, regardless of the history volume.
        require(estimate(result.toString()) + ENVELOPE_RESERVE <= requested) { "主体材料预算不足以容纳来源与版本信息" }
        fun include(lane: Lane, row: Any) {
            lane.target.put(row)
            omitted.put(lane.path, lane.rows.size - lane.target.length())
            if (estimate(result.toString()) + ENVELOPE_RESERVE > requested) {
                lane.target.remove(lane.target.length() - 1)
                omitted.put(lane.path, lane.rows.size - lane.target.length())
            }
        }
        // A compact rotating directory remains discoverable even while large details compete.
        // It carries no psychological ranking. Reserving these IDs prevents detail selection
        // from permanently hiding the next page of existing records.
        val directory = lanes.firstOrNull { it.path == "inner_structure.catalogue" }
        directory?.rows?.forEach { include(directory, it) }
        // Identity has the first detail turn, not an unlimited exclusive allocation.
        // Rotate all other domains per request so emotion/identity cannot forever exclude
        // contrary material. This is technical exposure, never a change in subjective focus.
        val rest = lanes.drop(1).filter { it != directory }
        val cursor = source.optLong("material_selection_cursor").coerceAtLeast(0)
        val start = (cursor % rest.size.coerceAtLeast(1)).toInt()
        val order = listOf(lanes.first()) + rest.drop(start) + rest.take(start)
        for (index in 0 until (order.maxOfOrNull { it.rows.size } ?: 0))
            order.forEach { if (index < it.rows.size) include(it, it.rows[index]) }
        budget.put("estimated_tokens", estimate(result.toString()) + ENVELOPE_RESERVE)
        check(estimate(result.toString()) + ENVELOPE_RESERVE <= requested)
        return result
    }
}
