package com.ai.assistance.operit.core.experience

import android.content.Context
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/** Sidecar telemetry only: never changes tool results, retrieval or model input. */
class RequestDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences("experience_request_diagnostics", Context.MODE_PRIVATE)
    fun begin(chat: String, sentAt: Long, role: String?) = guarded {
        val key = "$chat:$sentAt"
        check(prefs.edit().putString("active:$chat:${role.orEmpty()}", key).putString(key,
            JSONObject().put("role", role).put("events", JSONArray()).toString()).commit())
    }
    fun active(chat: String?, role: String?): String? = if (chat == null) null else prefs.getString("active:$chat:${role.orEmpty()}", null)
    fun append(key: String?, stage: String, detail: String) = guarded {
        if (key != null) {
            val doc = JSONObject(requireNotNull(prefs.getString(key, null)))
            val rows = doc.getJSONArray("events")
            rows.put(JSONObject().put("time", System.currentTimeMillis()).put("stage", stage).put("detail", detail.take(60000)))
            check(prefs.edit().putString(key, doc.toString()).commit())
        }
    }
    fun tool(key: String?, result: ToolResult) {
        append(key, "工具执行", "${result.toolName}：${if (result.success) "成功" else "失败"}\n${result.error.orEmpty()}")
        if (result.toolName in setOf("query_memory", "get_memory_by_title", "search_memory", "memory_query")) {
            append(key, "实际记忆返回", result.result.toString())
        }
    }
    fun read(chat: String, sentAt: Long): String? = prefs.getString("$chat:$sentAt", null)?.let { text ->
        val rows = JSONObject(text).getJSONArray("events")
        (0 until rows.length()).joinToString("\n\n") { i ->
            val row = rows.getJSONObject(i)
            "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(row.getLong("time")))} · ${row.getString("stage")}\n${row.getString("detail")}"
        }
    }
    private fun guarded(block: () -> Unit) { synchronized(lock) {
        try { block() } catch (e: Exception) { AppLogger.e("RequestDiagnostics", "Cannot persist request evidence", e) }
    } }
    companion object { private val lock = Any() }
}
