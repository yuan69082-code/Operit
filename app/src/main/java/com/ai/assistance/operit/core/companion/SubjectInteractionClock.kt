package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONObject

/** Role-scoped facts survive compression and window changes. Reads never invent interaction. */
internal class SubjectInteractionClock(private val context: Context) {
    private val store = CompanionStore(context)
    fun record(role: String, kind: String, at: Long) {
        require(kind in setOf("user", "assistant", "contact", "exchange") && at > 0)
        store.update("interaction-clock:$role") { it.put(kind, maxOf(it.optLong(kind), at)) }
    }
    suspend fun snapshot(role: String, now: Long = System.currentTimeMillis()): JSONObject {
        SubjectStateBoundary.authorize(role, com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        val clock = store.read("interaction-clock:$role")
        val history = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
        // Old installs can use real stored timestamps; absence is unknown, never install time.
        val user = maxOf(clock.optLong("user"), history.latestVisibleMessageAt(role, true) ?: 0L)
        val assistant = maxOf(clock.optLong("assistant"), history.latestVisibleMessageAt(role, false) ?: 0L)
        return JSONObject().put("now", now).put("zone", java.time.ZoneId.systemDefault().id).apply {
            for ((name, at) in listOf("last_user" to user, "last_assistant" to assistant,
                "last_proactive_contact" to clock.optLong("contact"), "last_exchange" to clock.optLong("exchange"))) {
                put(name, if (at > 0) at else JSONObject.NULL)
                put("${name}_elapsed_ms", if (at > 0 && now >= at) now - at else JSONObject.NULL)
            }
            put("meaning", "发送/保存时间不等于已读。exchange仅记录用户发言与正常回复的往返；主动发送不刷新。未知间隔不推测；后台停机不等于经历。")
        }
    }
}
