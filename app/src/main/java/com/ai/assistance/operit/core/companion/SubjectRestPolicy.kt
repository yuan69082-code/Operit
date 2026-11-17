package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** A chosen arrangement, not a biological sleep model or an idle scheduler. */
internal object SubjectRestPolicy {
    fun adopt(change: JSONObject, prior: JSONObject?, now: Long, frame: String): JSONObject {
        val state = change.getString("state")
        val result = JSONObject().put("state", state)
        if (state == "awake") {
            if (prior != null) result.put("previous_rest", prior.optString("session_id"))
                .put("actual_end", now).put("previous_expected_end", prior.opt("until"))
            prior?.optString("consolidation")?.takeIf { it.isNotBlank() }?.let { result.put("awake_consolidation", it) }
            return result
        }
        val mode = change.optString("mode", "quiet") // Published resting means quiet rest, not an invented bedtime.
        require(mode in setOf("quiet", "sleep")) { "rest.mode: quiet|sleep" }
        val until = if (change.has("until")) change.getLong("until") else 0L
        val wake = change.optJSONArray("wake_sources") ?: JSONArray()
        val sources = CategoryEvents.strings(wake, 16)
        require(sources.all { it in setOf("user_message", "study", "phone", "software", "task", "notification") }) {
            "rest.wake_sources: use source kinds, not keywords or psychological scores"
        }
        require(until == 0L || until > now) { "rest.until: expected end must be in the future" }
        require(mode != "sleep" || until > now || sources.isNotEmpty()) { "rest: sleep needs an end time or a wake condition" }
        val interruption = change.optString("interruption", if (sources.isNotEmpty()) "judge" else "buffer")
        require(interruption in setOf("buffer", "judge", "wake")) { "rest.interruption: buffer|judge|wake" }
        require(mode != "sleep" || until > now || interruption != "buffer") {
            "rest: sleep without a time must admit its chosen wake condition; use quiet for open-ended rest"
        }
        val sameSession = prior?.optString("state") == "resting"
        result.put("mode", mode).put("session_id", if (sameSession) prior!!.optString("session_id", frame) else frame)
            .put("started_at", if (sameSession) prior!!.optLong("started_at", now) else now)
            .put("until", if (until > 0) until else JSONObject.NULL)
            .put("wake_sources", wake).put("interruption", interruption)
            .put("defer_internal", change.optBoolean("defer_internal", true))
        change.optJSONObject("dream_plan")?.let { plan ->
            require(plan.keys().asSequence().all { it in setOf("steps", "theme", "tone", "form", "recall", "theme_mode", "theme_count") })
            require(plan.getInt("steps") in 1..6) { "rest.dream_plan.steps: one bounded session, 1..6 interactions" }
            if (plan.has("theme_mode")) require(plan.get("theme_mode") in setOf("single", "multiple", "random")) { "dream_plan.theme_mode: single|multiple|random" }
            if (plan.has("theme_count")) {
                val count = SubjectContracts.integer(plan, "theme_count")
                require(count in 2..DreamDesign.themes.size && plan.optString("theme_mode") == "multiple") { "dream_plan.theme_count仅用于multiple，范围2..${DreamDesign.themes.size}" }
            }
            for (key in listOf("theme", "tone", "form", "recall")) if (plan.has(key)) {
                require(plan.get(key) is String && plan.getString(key).length <= 1000) { "rest.dream_plan.$key" }
            }
            result.put("dream_plan", JSONObject(plan.toString()))
        }
        change.optString("consolidation").takeIf { it.isNotBlank() }?.let {
            require(it.length <= 2000)
            result.put("consolidation", it)
        }
        return result
    }

    fun due(rest: JSONObject?, now: Long): Boolean = rest?.optString("state") == "resting" &&
        rest.optLong("until") > 0 && now >= rest.optLong("until")

    fun admission(rest: JSONObject?, source: String, now: Long): String {
        if (rest == null || rest.optString("state") != "resting" || due(rest, now)) return "process"
        val matches = rest.optJSONArray("wake_sources")?.let { list ->
            (0 until list.length()).any { list.optString(it) == source }
        } == true
        return if (matches) rest.optString("interruption", "buffer") else "buffer"
    }

    fun label(rest: JSONObject?, busy: String?, now: Long): String = when {
        !busy.isNullOrBlank() -> "忙 · $busy"
        due(rest, now) -> "休息时段已结束，等待续接"
        rest?.optString("state") == "resting" && rest.optString("mode") == "sleep" -> "睡眠中"
        rest?.optString("state") == "resting" -> "安静休息中"
        else -> "空闲"
    }
}
