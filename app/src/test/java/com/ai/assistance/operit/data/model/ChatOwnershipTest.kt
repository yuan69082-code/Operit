package com.ai.assistance.operit.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatOwnershipTest {
    private val alice = CharacterCard(id = "alice", name = "Alice", isDefault = true)
    private val bob = CharacterCard(id = "bob", name = "Bob")
    private fun chat(cardId: String? = "alice", groupId: String? = null) = ChatHistory(
        id = "chat", title = "Private", messages = emptyList(), characterCardId = cardId,
        characterCardName = "Alice", characterGroupId = groupId)

    @Test fun renamedOrSameNamedAssistantsDoNotInheritPrivateHistory() {
        val history = chat().copy(characterCardName = "Renamed")
        assertTrue(ChatOwnership.belongsTo(history, ActivePrompt.CharacterCard("alice")))
        assertFalse(ChatOwnership.belongsTo(history, ActivePrompt.CharacterCard("bob")))
        assertFalse(ChatOwnership.belongsTo(history, ActivePrompt.CharacterGroup("alice")))
    }

    @Test fun defaultAssistantNeverOwnsGroupHistory() {
        val group = chat(null, "group").copy(characterCardName = null)
        assertFalse(ChatOwnership.belongsTo(group, ActivePrompt.CharacterCard("alice")))
        assertTrue(ChatOwnership.belongsTo(group, ActivePrompt.CharacterGroup("group")))
        assertEquals(ActivePrompt.CharacterGroup("group"), ChatOwnership.prompt(group))
    }

    @Test fun legacyNamesResolveOnlyWhenUnambiguous() {
        assertEquals("alice", ChatOwnership.legacyCardId(null, listOf(alice, bob)))
        assertEquals("bob", ChatOwnership.legacyCardId("Bob", listOf(alice, bob)))
        assertNull(ChatOwnership.legacyCardId("Bob", listOf(alice.copy(name = "Bob"), bob)))
        assertNull(ChatOwnership.legacyCardId("Deleted", listOf(alice, bob)))
    }

    @Test fun privateSendUsesOwnerInsteadOfGlobalSelector() {
        assertEquals("alice", ChatOwnership.speaker(chat(), null, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { ChatOwnership.speaker(chat(), "bob", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ChatOwnership.speaker(chat("unassigned:chat"), null, emptyList()) }
    }

    @Test fun groupOnlyAllowsItsMembers() {
        val history = chat(null, "group")
        assertEquals("bob", ChatOwnership.speaker(history, "bob", listOf("alice", "bob")))
        assertThrows(IllegalArgumentException::class.java) { ChatOwnership.speaker(history, "outsider", listOf("alice", "bob")) }
        assertThrows(IllegalArgumentException::class.java) { ChatOwnership.speaker(history, null, emptyList()) }
    }

    @Test fun toolsCannotReadAnotherAssistantsPrivateChat() {
        assertTrue(ChatOwnership.canAccess(chat(), "alice", emptyList()))
        assertFalse(ChatOwnership.canAccess(chat(), "bob", listOf("bob")))
        assertTrue(ChatOwnership.canAccess(chat(null, "group"), "bob", listOf("alice", "bob")))
        assertFalse(ChatOwnership.canAccess(chat(null, "group"), "outsider", listOf("alice", "bob")))
    }

    @Test fun entityAndArchiveRoundTripsKeepOwnerAndContent() {
        val original = chat().copy(messages = listOf(ChatMessage(sender = "user", content = "private note")))
        val entity = ChatEntity.fromChatHistory(original)
        assertEquals("alice", entity.toChatHistory(original.messages).characterCardId)
        val archive = OperitArchivedChat.fromChatHistory(original, original.messages.map { OperitArchivedMessage(it) })
        val restored = Json.decodeFromString<OperitArchivedChat>(Json.encodeToString(archive)).toChatHistory()
        assertEquals("alice", restored.characterCardId)
        assertEquals(original.messages, restored.messages)
    }

    @Test fun oldArchiveWithoutOwnerStillLoadsWithoutFabricatingIdentity() {
        val restored = Json.decodeFromString<OperitArchivedChat>("""{"id":"old","title":"Old","messages":[],"characterCardName":"Alice"}""").toChatHistory()
        assertNull(restored.characterCardId)
        assertEquals("Alice", restored.characterCardName)
    }
}
