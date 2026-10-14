package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Claim once before any model/tool work. Failed or interrupted batches are not replayed. */
object PhoneEventQueue {
    fun claim(data: JSONObject, now: Long, interval: Long): JSONObject? {
        val rows = data.optJSONArray("events") ?: return null
        val pending = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("dispatch") == "pending" }
        if (pending.isEmpty()) return null
        val hasUrgent = pending.any { it.optBoolean("urgent") }
        if (!hasUrgent && data.has("last_wake") && now - data.optLong("last_wake") in 0L until interval) return null
        pending.filter { now - it.optLong("at") !in 0..300_000 }.forEach { it.put("dispatch", "expired") }
        val fresh = pending.filter { it.optString("dispatch") == "pending" }
        if (fresh.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        fresh.forEach { it.put("dispatch", "running").put("batch", id) }
        data.put("last_wake", now)
        return JSONObject().put("id", id).put("count", fresh.size).put("observations", JSONArray(fresh.takeLast(10).map {
            JSONObject().put("kind", it.optString("kind")).put("at", it.optLong("at"))
                .put("package", it.optString("package")).put("app_name", it.optString("app_name"))
        }))
    }
}
