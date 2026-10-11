package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.core.config.FunctionalPrompts
import org.junit.Assert.*
import org.junit.Test

class GroupPrivacyTest {
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
