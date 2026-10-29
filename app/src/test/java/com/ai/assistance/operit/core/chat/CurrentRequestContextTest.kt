package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentRequestContextTest {
    @Test fun stagingKeepsCurrentUserAtTheTailWithoutCreatingAnotherUser() {
        val current = PromptTurn(PromptTurnKind.USER, "这是用户原文", metadata = mapOf("message_id" to "u2"))
        val original = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "角色设定"),
            PromptTurn(PromptTurnKind.SYSTEM, "工具规则"),
            PromptTurn(PromptTurnKind.USER, "上一轮问题"),
            PromptTurn(PromptTurnKind.ASSISTANT, "上一轮回答"),
            current
        )

        val staged = CurrentRequestContext.stage(
            original,
            RuntimeContextSections("固定宿主规则", "energy=37")
        )

        assertEquals(original.filter { it.kind == PromptTurnKind.USER }, staged.filter { it.kind == PromptTurnKind.USER })
        assertEquals(current, staged.last())
        assertEquals(original.take(2), staged.take(2))
        assertEquals(PromptTurnKind.SYSTEM, staged[2].kind)
        assertFalse(staged.any { it.content.contains("energy=37") })
    }

    @Test fun changingLiveStateLeavesTheSystemAndHistoricalPrefixIdentical() {
        val original = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "角色设定"),
            PromptTurn(PromptTurnKind.USER, "上一轮问题"),
            PromptTurn(PromptTurnKind.ASSISTANT, "上一轮回答"),
            PromptTurn(PromptTurnKind.USER, "当前问题")
        )
        fun request(state: String) = CurrentRequestContext.project(
            CurrentRequestContext.stage(original, RuntimeContextSections("固定宿主规则", state)),
            "当前问题"
        )

        val first = request("energy=37")
        val next = request("energy=81")

        assertEquals(first.dropLast(1), next.dropLast(1))
        assertNotEquals(first.last().content, next.last().content)
        assertTrue(first.last().content.startsWith("当前问题\n\n"))
        assertTrue(first.last().content.contains("source=\"host\""))
        assertEquals("当前问题", original.last().content)
    }

    @Test fun projectionIsConsumedOnceAndToolContinuationKeepsTheSameSnapshot() {
        val original = listOf(PromptTurn(PromptTurnKind.USER, "读取资料"))
        val projected = CurrentRequestContext.project(
            CurrentRequestContext.stage(original, RuntimeContextSections(state = "energy=37")),
            "读取资料"
        )
        val continuation = projected + listOf(
            PromptTurn(PromptTurnKind.TOOL_CALL, "调用资料工具", "lookup"),
            PromptTurn(PromptTurnKind.TOOL_RESULT, "资料工具结果", "lookup")
        )

        assertEquals(projected, CurrentRequestContext.project(projected, "读取资料"))
        assertEquals(continuation, CurrentRequestContext.project(continuation, ""))
        assertEquals(1, continuation.count { it.content.contains("energy=37") })
        assertEquals(PromptTurnKind.TOOL_RESULT, continuation.last().kind)
    }

    @Test fun clearingOwnedProjectionPreservesLiteralUserTagsAndUnrelatedMetadata() {
        val literal = "我在讨论这个标签：\n" +
            "<operit_request_context source=\"host\" scope=\"current_turn\">手打文字</operit_request_context>"
        val original = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "[本轮宿主资料]\n我手写的系统规则"),
            PromptTurn(PromptTurnKind.USER, literal, metadata = mapOf("message_id" to "u1"))
        )
        val projected = CurrentRequestContext.project(
            CurrentRequestContext.stage(original, RuntimeContextSections(state = "energy=37")),
            literal
        )

        assertEquals(original, CurrentRequestContext.clearPrevious(original))
        assertEquals(original, CurrentRequestContext.clearPrevious(projected))
        assertEquals(original, CurrentRequestContext.clearPrevious(CurrentRequestContext.clearPrevious(projected)))
    }

    @Test fun aNewTurnReplacesOldSnapshotWithoutGrowingHistoricalUserMessages() {
        val firstUser = PromptTurn(PromptTurnKind.USER, "第一句", metadata = mapOf("message_id" to "u1"))
        val first = CurrentRequestContext.project(
            CurrentRequestContext.stage(listOf(firstUser), RuntimeContextSections("固定规则", "snapshot-old")),
            "第一句"
        )
        val nextHistory = first + listOf(
            PromptTurn(PromptTurnKind.ASSISTANT, "第一轮回答"),
            PromptTurn(PromptTurnKind.USER, "第二句")
        )
        val next = CurrentRequestContext.project(
            CurrentRequestContext.stage(nextHistory, RuntimeContextSections("固定规则", "snapshot-new")),
            "第二句"
        )
        val users = next.filter { it.kind == PromptTurnKind.USER }

        assertEquals(2, users.size)
        assertEquals(firstUser, users.first())
        assertFalse(next.any { it.content.contains("snapshot-old") })
        assertEquals(1, next.count { it.content.contains("snapshot-new") })
        assertEquals(1, next.count { it.kind == PromptTurnKind.SYSTEM })
    }

    @Test fun hostOnlyInputNeverAddsRuntimeDataToAnOlderUsersWords() {
        val original = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "角色设定"),
            PromptTurn(PromptTurnKind.USER, "上一次真实发言"),
            PromptTurn(PromptTurnKind.ASSISTANT, "上一次回复")
        )
        val projected = CurrentRequestContext.project(
            CurrentRequestContext.stage(original, RuntimeContextSections(state = "snapshot-current")),
            ""
        )

        assertEquals(original[1], projected.first { it.kind == PromptTurnKind.USER })
        assertTrue(projected.last().content.startsWith("<operit_request_context source=\"host\""))
        assertTrue(projected.last().content.contains("snapshot-current"))
        assertEquals(original, CurrentRequestContext.clearPrevious(projected))
    }

    @Test fun noLiveStateDoesNotCreateAHostOnlyMessageOrChangeUserText() {
        val original = listOf(PromptTurn(PromptTurnKind.USER, "保持原文"))
        val staged = CurrentRequestContext.stage(original, RuntimeContextSections(instructions = "固定规则"))
        val projected = CurrentRequestContext.project(staged, "")

        assertEquals(original, projected.filter { it.kind == PromptTurnKind.USER })
        assertEquals(projected, CurrentRequestContext.project(projected, ""))
    }
}
