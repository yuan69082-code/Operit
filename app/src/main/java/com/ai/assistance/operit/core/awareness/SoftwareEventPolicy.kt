package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object SoftwareEventPolicy {
    fun mayRead(enabled: Boolean, observer: String?, caller: String?, owner: String?, group: String?): Boolean =
        enabled && !observer.isNullOrBlank() && observer == caller && caller == owner && group.isNullOrBlank()

    fun rows(data: JSONObject): List<JSONObject> = data.optJSONArray("events")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }.orEmpty()

    fun claim(data: JSONObject, now: Long, interval: Long): JSONObject? {
        if (data.has("last_wake") && now - data.optLong("last_wake") in 0 until interval) return null
        val pending = rows(data).filter { it.optString("dispatch") == "pending" }
        if (pending.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        pending.forEach { it.put("dispatch", "running").put("batch", id) }
        data.put("last_wake", now)
        return JSONObject().put("id", id).put("count", pending.size)
            .put("first_sequence", pending.first().getLong("sequence"))
            .put("last_sequence", pending.last().getLong("sequence"))
            .put("recent", JSONArray(pending.takeLast(8).map(::brief)))
    }

    fun brief(row: JSONObject): JSONObject = JSONObject().put("sequence", row.getLong("sequence"))
        .put("at", row.getLong("at")).put("kind", row.getString("kind"))
        .put("summary", row.getString("summary")).put("actor", row.getString("actor"))
}
