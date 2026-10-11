package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.ActivePrompt
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.*

/** The executing assistant wins over foreground navigation, including group/background turns. */
object AssistantScope {
    private val executingId = ThreadLocal<String?>()
    fun executionContext(id: String?) = executingId.asContextElement(id)
    fun callerId(): String? = ToolExecutionManager.currentToolRuntimeContext()?.callerCardId
        ?: executingId.get()

    fun idFlow(context: Context): Flow<String> = flow {
        val caller = callerId()
        if (caller != null) emit(caller)
        else emitAll(ActivePromptManager.getInstance(context).activePromptFlow.map {
            when (it) {
                is ActivePrompt.CharacterCard -> it.id
                is ActivePrompt.CharacterGroup -> "group:${it.id}"
            }
        }.distinctUntilChanged())
    }

    suspend fun currentId(context: Context): String = callerId() ?: idFlow(context).first()
    fun isGroup(id: String) = id.startsWith("group:")

    fun accessFlow(context: Context) = idFlow(context).flatMapLatest { id ->
        if (isGroup(id)) flowOf(com.ai.assistance.operit.data.model.CharacterCardToolAccessConfig(enabled = true))
        else flow {
            val manager = CharacterCardManager.getInstance(context)
            manager.ensureIndependentSettings(id)
            emitAll(manager.getCharacterCardFlow(id).map { it.toolAccessConfig })
        }
    }

    suspend fun memoryId(context: Context, requestedId: String? = null): String {
        val id = requestedId ?: currentId(context)
        val prefs = UserPreferencesManager(context)
        if (isGroup(id)) return prefs.ensureAssistantMemorySpace(id, "群聊记忆")
        val cards = CharacterCardManager.getInstance(context)
        cards.ensureIndependentSettings(id)
        return requireNotNull(cards.getCharacterCard(id).memoryProfileId) { "助手记忆库未初始化" }
    }
}
