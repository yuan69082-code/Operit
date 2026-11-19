package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Text-only estimates of the final request. Never counts or stores model output or raw prompts. */
data class ContextUsageBreakdown(val windowTokens: Long, val parts: Map<String, Long>) {
    val textTokens: Long get() = parts.values.sum()
}

internal object ContextUsageDiagnostics {
    data class SummaryComparison(val before: ContextUsageBreakdown, val after: ContextUsageBreakdown)
    data class Snapshot(val current: ContextUsageBreakdown, val summary: SummaryComparison? = null)
    private val snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val byChat = snapshots.asStateFlow()

    fun record(chat: String, usage: ContextUsageBreakdown, beforeSummary: ContextUsageBreakdown? = null) {
        snapshots.update { old ->
            val comparison = beforeSummary?.let { SummaryComparison(it, usage) } ?: old[chat]?.summary
            val recent = (old - chat).entries.toList().takeLast(31).associate { it.toPair() }
            recent + (chat to Snapshot(usage, comparison))
        }
    }

    private val userProfile = Regex("""<user_profile\b[^>]*>[\s\S]*?</user_profile>""")
    private val toolMarkup = Regex("""<tool(?:_result)?\b[^>]*>[\s\S]*?</tool(?:_result)?>""")

    fun measure(turns: List<PromptTurn>, tools: List<ToolPrompt>?, providerTotal: Long): ContextUsageBreakdown {
        val parts = linkedMapOf<String, Long>()
        fun count(label: String, text: String) {
            if (text.isNotBlank()) parts[label] = (parts[label] ?: 0) + ChatUtils.estimateTokenCount(text)
        }
        turns.forEach { turn ->
            val content = turn.content
            val userText = CurrentRequestContext.originalUserText(turn)
            when {
                CurrentRequestContext.isHostInstruction(turn) || CurrentRequestContext.isHostOnly(turn) ->
                    count("内部状态与本轮宿主资料", content)
                userText != null -> {
                    if (content.endsWith(userText)) {
                        count("内部状态与本轮宿主资料", content.dropLast(userText.length))
                        count("最近对话", userText)
                    } else count("最近对话", content)
                }
                turn.kind == PromptTurnKind.SYSTEM -> {
                    count("角色设定与系统规则", userProfile.replace(content) {
                        count("用户资料", it.value)
                        ""
                    })
                }
                turn.kind == PromptTurnKind.SUMMARY -> count("摘要节选", content)
                turn.kind in setOf(PromptTurnKind.TOOL_CALL, PromptTurnKind.TOOL_RESULT) ->
                    count("工具调用与结果", content)
                turn.kind == PromptTurnKind.ASSISTANT -> count("最近对话", toolMarkup.replace(ChatUtils.removeThinkingContent(content)) {
                    count("工具调用与结果", it.value)
                    ""
                })
                else -> count("最近对话", content)
            }
        }
        tools.orEmpty().forEach { count("工具定义与说明", it.toString()) }
        return ContextUsageBreakdown(providerTotal, parts)
    }
}
