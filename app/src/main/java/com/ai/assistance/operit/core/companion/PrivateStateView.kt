package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** User-facing projection only. The archive/model retain their original private evidence;
 * opening a source or technical fold must not publish the old dream/companion_note audience. */
internal object PrivateStateView {
    private val privateKinds = setOf("dream", "dream_understanding")
    fun project(value: JSONObject): JSONObject = scrub(value) as JSONObject

    private fun hidden(value: JSONObject): JSONObject = JSONObject().put("private_original_omitted", true)
        .put("visibility_notice", "原始私密内容保留原可见范围，未在此公开。")
        .also { out -> listOf("id", "version", "at", "type", "storage_kind", "status").forEach { if (value.has(it)) out.put(it, value.get(it)) } }

    private fun scrub(value: Any, field: String = ""): Any = when (value) {
        is JSONArray -> JSONArray((0 until value.length()).map { scrub(value.get(it)) })
        is JSONObject -> {
            val locator = value.optJSONObject("locator")
            if (value.optString("storage_kind") in privateKinds ||
                value.optString("type") in setOf("dream_not_reality", "subject_dream_understanding") ||
                locator?.optString("kind") in privateKinds) hidden(value)
            else {
                val request = value.optJSONObject("request")
                val parameters = request?.optJSONArray("parameters")
                // Old action heads omit parameters and may contain a truncated private
                // receipt. Open the full action to establish its section before display.
                val privateRead = request?.optString("tool") == "companion_internal_state" &&
                    (parameters == null || (0 until parameters.length()).any { i ->
                        val p = requireNotNull(parameters).getJSONObject(i)
                        p.optString("name") == "section" && p.optString("value") in privateKinds
                    })
                // Activity receipts are another projection of the same real operation.
                // Old records lack the section: keep the receipt private until a full
                // action read can establish its audience. Never change stored evidence.
                val activityPrivateRead = value.optString("actual_tool", value.optString("tool")) == "companion_internal_state" &&
                    (value.isNull("private_section") || value.optString("private_section") in privateKinds)
                JSONObject().also { out -> value.keys().forEach { key ->
                    val item = value.get(key)
                    out.put(key, if ((privateRead || activityPrivateRead) && key == "receipt") hidden(if (item is JSONObject) item else JSONObject()) else scrub(item, key))
                } }
            }
        }
        is String -> {
            // Typed tool data may be stored as a JSON string. Decode only valid containers;
            // ordinary prose remains exact, except the explicitly private output tag.
            val trimmed = value.trimStart()
            val nested = if (field !in setOf("text", "data", "inner_source", "detail", "receipt")) null
                else if (trimmed.startsWith("{")) runCatching { JSONObject(value) }.getOrNull()
                else if (trimmed.startsWith("[")) runCatching { JSONArray(value) }.getOrNull() else null
            if (nested != null) scrub(nested).let { if (it.toString() == nested.toString()) value else it.toString() }
            else if (field in setOf("text", "data", "inner_source", "detail", "receipt")) value.replace(Regex("<companion_note(?:\\s[^>]*)?>[\\s\\S]*?(?:</companion_note>|$)"), "〔原私密随记未公开〕")
            else value
        }
        else -> value
    }
}
