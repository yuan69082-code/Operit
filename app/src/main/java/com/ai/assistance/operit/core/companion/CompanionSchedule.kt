package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** An in-flight judgment is not an explicit request to pause future judgments. */
internal object CompanionSchedule {
    fun claim(data: JSONObject, ticket: String, now: Long) {
        data.put("running_ticket", ticket).put("started", now).put("paused", true)
    }

    fun supersede(data: JSONObject) {
        data.remove("running_ticket")
        data.remove("interruptions")
    }

    fun finish(data: JSONObject, ticket: String, completed: Boolean, now: Long) {
        if (data.optString("running_ticket") != ticket) return
        data.remove("running_ticket")
        if (completed) {
            data.remove("interruptions")
            return
        }
        val attempts = (data.optInt("interruptions") + 1).coerceIn(1, 5)
        val delay = (60_000L shl (attempts - 1)).coerceAtMost(15 * 60_000L)
        data.put("interruptions", attempts).put("at", now + delay).put("paused", false)
        // This is a new judgment, never a replay of a partially executed tool transaction.
        data.put("interrupted", true)
    }
}
