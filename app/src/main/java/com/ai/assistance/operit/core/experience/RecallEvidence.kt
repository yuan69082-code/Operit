package com.ai.assistance.operit.core.experience

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject

/** Records the exact recall segment supplied to this request, never a later re-run of retrieval. */
class RecallEvidence(private val context: Context) {
    private val prefs = context.getSharedPreferences("request_recall_evidence", Context.MODE_PRIVATE)
    fun save(chatId: String, sentAt: Long, roleId: String?, content: String) {
        RequestDiagnostics(context).begin(chatId, sentAt, roleId)
        try {
            check(prefs.edit().putString("$chatId:$sentAt:${roleId.orEmpty()}", JSONObject()
                .put("role", roleId).put("strategy", "旧日回声标题召回")
                .put("content", content).toString()).commit()) { "记忆证据保存失败" }
        } catch (e: Exception) { AppLogger.e("RecallEvidence", "Cannot persist recall evidence", e) }
    }
    fun read(chatId: String, sentAt: Long, roleId: String? = null): String? {
        val prefix = "$chatId:$sentAt:"
        val rows = prefs.all.filter { (key, _) -> key.startsWith(prefix) && (roleId == null || key == prefix + roleId) }.values
        if (rows.isEmpty()) return null
        return rows.joinToString("\n\n") {
            val record = JSONObject(it as String)
            val content = record.getString("content")
            "方式：${record.getString("strategy")}\n" + if (content.isBlank()) "本次未注入旧日回声。" else content
        }
    }
}
