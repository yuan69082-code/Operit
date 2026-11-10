package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ChatMarkupRegex

/** Request-only excerpts of completed, saved results; the stored conversation stays verbatim. */
internal object HistoricalToolResultContext {
    private const val LARGE_RESULT_CHARACTERS = 4096
    private const val HEAD_CHARACTERS = 768
    private const val TAIL_CHARACTERS = 256
    private const val MARKER = "[历史工具结果节选；非完整原文]"

    fun hasCandidates(turns: List<PromptTurn>): Boolean = turns.any(::isSavedResult)

    private fun isSavedResult(turn: PromptTurn): Boolean =
        turn.kind == PromptTurnKind.TOOL_RESULT &&
            turn.content.length > LARGE_RESULT_CHARACTERS &&
            (turn.metadata[PromptTurn.SOURCE_MESSAGE_AT_METADATA_KEY] as? Long ?: 0L) > 0L &&
            (turn.metadata[PromptTurn.SOURCE_COMPLETED_AT_METADATA_KEY] as? Long ?: 0L) > 0L

    /** Caller must establish that group_chat history_message can read this private chat. */
    fun compact(turns: List<PromptTurn>, chatId: String): List<PromptTurn> {
        require(chatId.isNotBlank())
        return turns.map { turn ->
            if (!isSavedResult(turn)) return@map turn
            val at = turn.metadata[PromptTurn.SOURCE_MESSAGE_AT_METADATA_KEY] as Long
            val compacted = ChatMarkupRegex.toolResultTagWithAttrs.replace(turn.content) { result ->
                val attrs = result.groupValues[2]
                val status = ChatMarkupRegex.statusAttr.find(attrs)?.groupValues?.get(1)
                // Errors carry the diagnostic needed for the next attempt and stay complete.
                if (status != "success") return@replace result.value
                val body = requireNotNull(result.groups[3])
                if (ChatMarkupRegex.errorTag.containsMatchIn(body.value)) return@replace result.value
                val envelope = ChatMarkupRegex.contentTag.find(body.value)
                val content = envelope?.groups?.get(1)?.value ?: body.value
                if (content.length <= LARGE_RESULT_CHARACTERS || content.startsWith(MARKER)) {
                    return@replace result.value
                }

                val headEnd = safeEnd(content, HEAD_CHARACTERS)
                val tailStart = safeStart(content, content.length - TAIL_CHARACTERS)
                val preview = buildString {
                    append(MARKER).append('\n')
                    append("原结果共 ").append(content.length).append(" 字符；中间省略 ")
                        .append(tailStart - headEnd).append(" 字符。以下为首尾原文节选，XML符号已转义。\n")
                    append("开头：\n").append(escapeXml(content.substring(0, headEnd)))
                    append("\n[中间原文未携带]\n结尾：\n")
                        .append(escapeXml(content.substring(tailStart)))
                    append("\n需要完整内容时读取已保存消息，不要重新执行原操作：")
                    append("group_chat action=history_message chat_id=\"")
                        .append(escapeXml(chatId)).append("\" message_at=").append(at)
                    append(" part=tools offset=0；按 next_offset 续读，直到 complete=true。")
                }
                if (preview.length >= content.length) return@replace result.value
                val compactedBody = if (envelope != null) {
                    body.value.replaceRange(requireNotNull(envelope.groups[1]).range, preview)
                } else preview
                // Preserve tool name, success status, randomized tags and native call IDs.
                result.value.replaceRange(body.range.shift(-result.range.first), compactedBody)
            }
            if (compacted == turn.content) turn else turn.copy(content = compacted)
        }
    }

    private fun IntRange.shift(offset: Int): IntRange = (first + offset)..(last + offset)

    private fun safeEnd(text: String, end: Int): Int =
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end

    private fun safeStart(text: String, start: Int): Int =
        if (text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) start + 1 else start

    private fun escapeXml(text: String): String = text.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
