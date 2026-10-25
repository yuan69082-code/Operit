package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Claim once before any model/tool work. Failed or interrupted batches are not replayed. */
object PhoneEventQueue {
    const val SHORT_VISIT_MS = 5000L

    fun claim(data: JSONObject, now: Long, interval: Long, urgentWakes: Boolean = true): JSONObject? {
        val rows = data.optJSONArray("events") ?: return null
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }
        val pending = all.filter { it.optString("dispatch") == "pending" }
        if (pending.isEmpty()) return null
        val hasUrgent = urgentWakes && pending.any { it.optBoolean("urgent") }
        if (!hasUrgent && data.has("last_wake") && now - data.optLong("last_wake") in 0L until interval) return null
        pending.filter { now - it.optLong("at") !in 0..maxOf(300_000L, interval + 120_000L) }.forEach { it.put("dispatch", "expired") }
        // Keep raw intervals exact. Short visits are bundled into a later batch,
        // rather than waking on a permission dialog or charging its time to another app.
        pending.filter { it.optString("dispatch") == "pending" && it.optString("kind") == "app_enter" }.forEach { entry ->
            val leave = leaveFor(all, entry)
            if (leave != null && leave.optLong("duration_ms") < SHORT_VISIT_MS) entry.put("dispatch", "coalesced")
        }
        val fresh = pending.filter {
            it.optString("dispatch") == "pending" &&
                (it.optString("kind") != "app_enter" || now - it.optLong("at") >= SHORT_VISIT_MS)
        }
        if (fresh.isEmpty()) return null
        val brief = all.filter { it.optString("dispatch") == "coalesced" }
        val completed = all.filter { it.optString("dispatch") == "recorded" && it.optString("kind") == "app_leave" }
        val id = UUID.randomUUID().toString()
        (fresh + brief + completed).forEach { it.put("dispatch", "running").put("batch", id) }
        data.put("last_wake", now)
        return JSONObject().put("id", id).put("count", fresh.size).put("observations", JSONArray(fresh.takeLast(10).map { entry ->
            observation(entry).apply {
                if (entry.optString("kind") == "app_enter") {
                    all.lastOrNull { it.optString("kind") == "app_leave" && it.optLong("at") == entry.optLong("at") }
                        ?.let { put("previous_app", observation(it)) }
                }
            }
        })).put("brief_visit_count", brief.size)
            .put("completed_visit_count", completed.size)
            .put("completed_visits", JSONArray(completed.takeLast(10).map { observation(it).put("brief", it.optLong("duration_ms") < SHORT_VISIT_MS) }))
    }

    private fun leaveFor(rows: List<JSONObject>, entry: JSONObject): JSONObject? = rows.firstOrNull {
        it.optString("kind") == "app_leave" && it.optString("package") == entry.optString("package") &&
            it.has("entered_at") && it.optLong("entered_at") == entry.optLong("at")
    }

    private fun observation(row: JSONObject) = JSONObject().put("kind", row.optString("kind")).put("at", row.optLong("at"))
        .put("package", row.optString("package")).put("app_name", row.optString("app_name")).apply {
            if (row.has("entered_at")) put("entered_at", row.getLong("entered_at"))
            if (row.has("duration_ms")) put("duration_ms", row.getLong("duration_ms"))
        }
}
