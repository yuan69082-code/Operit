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
        data.remove("interrupted")
        data.remove("result_status")
    }

    fun finish(data: JSONObject, ticket: String, completed: Boolean, now: Long, continuous: Boolean = false) {
        if (data.optString("running_ticket") != ticket) return
        data.remove("running_ticket")
        if (completed) {
            data.remove("interruptions")
            return
        }
        if (continuous) {
            data.put("interrupted", true).put("paused", true).put("result_status", "unknown")
            return // Recovery is not a new subjective decision or permission to retry.
        }
        val attempts = (data.optInt("interruptions") + 1).coerceIn(1, 5)
        val delay = (60_000L shl (attempts - 1)).coerceAtMost(15 * 60_000L)
        data.put("interruptions", attempts).put("at", now + delay).put("paused", false)
        // This is a new judgment, never a replay of a partially executed tool transaction.
        data.put("interrupted", true)
    }

    fun eligible(data: JSONObject, continuous: Boolean): Boolean = !continuous ||
        (data.has("at") && data.optString("owner") != "initial" && !data.optBoolean("interrupted"))
}
