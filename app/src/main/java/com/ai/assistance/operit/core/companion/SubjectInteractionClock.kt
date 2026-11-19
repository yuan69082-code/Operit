package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val history = ChatHistoryManager.getInstance(context)
        val user = latestUserAt(role, clock)
        val assistant = maxOf(clock.optLong("assistant"), history.latestVisibleMessageAt(role, false) ?: 0L)
        return JSONObject().put("now", now).put("zone", ZoneId.systemDefault().id).apply {
            for ((name, at) in listOf("last_user" to user, "last_assistant" to assistant,
                "last_proactive_contact" to clock.optLong("contact"), "last_exchange" to clock.optLong("exchange"),
                "last_wake" to clock.optLong("wake"))) {
                put(name, if (at > 0) at else JSONObject.NULL)
                put("${name}_elapsed_ms", if (at > 0 && now >= at) now - at else JSONObject.NULL)
            }
            put("meaning", "发送/保存时间不等于已读。exchange仅记录用户发言与正常回复的往返；主动发送不刷新。last_wake是最近一次开始准备后台模型请求的时间。未知间隔不推测；后台停机不等于经历。")
        }
    }

    suspend fun wakeSummary(role: String, wakeId: String, record: Boolean, now: Long = System.currentTimeMillis()): String {
        SubjectStateBoundary.authorize(role, com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        val scope = "interaction-clock:$role"
        val userAt = latestUserAt(role, store.read(scope))
        currentCoroutineContext().ensureActive()
        val clock = if (record) {
            store.update(scope) {
                // Save the prior wake before updating it. A retry of this model turn still
                // sees the same prior wake, even across a different service instance.
                if (it.optString("wake_id") != wakeId) {
                    it.put("previous_wake", it.optLong("wake"))
                        .put("wake", now).put("wake_id", wakeId)
                }
            }
        } else store.read(scope)
        val priorWake = clock.optLong(if (clock.optString("wake_id") == wakeId) "previous_wake" else "wake")
        val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault())
        fun time(at: Long): String = if (at > 0) format.format(Instant.ofEpochMilli(at)) else "暂无记录"
        val elapsed = when {
            userAt <= 0 || now < userAt -> "未知"
            now - userAt < 60_000 -> "不足 1 分钟"
            else -> "${(now - userAt) / 60_000} 分钟"
        }
        return "[后台唤醒时钟] 当前时间 ${time(now)}｜上次后台唤醒 ${time(priorWake)}｜用户上次给本助手发消息 ${time(userAt)}｜距上次消息 $elapsed\n"
    }

    private suspend fun latestUserAt(role: String, clock: JSONObject): Long {
        // The DAO aggregates actual visible private messages across this assistant's windows.
        val history = ChatHistoryManager.getInstance(context)
        return maxOf(clock.optLong("user"), history.latestVisibleMessageAt(role, true) ?: 0L)
    }
}
