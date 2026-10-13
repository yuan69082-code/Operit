package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import org.json.JSONObject
import java.util.UUID

/** All state and schedules are keyed by assistant identity, never provider/model. */
class ExplorationStore(private val context: Context) {
    private val store = CompanionStore(context)
    private fun scope(role: String): String {
        require(role.isNotBlank()) { "请指定独立助手" }
        return "exploration:$role"
    }
    private fun roles() = store.preferences.getStringSet("exploration_roles", emptySet()).orEmpty().toSet()
    fun read(role: String) = store.read(scope(role))
    fun enabled(role: String) = ExplorationPolicy.enabled(read(role))
    fun anyEnabled() = roles().any { enabled(it) }

    // This entry point is for user settings only. The AI tool cannot call configure.
    suspend fun configure(role: String, chat: String, enable: Boolean, activities: Set<String>, limit: Int) {
        if (enable) authorize(role, chat)
        synchronized(settingsLock) {
            // Index first: an interrupted configuration can leave an inert entry, never an
            // enabled assistant omitted from scheduler/keep-alive discovery.
            check(store.preferences.edit().putStringSet("exploration_roles", roles() + role).commit()) { "探索设置未能保存" }
            store.update(scope(role)) { ExplorationPolicy.configure(it, enable, chat, activities, limit, System.currentTimeMillis()) }
        }
        CompanionRuntime.explorationSettingsChanged(role)
        store.log(chat, "自主探索设置", if (enable) "已开启；仅此助手，每天最多${limit}轮" else "已关闭，探索预约已撤销；已有资料和日志保留")
    }

    suspend fun authorize(role: String, chat: String) {
        check(CharacterCardManager.getInstance(context).getAllCharacterCards().any { it.id == role }) { "助手不存在" }
        val metadata = ChatHistoryManager.getInstance(context).getChatMetadata(chat) ?: error("私聊不存在")
        check(metadata.characterGroupId.isNullOrBlank() && metadata.characterCardId == role) { "请选择该助手已绑定的独立私聊" }
    }

    suspend fun schedule(role: String, chat: String, minutes: Int, purpose: String): JSONObject {
        val work = CompanionRuntime.currentExplorationWork(chat)
        authorize(role, chat)
        return store.update(scope(role)) {
            if (work != null) check(work.role == role && ExplorationPolicy.valid(it, work.chat, work.revision, work.ticket)) { "旧探索已撤销，不能再安排预约" }
            check(it.optString("chat") == chat) { "请在已指定的探索私聊中安排" }
            ExplorationPolicy.schedule(it, minutes, purpose, System.currentTimeMillis())
        }.also { store.log(chat, "探索安排", "${minutes}分钟后：$purpose") }
    }

    fun pause(role: String): JSONObject = store.update(scope(role)) { ExplorationPolicy.pause(it) }.also {
        if (it.optString("chat").isNotBlank()) store.log(it.getString("chat"), "探索安排", "后续探索预约已暂停")
    }

    fun disableRemovedRole(role: String) {
        if (role !in roles()) return
        store.update(scope(role)) {
            it.put("enabled", false).put("revision", ExplorationPolicy.revision(it) + 1)
            it.remove("running")
            ExplorationPolicy.pause(it)
        }
        CompanionRuntime.explorationSettingsChanged(role)
        com.ai.assistance.operit.api.chat.AIForegroundService.refreshBackgroundKeepAlive(context)
    }

    data class Work(val role: String, val chat: String, val revision: Long, val ticket: String, val purpose: String)

    suspend fun next(): Work? {
        val now = System.currentTimeMillis()
        val day = ExplorationPolicy.day(now)
        for ((role, candidate) in roles().map { it to read(it) }.sortedBy { it.second.optLong("at", Long.MAX_VALUE) }) {
            if (!ExplorationPolicy.due(candidate, now, day)) continue
            val chat = candidate.getString("chat")
            try { authorize(role, chat) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                pause(role)
                store.log(chat, "探索暂停", failure.message.orEmpty())
                continue
            }
            var work: Work? = null
            store.update(scope(role)) { data ->
                // A settings change may have rebound the target while authorize suspended.
                if (data.optString("chat") != chat || ExplorationPolicy.revision(data) != ExplorationPolicy.revision(candidate)) return@update
                val ticket = UUID.randomUUID().toString()
                if (ExplorationPolicy.claim(data, now, day, ticket))
                    work = Work(role, chat, ExplorationPolicy.revision(data), ticket, data.optString("purpose"))
            }
            if (work != null) return work
        }
        return null
    }

    fun valid(work: Work) = ExplorationPolicy.valid(read(work.role), work.chat, work.revision, work.ticket)
    fun finish(work: Work, outcome: String) {
        store.update(scope(work.role)) { ExplorationPolicy.finish(it, work.ticket, outcome) }
        store.log(work.chat, "探索结束", when (outcome) {
            "completed" -> "本轮结束；阅读、整理的实际结果见资料库和私人待办"
            "interrupted" -> "探索已中断，未自动重试"
            else -> "探索未完成，后续预约已暂停，未自动重试"
        })
    }
    fun recover() {
        for (role in roles()) {
            if (!read(role).has("running")) continue
            var interrupted = false
            val data = store.update(scope(role)) { interrupted = ExplorationPolicy.recover(it) }
            if (interrupted) store.log(data.optString("chat"), "探索中断", "上次运行未结束；已暂停预约，请核对进展后重新安排")
        }
    }

    fun status(role: String): JSONObject {
        val data = read(role)
        return JSONObject().put("enabled", ExplorationPolicy.enabled(data)).put("assistant_id", role)
            .put("chat", data.optString("chat")).put("activities", org.json.JSONArray(ExplorationPolicy.choices(data).toList()))
            .put("daily_limit", data.optInt("limit", 4)).put("used_today", ExplorationPolicy.used(data, ExplorationPolicy.day(System.currentTimeMillis())))
            .put("paused", data.optBoolean("paused", true)).put("at", data.optLong("at"))
            .put("purpose", data.optString("purpose")).put("running", data.has("running"))
    }

    companion object { private val settingsLock = Any() }
}
