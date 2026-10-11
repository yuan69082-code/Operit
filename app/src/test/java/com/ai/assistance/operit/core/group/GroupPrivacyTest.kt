package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.core.config.FunctionalPrompts
import org.junit.Assert.*
import org.junit.Test

class GroupPrivacyTest {
    @Test fun runtimeRandomizedToolTagsUseTheSamePrivacyRules() {
        val raw = "<tool_aB12 name = 'group_game'><param name='move'>秘密行动</param></tool_aB12><tool_result_X1 name=\"assistant_admin\"><content>秘密聊天</content></tool_result_X1>公开部分"
        val public = GroupGamePrivacy.publicText(raw)
        assertFalse(public.contains("秘密")); assertTrue(public.contains("公开部分"))
    }
    @Test fun privateLookupResultsAreNotForwardedToTheNextGroupMember() {
        for (tool in listOf("assistant_admin", "group_chat", "assistant_relationship")) {
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
}
