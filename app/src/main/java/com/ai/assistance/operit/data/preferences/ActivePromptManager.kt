package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.data.model.ActivePrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ActivePromptManager private constructor(private val context: Context) {

    private val characterCardManager = CharacterCardManager.getInstance(context)
    private val characterGroupCardManager = CharacterGroupCardManager.getInstance(context)
    private val userPreferencesManager = UserPreferencesManager.getInstance(context)
    private val themeOperations = ThemeTargetOperationCoordinator()
    private val selectionMutex = Mutex()

    val activePromptFlow: Flow<ActivePrompt> =
        combine(
            characterGroupCardManager.observeActiveCharacterGroupId(),
            characterCardManager.observeActiveCharacterCardId()
        ) { groupId, cardId ->
            when {
                !groupId.isNullOrBlank() -> ActivePrompt.CharacterGroup(groupId)
                !cardId.isNullOrBlank() -> ActivePrompt.CharacterCard(cardId)
                else -> ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
            }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeThemePreferenceSnapshotFlow: Flow<ThemePreferenceSnapshot> =
        activePromptFlow
            .flatMapLatest { prompt ->
                when (prompt) {
                    is ActivePrompt.CharacterGroup ->
                        userPreferencesManager.observeThemePreferenceSnapshot(
                            characterGroupId = prompt.id,
                        )

                    is ActivePrompt.CharacterCard ->
                        userPreferencesManager.observeThemePreferenceSnapshot(
                            characterCardId = prompt.id,
                        )
                }
            }
            .distinctUntilChanged()

    suspend fun getActivePrompt(): ActivePrompt = activePromptFlow.first()

    suspend fun setActivePrompt(prompt: ActivePrompt, synchronizeChat: Boolean = true) = selectionMutex.withLock {
        applyPrompt(prompt)
        if (synchronizeChat) {
            com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).activateChatForPrompt(prompt)
        }
    }

    private suspend fun applyPrompt(prompt: ActivePrompt) {
        themeOperations.runTransition {
            when (prompt) {
                is ActivePrompt.CharacterGroup -> {
                    val group = requireNotNull(characterGroupCardManager.getCharacterGroupCard(prompt.id)) { "群聊不存在" }
                    group.members.forEach { characterCardManager.ensureIndependentSettings(it.characterCardId) }
                    characterGroupCardManager.setActiveCharacterGroupCard(prompt.id)
                    characterCardManager.clearActiveCharacterCard()
                }
                is ActivePrompt.CharacterCard -> {
                    characterCardManager.ensureIndependentSettings(prompt.id)
                    characterCardManager.setActiveCharacterCard(prompt.id)
                    characterGroupCardManager.setActiveCharacterGroupCard(null)
                }
            }
        }
    }

    suspend fun activateForChat(chat: com.ai.assistance.operit.data.model.ChatHistory) = selectionMutex.withLock {
        val history = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
        // A queued collector for the previous window must never undo a newer selection.
        if (history.currentChatIdFlow.value != chat.id) return@withLock
        val prompt = com.ai.assistance.operit.data.model.ChatOwnership.prompt(chat) ?: return@withLock
        if (prompt is ActivePrompt.CharacterCard && characterCardManager.getAllCharacterCards().none { it.id == prompt.id }) return@withLock
        if (prompt is ActivePrompt.CharacterGroup && characterGroupCardManager.getCharacterGroupCard(prompt.id) == null) return@withLock
        applyPrompt(prompt)
    }

    internal suspend fun <T> runThemeTransition(action: suspend () -> T): T {
        return themeOperations.runTransition(action)
    }

    suspend fun mutateActiveThemeForPrompt(
        target: ActivePrompt,
        transform: (ThemePreferenceValues) -> ThemePreferenceValues,
    ) {
        themeOperations.runTransition {
            if (getActivePrompt() != target) return@runTransition
            userPreferencesManager.mutateThemeForPrompt(
                target = target,
                transform = transform,
            )
        }
    }

    suspend fun commitThemeDraft(
        target: ActivePrompt,
        values: ThemePreferenceValues,
    ) {
        themeOperations.runTransition {
            userPreferencesManager.replaceThemeForPrompt(
                target = target,
                values = values,
            )
        }
    }

    suspend fun resetThemeDraft(
        target: ActivePrompt,
        values: ThemePreferenceValues,
    ) {
        themeOperations.runTransition {
            userPreferencesManager.resetVisualThemeForPrompt(
                target = target,
                values = values,
            )
        }
    }

    suspend fun saveAiAvatarForPrompt(target: ActivePrompt, avatarUri: String?) {
        themeOperations.runTransition {
            when (target) {
                is ActivePrompt.CharacterGroup ->
                    userPreferencesManager.saveAiAvatarForCharacterGroup(target.id, avatarUri)

                is ActivePrompt.CharacterCard ->
                    userPreferencesManager.saveAiAvatarForCharacterCard(target.id, avatarUri)
            }
        }
    }

    suspend fun saveCustomChatTitleForPrompt(target: ActivePrompt, title: String?) {
        themeOperations.runTransition {
            when (target) {
                is ActivePrompt.CharacterGroup ->
                    userPreferencesManager.saveCustomChatTitleForCharacterGroup(target.id, title)

                is ActivePrompt.CharacterCard ->
                    userPreferencesManager.saveCustomChatTitleForCharacterCard(target.id, title)
            }
        }
    }

    suspend fun activateForChatBinding(characterCardName: String?, characterGroupId: String?) {
        val normalizedGroupId = characterGroupId?.trim()?.takeIf { it.isNotBlank() }
        if (!normalizedGroupId.isNullOrBlank()) {
            setActivePrompt(ActivePrompt.CharacterGroup(normalizedGroupId), synchronizeChat = false)
            return
        }

        val normalizedCardName = characterCardName?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedCardName != null) {
            val targetCard = characterCardManager.getAllCharacterCards().singleOrNull { it.name == normalizedCardName }
            if (targetCard != null) {
                setActivePrompt(ActivePrompt.CharacterCard(targetCard.id), synchronizeChat = false)
                return
            }
        }

        if (normalizedCardName == null) setActivePrompt(ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID), synchronizeChat = false)
    }

    suspend fun resolveActiveCardIdForSend(): String {
        return when (val prompt = getActivePrompt()) {
            is ActivePrompt.CharacterCard -> prompt.id
            is ActivePrompt.CharacterGroup -> CharacterCardManager.DEFAULT_CHARACTER_CARD_ID
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ActivePromptManager? = null

        fun getInstance(context: Context): ActivePromptManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ActivePromptManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
