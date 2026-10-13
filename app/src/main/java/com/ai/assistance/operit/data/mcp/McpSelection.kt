package com.ai.assistance.operit.data.mcp

import android.content.Context
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import kotlinx.coroutines.flow.first

/** Resolve once at the caller boundary, before moving work into a lifecycle scope. */
object McpSelection {
    suspend fun selectedIds(context: Context, scopeId: String? = null): Set<String> {
        val resolvedId = scopeId ?: AssistantScope.currentId(context)
        val cards = CharacterCardManager.getInstance(context)
        val members = if (AssistantScope.isGroup(resolvedId)) {
            CharacterGroupCardManager.getInstance(context).allCharacterGroupCardsFlow.first()
                .firstOrNull { it.id == resolvedId.removePrefix("group:") }
                ?.members.orEmpty().map { it.characterCardId }
        } else listOf(resolvedId)
        val existing = cards.getAllCharacterCards().map { it.id }.toSet()
        return members.filter { it in existing }.flatMap { id ->
            cards.ensureIndependentSettings(id)
            cards.getCharacterCard(id).toolAccessConfig.allowedMcpServers
        }.toSet()
    }
}
