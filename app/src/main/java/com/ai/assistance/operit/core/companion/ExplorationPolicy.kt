package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Independent of the legacy proactive-contact and learning preferences. */
object ExplorationPolicy {
    val activities = linkedMapOf("mcp" to "已有 MCP（包括 hy）", "web" to "上网探索",
        "reading" to "阅读和复习资料", "organize" to "整理资料与私人待办")

    fun enabled(data: JSONObject) = data.optBoolean("enabled", false)
    fun revision(data: JSONObject) = data.optLong("revision", 0)
    fun choices(data: JSONObject): Set<String> {
        val values = data.optJSONArray("activities") ?: return setOf("reading")
        return (0 until values.length()).map { values.getString(it) }.filter { it in activities }.toSet()
    }
    fun day(now: Long): String = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    fun used(data: JSONObject, day: String): Int = if (data.optString("day") == day) data.optInt("used") else 0

    fun configure(data: JSONObject, enable: Boolean, chat: String, choices: Set<String>, limit: Int, now: Long) {
        require(limit in 1..24) { "每天探索轮次须为1–24次" }
        require(choices.all { it in activities } && (!enable || choices.isNotEmpty())) { "请选择至少一项探索活动" }
        require(!enable || chat.isNotBlank()) { "请先打开该助手的私聊" }
        val wasEnabled = enabled(data)
        val previousChat = data.optString("chat")
        val interrupted = data.has("running")
        data.put("enabled", enable).put("chat", chat).put("activities", JSONArray(choices.toList()))
            .put("limit", limit).put("revision", revision(data) + 1)
        data.remove("running")
        if (!enable || interrupted) {
            data.put("paused", true)
            data.remove("at")
        } else if (!wasEnabled || previousChat != chat) {
            data.put("at", now + 15 * 60_000L).put("paused", false)
                .put("purpose", "首次自主探索，可挑选感兴趣的方向，也可以安静或暂缓。")
        }
    }

    fun schedule(data: JSONObject, minutes: Int, purpose: String, now: Long) {
        check(enabled(data)) { "自主探索未开启，只有用户能打开开关" }
        require(minutes in 15..10080) { "探索间隔须为15–10080分钟" }
        require(purpose.isNotBlank() && purpose.length <= 500) { "请填写1–500字的下次打算" }
        data.put("at", now + minutes * 60_000L).put("purpose", purpose).put("paused", false)
    }

    fun pause(data: JSONObject) {
        data.put("paused", true)
        data.remove("at")
    }

    fun due(data: JSONObject, now: Long, day: String): Boolean = enabled(data) &&
        data.optString("chat").isNotBlank() && choices(data).isNotEmpty() && !data.optBoolean("paused", true) &&
        !data.has("running") && data.has("at") && data.optLong("at") <= now &&
        used(data, day) < data.optInt("limit", 4).coerceIn(1, 24)

    fun claim(data: JSONObject, now: Long, day: String, ticket: String): Boolean {
        if (!due(data, now, day)) return false
        data.put("used", used(data, day) + 1).put("day", day).put("running", ticket)
            .put("started", now).put("paused", true)
        data.remove("at")
        return true
    }

    fun valid(data: JSONObject, chat: String, expectedRevision: Long, ticket: String): Boolean =
        enabled(data) && data.optString("chat") == chat && revision(data) == expectedRevision &&
            data.optString("running") == ticket

    fun finish(data: JSONObject, ticket: String, outcome: String) {
        if (data.optString("running") != ticket) return
        data.remove("running")
        data.put("last_outcome", outcome)
        if (outcome != "completed") pause(data)
    }

    fun recover(data: JSONObject): Boolean {
        val ticket = data.optString("running")
        if (ticket.isBlank()) return false
        finish(data, ticket, "interrupted")
        return true
    }
}
