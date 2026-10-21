package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class GroupPrivacyTest {
    @Test fun internalLookupsLeaveOnlyPublicSpeechWithoutNotices() {
        val raw = "<tool name='group_chat'><param name='action'>whisper</param><param name='text'>只想对你说</param></tool>" +
            "<tool_result name='group_chat'><content>私聊位置</content></tool_result>群里说的话"
        assertEquals("群里说的话", GroupGamePrivacy.publicText(raw))
        val old = "[工具内部操作不在群公共上下文中展开][此助手查询的私人资料不在群公共上下文中展开]旧正文"
        assertEquals("旧正文", GroupGamePrivacy.withoutInternalNotices(old))
    }
    @Test fun interruptedPrivateLookupIsRemovedFromContinuitySnapshots() {
        val message = ChatMessage(sender = "ai", content = "公开发言<tool_result_x1 name='assistant_admin'><content>另一助手的私聊")
        val preview = GroupContext.visible(message)
        assertTrue(preview.contains("公开发言"))
        assertFalse(preview.contains("另一助手的私聊"))
    }
    @Test fun runtimeRandomizedToolTagsUseTheSamePrivacyRules() {
        val raw = "<tool_aB12 name = 'group_game'><param name='move'>秘密行动</param></tool_aB12><tool_result_X1 name=\"assistant_admin\"><content>秘密聊天</content></tool_result_X1>公开部分"
        val public = GroupGamePrivacy.publicText(raw)
        assertFalse(public.contains("秘密")); assertTrue(public.contains("公开部分"))
    }
    @Test fun privateLookupResultsAreNotForwardedToTheNextGroupMember() {
        for (tool in listOf("assistant_admin", "group_chat", "assistant_relationship", "companion_todo", "companion_library", "companion_state", "phone_awareness", "companion_room", "companion_diary", "companion_reading")) {
            val text = GroupGamePrivacy.publicText("<tool_result name=\"$tool\" status=\"success\"><content>另一窗口的私事</content></tool_result>我已查看")
            assertFalse(text.contains("另一窗口的私事")); assertTrue(text.contains("我已查看"))
        }
    }
    @Test fun publicHistoryNeverIncludesSecretToolParametersOrReasoning() {
        val raw = "<think>我的身份是狼人</think>轮到我了<tool name=\"group_game\"><param name=\"move\">{\"hidden\":\"藏钥匙\",\"target\":\"a\"}</param></tool>公开发言"
        val text = GroupGamePrivacy.publicText(raw)
        assertFalse(text.contains("狼人")); assertFalse(text.contains("藏钥匙")); assertFalse(text.contains("target"))
        assertTrue(text.contains("公开发言")); assertTrue(text.contains("轮到我了"))
    }
    @Test fun truncatedSecretActionIsNotExposed() {
        assertFalse(GroupGamePrivacy.publicText("<tool name='group_game'><param name='move'>秘密内容").contains("秘密内容"))
    }
    @Test fun groupDraftAndReviewRemoveAmbiguousFirstPersonRequirements() {
        for (english in listOf(false, true)) {
            val prompt = GroupProvenance.summaryInstructions(FunctionalPrompts.buildSummarySystemPrompt(null, english))
            assertFalse(prompt.contains("第一人称")); assertFalse(prompt.contains("first-person"))
            assertTrue(prompt.contains("助手ID")); assertTrue(prompt.contains("不是亲历"))
        }
    }
    @Test fun speakerOwnershipDoesNotDependOnDisplayName() {
        assertTrue(GroupPolicy.ownsTurn("id-a", "id-a"))
        assertFalse(GroupPolicy.ownsTurn("id-b", "id-a"))
        assertFalse(GroupPolicy.ownsTurn("id-a", null))
        assertFalse(GroupPolicy.ownsTurn(null, null))
    }

    @Test fun privateNotesWrittenInGroupDoNotLeakInToolArguments() {
        for (name in listOf("companion_todo", "companion_library", "companion_state", "assistant_admin", "companion_room", "companion_diary", "companion_reading")) {
            val public = GroupGamePrivacy.publicText("<tool_x1 name='$name'><param name='content'>我的私人打算</param></tool_x1>公开的话")
            assertFalse(public.contains("私人打算"))
            assertTrue(public.contains("公开的话"))
        }
    }
}

