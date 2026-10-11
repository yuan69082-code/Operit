package com.ai.assistance.operit.data.model

/** Names are display data. Once resolved, a chat remains owned by the same immutable ID. */
object ChatOwnership {
    fun speaker(chat: ChatHistory, requestedCardId: String?, groupMemberIds: List<String>): String {
        val allowed = if (chat.characterGroupId.isNullOrBlank()) listOfNotNull(chat.characterCardId)
            else groupMemberIds
        val selected = requestedCardId ?: allowed.firstOrNull()
        require(!selected.isNullOrBlank() && !selected.startsWith("unassigned:") && selected in allowed) {
            "此助手不属于这个会话，请打开对应的私聊或群聊。"
        }
        return selected
    }

    fun canAccess(chat: ChatHistory, cardId: String, groupMemberIds: List<String>, primaryAssistantId: String? = null): Boolean =
        if (cardId.isNotBlank() && cardId == primaryAssistantId) true
        else if (chat.characterGroupId.isNullOrBlank()) chat.characterCardId == cardId
        else cardId in groupMemberIds

    fun legacyCardId(name: String?, cards: List<CharacterCard>): String? =
        if (name.isNullOrBlank()) cards.singleOrNull { it.isDefault }?.id
        else cards.filter { it.name == name }.singleOrNull()?.id

    fun belongsTo(chat: ChatHistory, prompt: ActivePrompt): Boolean = when (prompt) {
        is ActivePrompt.CharacterCard -> chat.characterGroupId.isNullOrBlank() && chat.characterCardId == prompt.id
        is ActivePrompt.CharacterGroup -> chat.characterGroupId == prompt.id
    }

    fun prompt(chat: ChatHistory): ActivePrompt? = when {
        !chat.characterGroupId.isNullOrBlank() -> ActivePrompt.CharacterGroup(chat.characterGroupId)
        !chat.characterCardId.isNullOrBlank() -> ActivePrompt.CharacterCard(chat.characterCardId)
        else -> null
    }
}
