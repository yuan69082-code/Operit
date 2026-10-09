package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** No summary may leave this gate until the reviewer explicitly accepts a final text. */
internal object SummaryReview {
    suspend fun finalize(draft: String, review: suspend (String) -> String): String {
        require(draft.isNotBlank()) { "压缩草稿为空，原上下文已保留。" }
        val response = review(draft)
        currentCoroutineContext().ensureActive()
        val result = JSONObject(response)
        return when (result.get("decision")) {
            "approve" -> {
                require(!result.has("summary") || result.get("summary") == "") {
                    "审阅结果同时包含确认和修改，未执行压缩。"
                }
                draft
            }
            "revise" -> {
                val revised = result.get("summary")
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
