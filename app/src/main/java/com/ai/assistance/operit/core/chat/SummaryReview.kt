package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** No summary may leave this gate until the reviewer explicitly accepts a final text. */
internal object SummaryReview {
    suspend fun finalize(draft: String, review: suspend (String) -> String): String {
        require(draft.isNotBlank()) { "压缩草稿为空，原上下文已保留。" }
        val response = withTimeoutOrNull(300_000) { review(draft) }
            ?: error("AI 审阅超时，本次未压缩，原上下文已保留。")
        currentCoroutineContext().ensureActive()
        // Explicit text decisions avoid escaping an entire revised summary as JSON.
        // Arbitrary prose or quoted examples are never interpreted as approval.
        val text = response.replace("\r\n", "\n").trim().removeSurrounding("```text\n", "\n```")
        if (text == "<summary_review>approve</summary_review>") return draft
        if (text == "<summary_review>reject</summary_review>") error("AI 未批准这次压缩，原上下文已保留。")
        val revision = "<summary_review>revise</summary_review>\n"
        if (text.startsWith(revision)) {
            return text.removePrefix(revision).trim().also {
                require(it.isNotBlank()) { "AI 未返回修改后的完整摘要，未执行压缩。" }
            }
        }
        val json = text.removeSurrounding("```json\n", "\n```").removeSurrounding("```\n", "\n```").trim()
        require(json.startsWith("{") && json.endsWith("}")) { "AI 未返回明确的审阅决定，原上下文已保留。请重试审阅。" }
        val result = try { JSONObject(json) } catch (_: org.json.JSONException) {
            error("AI 审阅格式不完整，原上下文已保留。请重试审阅。")
        }
        return when (result.optString("decision")) {
            "approve" -> {
                require(!result.has("summary") || result.get("summary") == "") {
                    "审阅结果同时包含确认和修改，未执行压缩。"
                }
                draft
            }
            "revise" -> {
                val revised = result.opt("summary")
                require(revised is String && revised.isNotBlank()) { "AI 未返回修改后的完整摘要，未执行压缩。" }
                revised.trim()
            }
            "reject" -> error("AI 未批准这次压缩，原上下文已保留。")
            else -> error("AI 未返回有效审阅决定，原上下文已保留。")
        }
    }

    // Changes outside the reviewed slice (such as a newly appended reply) do not invalidate it.
    // Edits, deletion, alternate replies and another summary inside the slice do.
    fun sourceUnchanged(expected: List<ChatMessage>, current: List<ChatMessage>): Boolean {
        val source = expected.filter { it.sender in setOf("user", "ai", "summary") }
        if (source.isEmpty()) return false
        val from = source.minOf { it.timestamp }
        val until = source.maxOf { it.timestamp }
        val actual = current.filter {
            it.timestamp in from..until && it.sender in setOf("user", "ai", "summary")
        }
        return source.size == actual.size && source.zip(actual).all { (a, b) ->
            a.timestamp == b.timestamp && a.sender == b.sender &&
                a.roleName == b.roleName && a.content == b.content
        }
    }
}
