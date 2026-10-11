package com.ai.assistance.operit.data.model

import org.junit.Assert.*
import org.junit.Test

class PrimaryAssistantAccessTest {
    private val privateChat = ChatHistory(id = "private", title = "B", messages = emptyList(), characterCardId = "bob")
    private val groupChat = ChatHistory(id = "group-chat", title = "Group", messages = emptyList(), characterGroupId = "group")

    @Test fun userSelectedPrimaryCanInspectOtherAssistantsPrivateAndGroupHistory() {
        assertTrue(ChatOwnership.canAccess(privateChat, "alice", emptyList(), "alice"))
        assertTrue(ChatOwnership.canAccess(groupChat, "alice", listOf("bob"), "alice"))
        assertFalse(ChatOwnership.canAccess(privateChat, "eve", emptyList(), "alice"))
    }

    @Test fun revocationImmediatelyRestoresOrdinaryOwnershipRules() {
        assertFalse(ChatOwnership.canAccess(privateChat, "alice", emptyList(), null))
        assertFalse(ChatOwnership.canAccess(groupChat, "alice", listOf("bob"), "carol"))
        assertTrue(ChatOwnership.canAccess(privateChat, "bob", emptyList(), null))
    }

    @Test fun primaryCannotImpersonateAnotherChatSpeaker() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatOwnership.speaker(privateChat, "alice", emptyList())
        }
        assertEquals("bob", ChatOwnership.speaker(privateChat, null, emptyList()))
        assertFalse(ChatOwnership.canAccess(privateChat, "", emptyList(), ""))
    }
}
