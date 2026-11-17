package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.util.ChatUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** One budget for serialized subject material, independent of the model's full input window.
 * Selection uses the shared estimate; supported local channels additionally verify the selected
 * envelope with their native tokenizer before sending. Charge non-ASCII non-Han text by UTF-8
 * bytes and reserve 25% uncertainty for channels without a matching local tokenizer.
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
        val priority = inner.optJSONArray("_relevance_first")?.let { ids ->
            (0 until minOf(ids.length(), 2)).map { ids.getString(it) }.distinct()
        }.orEmpty()
        inner.remove("_relevance_first")
        // Preserve the established rotation order. Installing vocabulary must not shift
        // which real adopted content, activity or event the next ordinary request receives.
        lane(inner, "identity", "inner_structure.identity")
        lane(inner, "selected", "inner_structure.selected")
        lane(result.getJSONObject("category_events"), "episodes", "category_events.episodes")
        lane(result.getJSONObject("category_events"), "focus", "category_events.focus")
        lane(result.getJSONObject("unions"), "items", "unions.items")
        lane(result, "retrieved_materials", "retrieved_materials")
        lane(result, "host_materials", "host_materials")
        for (key in listOf("states", "important_changes", "experiences", "thoughts", "candidates", "state_catalog"))
            lane(result, key, key)
        for (key in listOf("pending", "pending_fragments", "record_fragments", "pending_read", "catalogue", "pending_ids")) lane(inner, key, "inner_structure.$key")
        result.optJSONObject("foundation")?.let { foundation ->
            lane(foundation, "drive_values", "foundation.drive_values")
            lane(foundation, "available", "foundation.available")
            lane(foundation, "legacy_pending", "foundation.legacy_pending")
        }
        result.remove("selection")
        inner.put("selection", "Relevant records first; other material rotates. Omission preserves authority; retrieve by ID.")
        val omitted = JSONObject()
        val budget = JSONObject().put("limit_tokens", requested).put("estimated_tokens", requested)
            .put("method", "host_estimate_utf8_non_han_plus_25_percent")
            .put("exact", false).put("error_bound", "unknown_for_custom_tokenizers")
            .put("omitted_counts", omitted).put("omission_is_not_deletion", true)
            .put("retrieve", "companion_internal_state get section=inner/state/episode/focus/union/source id or offset; material locator tools")
        result.put("material_budget", budget)
        fun count(lane: Lane) {
            val missing = lane.rows.size - lane.target.length()
            // Zero-count entries are metadata, not subject material. Avoid spending the
            // same budget on a long empty directory while omitting a real saved episode.
            if (missing == 0) omitted.remove(lane.path) else omitted.put(lane.path, missing)
        }
        lanes.forEach(::count)
        // This envelope is small and bounded, regardless of the history volume.
        require(estimate(result.toString()) + ENVELOPE_RESERVE <= requested) { "主体材料预算不足以容纳来源与版本信息" }
        fun include(lane: Lane, row: Any) {
            lane.target.put(row)
            count(lane)
            if (estimate(result.toString()) + ENVELOPE_RESERVE > requested) {
                // A fragment is an explicit range of immutable text, not a shortened record.
                // Adapt its range to the shared remaining budget, including provenance metadata.
                if (lane.path in setOf("inner_structure.pending_fragments", "inner_structure.record_fragments") && row is JSONObject) {
                    val original = row.getString("text")
                    var low = 1; var high = original.length - 1; var best: JSONObject? = null
                    while (low <= high) {
                        val middle = (low + high) / 2
                        var length = middle
                        if (length < original.length && Character.isHighSurrogate(original[length - 1])) length--
                        val part = JSONObject(row.toString()).put("text", original.substring(0, length))
                            .put("end", row.getInt("offset") + length).put("next_offset", row.getInt("offset") + length)
                            .put("complete_record", false)
                        lane.target.put(lane.target.length() - 1, part)
                        if (length > 0 && estimate(result.toString()) + ENVELOPE_RESERVE <= requested) {
                            best = part; low = middle + 1
                        } else high = middle - 1
                    }
                    if (best != null) { lane.target.put(lane.target.length() - 1, best); return }
                }
                lane.target.remove(lane.target.length() - 1)
                count(lane)
            }
        }
        // Reserve an opportunity for the best matching identity and other adopted record before
        // catalogue/filler overhead. Only two rows qualify; other domains still rotate normally.
        val prioritized = mutableSetOf<Pair<String, Int>>()
        // The wake notice alone is not a versioned read. Deliver the one compact current
        // rest record before rotating history, otherwise a valid wake choice is rejected.
        lanes.firstOrNull { it.path == "retrieved_materials" }?.let { materials ->
            val index = materials.rows.indexOfFirst { it is JSONObject &&
                it.optString("type") == "subject_rest" && it.optBoolean("control_state") }
            if (index >= 0) {
                include(materials, materials.rows[index])
                prioritized += materials.path to index
            }
        }
        // Confirmed essence is a small, explicit choice inside the SAME budget. It is not
        // a second prompt appended after budgeting. Drafts and old versions never enter here.
        lanes.firstOrNull { it.path == "retrieved_materials" }?.let { materials ->
            materials.rows.forEachIndexed { index, row ->
                if (row is JSONObject && row.optString("type") in setOf("confirmed_essence", "private_time_plan")) {
                    include(materials, row)
                    prioritized += materials.path to index
                }
            }
        }
        // Keep one real current base drive in the normal request even when historical
        // catalogues fill the envelope. Rotate this bounded opportunity, not the subject's
        // importance or stored values. A vocabulary-only row never qualifies as a read.
        val cursor = source.optLong("material_selection_cursor").coerceAtLeast(0)
        lanes.firstOrNull { it.path == "foundation.drive_values" }?.let { drives ->
            if (drives.rows.isNotEmpty()) {
                val index = (cursor % drives.rows.size).toInt()
                include(drives, drives.rows[index])
                prioritized += drives.path to index
            }
        }
        for (id in priority) {
            val match = lanes.asSequence().filter { it.path in setOf("inner_structure.identity",
                "inner_structure.selected", "inner_structure.record_fragments") }.mapNotNull { lane ->
                val index = lane.rows.indexOfFirst { row -> row is JSONObject && !row.optBoolean("private_material_omitted") &&
                    (if (lane.path.endsWith("record_fragments")) row.optString("record_id") else row.optString("id")) == id }
                if (index >= 0) lane to index else null
            }.firstOrNull() ?: continue
            if (prioritized.add(match.first.path to match.second)) include(match.first, match.first.rows[match.second])
        }
        // One relevant adopted association/interpretation gets a bounded opportunity before
        // unrelated directories. It still competes inside the SAME budget, not an extra block.
        val relevantKinds = listOf("category_events.episodes" to "", "unions.items" to "", "retrieved_materials" to "adopted_feedback",
            "retrieved_materials" to "adopted_decision", "retrieved_materials" to "subject_experience",
            "retrieved_materials" to "subject_direction")
        val relevantStart = (cursor % relevantKinds.size).toInt()
        for ((path, type) in relevantKinds.drop(relevantStart) + relevantKinds.take(relevantStart)) {
            val lane = lanes.firstOrNull { it.path == path } ?: continue
            val index = lane.rows.indexOfFirst { row -> row is JSONObject &&
                row.optString("selection_reason") == "CURRENT_INPUT_RELEVANCE" && (type.isEmpty() || row.optString("type") == type) }
            if (index >= 0 && prioritized.add(path to index)) include(lane, lane.rows[index])
        }
        // Remaining space exposes the compact rotating directory before other details. It
        // carries no psychological ranking and continues to advance through existing records.
        val directory = lanes.firstOrNull { it.path == "inner_structure.catalogue" }
        directory?.rows?.forEach { include(directory, it) }
        // Rotate detail domains per request so emotion/identity cannot forever exclude
        // contrary material. This is technical exposure, never a change in subjective focus.
        val rest = lanes.filter { it != directory && !it.path.startsWith("foundation.") }
        val start = (cursor % rest.size.coerceAtLeast(1)).toInt()
        val order = rest.drop(start) + rest.take(start)
        for (index in 0 until (order.maxOfOrNull { it.rows.size } ?: 0))
            order.forEach { if (index < it.rows.size && (it.path to index) !in prioritized) include(it, it.rows[index]) }
        // Empty catalogue concepts must not displace actual relevant experience. Omitted
        // foundation values grant no read/adoption permission and remain retrievable by ID.
        val foundationLanes = lanes.filter { it.path.startsWith("foundation.") }
        for (index in 0 until (foundationLanes.maxOfOrNull { it.rows.size } ?: 0))
            foundationLanes.forEach { if (index < it.rows.size && (it.path to index) !in prioritized) include(it, it.rows[index]) }
        budget.put("estimated_tokens", estimate(result.toString()) + ENVELOPE_RESERVE)
        check(estimate(result.toString()) + ENVELOPE_RESERVE <= requested)
        return result
    }
}
