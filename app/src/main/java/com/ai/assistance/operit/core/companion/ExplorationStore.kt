package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.util.AppLogger
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
    suspend fun configure(role: String, enable: Boolean, activities: Set<String>, limit: Int) {
        if (enable) {
            check(CharacterCardManager.getInstance(context).getAllCharacterCards().any { it.id == role }) { "助手不存在" }
            CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", "companion_reading", true)
        }
        synchronized(settingsLock) {
            // Index first: an interrupted configuration can leave an inert entry, never an
            // enabled assistant omitted from scheduler/keep-alive discovery.
            check(store.preferences.edit().putStringSet("exploration_roles", roles() + role).commit()) { "探索设置未能保存" }
            store.update(scope(role)) { ExplorationPolicy.configure(it, enable, activities, limit, System.currentTimeMillis(), InternalStateStore(context).enabled(role)) }
        }
        CompanionRuntime.explorationSettingsChanged(role)
        AssistantWindowRouter.chat(context, role)?.let { chat -> store.log(chat, "自主探索设置", if (enable) "已开启；仅此助手，每天最多${limit}轮" else "已关闭，探索预约已撤销；已有资料和日志保留") }
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
            ExplorationPolicy.schedule(it, minutes, purpose, System.currentTimeMillis())
        }.also { logSaved(chat, "探索安排", "${minutes}分钟后：$purpose") }
    }

    fun pause(role: String): JSONObject = store.update(scope(role)) { ExplorationPolicy.selfPause(it) }.also {
        if (it.optString("chat").isNotBlank()) logSaved(it.getString("chat"), "探索安排", "后续探索预约已暂停")
    }

    suspend fun resume(role: String, chat: String): JSONObject {
        authorize(role, chat)
        return store.update(scope(role)) { ExplorationPolicy.resume(it) }
            .also { logSaved(chat, "探索安排", "用户恢复了中断前的原预约；时间和目的未改写") }
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
        AssistantWindowRouter.initialize(context)
        val now = System.currentTimeMillis()
        val day = ExplorationPolicy.day(now)
        for ((role, candidate) in roles().map { it to read(it) }.sortedBy { it.second.optLong("at", Long.MAX_VALUE) }) {
            if (InternalStateStore(context).enabled(role) && candidate.optInt("continuous_boundary") != 1) {
                store.update(scope(role)) { ExplorationPolicy.adoptContinuousBoundary(it) }
                continue
            }
            if (!ExplorationPolicy.due(candidate, now, day)) continue
            val chat = AssistantWindowRouter.chat(context, role) ?: continue
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
                if (AssistantWindowRouter.chat(context, role) != chat || ExplorationPolicy.revision(data) != ExplorationPolicy.revision(candidate)) return@update
                val ticket = UUID.randomUUID().toString()
                if (ExplorationPolicy.claim(data, now, day, ticket, chat)) {
                    val purpose = data.optString("purpose") + if (data.optBoolean("resume_requested"))
                        "\n这是用户恢复的原预约。上次探索曾中断，先核对实际进度和工具结果，不重做已完成操作，再决定是否继续。" else ""
                    work = Work(role, chat, ExplorationPolicy.revision(data), ticket, purpose)
                }
            }
            if (work != null) return work
        }
        return null
    }

    fun valid(work: Work) = AssistantWindowRouter.chat(context, work.role) == work.chat && ExplorationPolicy.valid(read(work.role), work.chat, work.revision, work.ticket)
    fun finish(work: Work, outcome: String) {
        val continuous = InternalStateStore(context).enabled(work.role)
        var finished = false
        val data = store.update(scope(work.role)) {
            finished = it.optString("running") == work.ticket
            ExplorationPolicy.finish(it, work.ticket, outcome, continuous = continuous)
        }
        if (finished) logSaved(work.chat, "探索结束", "本轮结果：$outcome；${arrangementDescription(data)}")
    }
    fun recover() {
        for (role in roles()) {
            if (!read(role).has("running")) continue
            var interrupted = false
            val continuous = InternalStateStore(context).enabled(role)
            val data = store.update(scope(role)) { interrupted = ExplorationPolicy.recover(it, continuous) }
            if (interrupted) logSaved(data.optString("chat"), "探索中断", "上次运行未结束，实际操作需核对；${arrangementDescription(data)}")
        }
    }

    fun status(role: String, data: JSONObject = read(role)): JSONObject {
        return JSONObject().put("enabled", ExplorationPolicy.enabled(data)).put("assistant_id", role)
            .put("chat", AssistantWindowRouter.chat(context, role)).put("activities", org.json.JSONArray(ExplorationPolicy.choices(data).toList()))
            .put("daily_limit", data.optInt("limit", 4)).put("used_today", ExplorationPolicy.used(data, ExplorationPolicy.day(System.currentTimeMillis())))
            .put("paused", data.optBoolean("paused", true)).put("at", data.optLong("at"))
            .put("purpose", data.optString("purpose")).put("running", data.has("running"))
            .put("auto_continued", data.optBoolean("auto_continued")).put("last_outcome", data.optString("last_outcome"))
            .put("schedule_state", ExplorationPolicy.state(data)).put("self_paused", data.optBoolean("self_paused"))
            .put("can_resume", ExplorationPolicy.canResume(data))
            .put("next_at", if (data.has("at") && !data.optBoolean("paused", true)) data.getLong("at") else JSONObject.NULL)
            .put("interrupted_plan", data.optJSONObject("interrupted_plan"))
            .put("continuous", InternalStateStore(context).enabled(role))
    }

    private fun arrangementDescription(data: JSONObject): String = when (ExplorationPolicy.state(data)) {
        "scheduled" -> "下次预约已保存：${java.time.Instant.ofEpochMilli(data.getLong("at")).atZone(java.time.ZoneId.systemDefault())}"
        "paused" -> "已明确暂停后续预约"
        "interrupted" -> if (ExplorationPolicy.canResume(data)) "原预约保留，等待明确恢复，不自动重跑" else "本轮未完成，暂无下一次预约"
        "disabled" -> "自主探索已关闭"
        else -> "本轮没有安排下一次；不等于设置失败"
    }

    private fun logSaved(chat: String, kind: String, detail: String) {
        try { store.log(chat, kind, detail) }
        catch (failure: Exception) {
            // The atomic schedule write has already succeeded. An observational log failure
            // must not make the caller retry the completed scheduling operation.
            AppLogger.e("ExplorationStore", "探索安排已保存，展示日志未写入", failure)
        }
    }

    companion object { private val settingsLock = Any() }
}
