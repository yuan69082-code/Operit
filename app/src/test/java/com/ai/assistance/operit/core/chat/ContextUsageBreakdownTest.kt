package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ToolPrompt
import org.junit.Assert.*
import org.junit.Test

class ContextUsageBreakdownTest {
    @Test fun requestSectionsAndSummaryComparisonsAreScopedToTheWindow() {
        val draft = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "系统规则 <user_profile>用户资料</user_profile>"),
            PromptTurn(PromptTurnKind.SUMMARY, "历史摘要"),
            PromptTurn(PromptTurnKind.ASSISTANT, "回复<tool_result>工具结果</tool_result>"),
            PromptTurn(PromptTurnKind.USER, "当前输入")
        )
        val request = CurrentRequestContext.project(
            CurrentRequestContext.stage(draft, RuntimeContextSections("宿主规则", "本轮状态")), "当前输入"
        )
        val before = ContextUsageDiagnostics.measure(request, listOf(ToolPrompt("read", "读取资料")), 500)
        assertEquals(setOf("角色设定与系统规则", "用户资料", "摘要节选", "工具调用与结果",
            "最近对话", "内部状态与本轮宿主资料", "工具定义与说明"), before.parts.keys)
        assertTrue(before.parts.values.all { it > 0 })
        assertEquals(500L, before.windowTokens)
        val after = ContextUsageDiagnostics.measure(request.filterNot { it.kind == PromptTurnKind.ASSISTANT }, null, 300)
        ContextUsageDiagnostics.record("usage-test-a", after, before)
        ContextUsageDiagnostics.record("usage-test-b", before)
        assertEquals(before, ContextUsageDiagnostics.byChat.value.getValue("usage-test-a").summary?.before)
        assertEquals(after, ContextUsageDiagnostics.byChat.value.getValue("usage-test-a").current)
        assertNull(ContextUsageDiagnostics.byChat.value.getValue("usage-test-b").summary)
    }
}
