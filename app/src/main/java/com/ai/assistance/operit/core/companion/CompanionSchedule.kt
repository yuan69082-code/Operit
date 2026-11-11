package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** An in-flight judgment is not an explicit request to pause future judgments. */
internal object CompanionSchedule {
    fun claim(data: JSONObject, ticket: String, now: Long) {
        data.put("running_ticket", ticket).put("started", now).put("paused", true)
        data.remove("resume_requested")
    }

    fun supersede(data: JSONObject) {
        data.remove("running_ticket")
        data.remove("interruptions")
        data.remove("interrupted")
        data.remove("result_status")
        data.remove("self_paused")
        data.remove("resume_requested")
    }

    fun cancel(data: JSONObject) {
        supersede(data)
        data.put("paused", true).put("self_paused", true)
    }

    fun finish(data: JSONObject, ticket: String, completed: Boolean, now: Long, continuous: Boolean = false) {
        if (data.optString("running_ticket") != ticket) return
        data.remove("running_ticket")
        if (completed) {
            data.remove("interruptions")
            data.remove("interrupted")
            data.put("result_status", "completed")
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
        (data.has("at") && data.optString("owner") != "initial" &&
            (!data.optBoolean("interrupted") || data.optBoolean("resume_requested")))

    fun state(data: JSONObject): String = when {
        data.has("running_ticket") -> "running"
        !data.optBoolean("paused") && data.optLong("at") > 0 -> "scheduled"
        data.optBoolean("self_paused") -> "paused"
        data.optBoolean("interrupted") -> "interrupted"
        data.optString("result_status") == "completed" -> "not_scheduled"
        data.optBoolean("paused") -> "paused"
        else -> "not_scheduled"
    }

    fun canResume(data: JSONObject): Boolean = state(data) == "interrupted" &&
        data.optLong("at") > 0 && data.optString("owner") in setOf("ai", "user")

    /** Only an explicit user action may resume an interrupted plan; retain its actual time/purpose. */
    fun resume(data: JSONObject) {
        check(canResume(data)) { "没有可恢复的明确主动联系预约" }
        data.put("paused", false).put("resume_requested", true)
    }

    fun status(data: JSONObject): JSONObject = JSONObject(data.toString())
        .put("schedule_state", state(data)).put("running", data.has("running_ticket"))
        .put("can_resume", canResume(data))
        .put("next_at", if (state(data) == "scheduled") data.optLong("at") else JSONObject.NULL)
}
