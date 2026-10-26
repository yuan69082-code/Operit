package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.data.preferences.observedSharedPreferences

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow

/** Additive storage: existing assistant and chat databases are not rewritten. */
class GroupStore(context: Context) {
    private val prefs = context.applicationContext.observedSharedPreferences("vio_group_sessions_v1", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        val revision = MutableStateFlow(0L)
    }
    fun read(chat: String): JSONObject = synchronized(lock) {
        JSONObject(prefs.getString(chat, "{}")!!)
    }
    fun update(chat: String, edit: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val value = read(chat)
        edit(value)
        check(prefs.edit().putString(chat, value.toString()).commit()) { "群聊设置保存失败" }
        revision.value += 1
        value
    }
    fun ids(): Set<String> = prefs.all.keys.filterNot { it.startsWith("authors:") }.toSet()
    fun log(chat: String, kind: String, detail: String, actor: String = "") {
        update(chat) { state ->
            val previous = state.optJSONArray("log") ?: JSONArray()
            val result = JSONArray()
            for (i in maxOf(0, previous.length() - 199) until previous.length()) result.put(previous.get(i))
            result.put(JSONObject().put("at", System.currentTimeMillis()).put("kind", kind)
                .put("detail", detail.take(800)).put("actor", actor))
            state.put("log", result)
        }
    }
    fun selected(chat: String): List<String> = strings(read(chat).optJSONArray("mentions"))
    fun schedule(chat: String, actor: String, minutes: Int, purpose: String) {
        require(purpose.isNotBlank() && purpose.length <= 500) { "请填写 1–500 字的唤醒目的" }
        update(chat) { state ->
            check(state.optBoolean("proactive")) { "请先开启本群的主动对话" }
            check(state.optBoolean("group_exists", true)) { "群已结束，不能安排唤醒" }
            val wakes = state.optJSONObject("wakes") ?: JSONObject()
            wakes.put(actor, JSONObject().put("at", GroupPolicy.wakeAt(System.currentTimeMillis(), minutes))
                .put("purpose", purpose).put("paused", false))
            state.put("wakes", wakes)
        }
        log(chat, "安排", "${minutes}分钟后：$purpose", actor)
    }
    fun cancelWake(chat: String, actor: String) = update(chat) {
        it.optJSONObject("wakes")?.optJSONObject(actor)?.put("paused", true)
    }
    fun takeDecision(chat: String): JSONObject? {
        var result: JSONObject? = null
        update(chat) { result = it.optJSONObject("decision"); it.remove("decision") }
        return result
    }
    fun strings(array: JSONArray?): List<String> = if (array == null) emptyList()
        else (0 until array.length()).map { array.getString(it) }
}
