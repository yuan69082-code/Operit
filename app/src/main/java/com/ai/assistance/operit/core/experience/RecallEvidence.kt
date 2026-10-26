package com.ai.assistance.operit.core.experience

import android.content.Context
import org.json.JSONObject

/** Records the exact recall segment supplied to this request, never a later re-run of retrieval. */
class RecallEvidence(private val context: Context) {
    private val prefs = context.getSharedPreferences("request_recall_evidence", Context.MODE_PRIVATE)
    fun save(chatId: String, sentAt: Long, roleId: String?, content: String) {
        RequestDiagnostics(context).begin(chatId, sentAt, roleId)
        check(prefs.edit().putString("$chatId:$sentAt", JSONObject()
            .put("role", roleId).put("strategy", "旧日回声标题召回")
            .put("content", content).toString()).commit()) { "记忆证据保存失败" }
    }
    fun read(chatId: String, sentAt: Long): String? = prefs.getString("$chatId:$sentAt", null)?.let {
        val record = JSONObject(it)
        val content = record.getString("content")
        "方式：${record.getString("strategy")}\n" + if (content.isBlank()) "本次未注入旧日回声。" else content
    }
}
