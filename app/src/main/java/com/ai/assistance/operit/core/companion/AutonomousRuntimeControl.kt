package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONObject

/** Technical execution control only. Never manufactures emotions or another state authority. */
class AutonomousRuntimeControl(private val context: Context) {
    private val store = CompanionStore(context)
    private val prefs = store.preferences
    private val key = "autonomous-runtime"
    val enabled get() = prefs.getBoolean("autonomous_enabled", true) // Preserve existing explicit opt-ins.
    fun allowed(now: Long = System.currentTimeMillis()): Boolean = enabled &&
        prefs.getLong("autonomous_pause_until", 0L).let { it == 0L || (it != Long.MAX_VALUE && now >= it) } &&
        store.read(key).optString("phase") != "error"
    fun needsService() = enabled && (prefs.getStringSet("autonomous_idle_roles", emptySet()).orEmpty().isNotEmpty() ||
        prefs.getLong("autonomous_pause_until", 0L) in 1 until Long.MAX_VALUE)

    fun enable(value: Boolean) {
        check(prefs.edit().putBoolean("autonomous_enabled", value).commit())
        changed()
    }
    fun pause(until: Long = Long.MAX_VALUE) {
        require(until > System.currentTimeMillis())
        check(prefs.edit().putLong("autonomous_pause_until", until).commit())
        changed()
    }
    fun resume() {
        // Resume admission, never replay an uncertain external operation.
        store.update(key) { it.put("phase", "waiting").put("resumed_at", System.currentTimeMillis()); it.remove("error") }
        check(prefs.edit().putLong("autonomous_pause_until", 0).commit())
        changed()
    }
    private fun changed() {
        CompanionRuntime.noteUserInteraction()
        if (!allowed()) com.ai.assistance.operit.core.group.GroupWakeRuntime.stopBackground(context)
        com.ai.assistance.operit.api.chat.AIForegroundService.refreshBackgroundKeepAlive(context)
    }
    fun fail(stage: String, failure: Exception) {
        store.update(key) { it.put("phase", "error").put("error", "$stage：${failure.javaClass.simpleName}；请核对进展后恢复。") }
        com.ai.assistance.operit.util.AppLogger.e("AutonomousRuntime", stage, failure)
    }
    fun recover() {
        store.update(key) {
            if (it.optString("phase") == "running") it.put("phase", "error")
                .put("error", "上次后台活动中断，已有进展保留。请核对活动回执后恢复；未知操作不会自动重做。")
                .put("interrupted_at", System.currentTimeMillis())
        }
    }
    fun begin(role: String, activity: String, now: Long = System.currentTimeMillis()) {
        check(allowed(now)) { "自主运行已关闭或暂停" }
        store.update(key) { it.put("phase", "running").put("role", role).put("activity", activity).put("started_at", now) }
    }
    fun finish(role: String, success: Boolean, interrupted: Boolean = false, now: Long = System.currentTimeMillis()) {
        store.update(key) {
            if (it.optString("role") == role && it.optString("phase") == "running") {
                it.put("phase", if (success || interrupted) "waiting" else "error").put("finished_at", now)
                if (!success && !interrupted) it.put("error", "本次后台活动未完成，已暂停自主调用。查看活动记录并核对已执行操作后恢复；不自动重放、不切换付费模型。")
            }
        }
    }
    fun idleConfig(role: String) = store.read("autonomous-idle:$role")
    fun configureIdle(role: String, enabled: Boolean, minutes: Int, daily: Int) {
        require(role.isNotBlank() && minutes in 1..1440 && daily in 1..96)
        store.update("autonomous-idle:$role") {
            it.put("enabled", enabled).put("interval_minutes", minutes).put("daily_limit", daily)
                .put("next_at", System.currentTimeMillis() + minutes * 60_000L).put("held", false)
        }
        synchronized(configLock) {
            val roles = prefs.getStringSet("autonomous_idle_roles", emptySet()).orEmpty().toMutableSet()
            if (enabled) roles.add(role) else roles.remove(role)
            check(prefs.edit().putStringSet("autonomous_idle_roles", roles).commit())
        }
        changed()
    }
    fun waitChoice(role: String, minutes: Int?, note: String): JSONObject {
        require(note.isNotBlank() && note.length <= 1000)
        require(minutes == null || minutes in 1..10080)
        return store.update("autonomous-idle:$role") {
            check(it.optBoolean("enabled")) { "空闲接续尚未由用户开启" }
            it.put("held", minutes == null).put("choice_note", note).put("choice_at", System.currentTimeMillis())
            if (minutes != null) it.put("next_at", System.currentTimeMillis() + minutes * 60_000L)
        }
    }
    internal fun claimIdle(role: String, now: Long): String? {
        if (!allowed(now)) return null
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        if (!idleDue(idleConfig(role), now, day)) return null
        var ticket: String? = null
        store.update("autonomous-idle:$role") { row ->
            if (idleDue(row, now, day)) {
                ticket = "idle:${java.util.UUID.randomUUID()}"
                row.put("used", if (row.optString("day") == day) row.optInt("used") + 1 else 1)
                    .put("day", day).put("last_at", now).put("ticket", ticket)
                    .put("next_at", now + row.getInt("interval_minutes") * 60_000L)
            }
        }
        return ticket
    }
    fun status(role: String, now: Long = System.currentTimeMillis()): JSONObject {
        SubjectStateBoundary.authorize(role, com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        val run = store.read(key)
        val until = prefs.getLong("autonomous_pause_until", 0L)
        val state = when {
            !enabled -> "已关闭"
            until == Long.MAX_VALUE || until > now -> "已暂停"
            run.optString("phase") == "error" -> "异常暂停"
            run.optString("phase") == "running" -> "运行中"
            else -> "等待中"
        }
        return JSONObject().put("state", state).put("enabled", enabled).put("now", now)
            .put("pause_until", if (until == Long.MAX_VALUE) JSONObject.NULL else until)
            .put("reason", run.optString("error")).put("idle", idleConfig(role))
            .put("activity", if (run.optString("role") == role) run.optString("activity") else "")
            .put("started_at", if (run.optString("role") == role) run.optLong("started_at") else 0L)
    }
    companion object {
        private val configLock = Any()
        internal fun idleDue(row: JSONObject, now: Long, day: String): Boolean = row.optBoolean("enabled") &&
            !row.optBoolean("held") && row.has("next_at") && row.getLong("next_at") <= now &&
            (row.optString("day") != day || row.optInt("used") < row.getInt("daily_limit"))
        const val instructions = "[自主运行]companion_life runtime_status查询实际时间、运行开关和自己的空闲安排；runtime_wait用details:{minutes:等待分钟,note:理由}选择下次机会，minutes:null暂停空闲接续。只有用户能开启总开关和空闲能力。空闲机会不要求成果、联系或状态变化；可联想、回顾、转向、放下。研究取材沿用companion_explore及其开关和预算，已安排活动用原入口续接，不重复预约同一活动。情绪、需要、兴趣、人格、关系等按本轮相关材料参与取舍，实际结果沿原状态报告和feedback回流，不另造状态。时间间隔不等于无聊或关系变化；无计算的等待不是持续思考，读取时间不生成经历。"
        const val idlePrompt = "这是用户开启的空闲判断机会，没有新的人类消息，也没有必须完成的任务。结合当前相关状态和挂着的念头，自行决定联想、回顾、探索方向、改变或放下想法、保持安静。可以没有结论或新的心理变化。想继续实际学习使用原companion_explore安排，避免与已有自由时间/活动重复。可用companion_life runtime_wait决定下一次空闲机会或暂停；未选择时沿用户配置的间隔和每日预算再提供机会，不代表你承诺继续。所有实际活动和反馈沿现有活动记录与状态报告保存，不把计划当成果。"
    }
}
