package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.core.group.GroupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Host inputs to the same request frame. Raw configuration is evidence, never adopted identity. */
internal object InnerStructureContext {
    suspend fun sources(context: Context, role: String): Map<String, String> {
        SubjectStateBoundary.authorize(role, com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        val cards = CharacterCardManager.getInstance(context)
        val configuration = cards.combinePrompts(role, labelInitialConfiguration = false)
        val voiceConfiguration = cards.combinePrompts(role, promptFunctionType = com.ai.assistance.operit.data.model.PromptFunctionType.VOICE,
            labelInitialConfiguration = false)
        val relations = GroupStore(context).read("relationships")
        return buildMap {
            if (configuration.isNotBlank()) put("config:role_prompt", configuration)
            if (voiceConfiguration.isNotBlank() && voiceConfiguration != configuration) put("config:voice_prompt", voiceConfiguration)
            relations.keys().forEach { key ->
                val row = relations.getJSONObject(key)
                if (row.optString("from") == role) put("relation:" + row.getString("to"), row.toString())
            }
        }
    }

    suspend fun prepare(context: Context, role: String, chat: String, persist: Boolean,
                        wake: Boolean = false, capabilities: JSONObject = JSONObject(), input: String = "",
                        hostMaterials: List<JSONObject> = emptyList(), retrievedMaterials: List<JSONObject> = emptyList()) =
        withContext(Dispatchers.IO) {
            val store = InternalStateStore(context)
            val group = store.requireRecipient(role, chat)
            store.prepare(role, chat, persist, wake, capabilities, input, group,
                sources(context, role),
                CharacterCardManager.getInstance(context).getAllCharacterCards().map { it.id }.toSet() + "user",
                hostMaterials, retrievedMaterials + SubjectActions.materials(context, role, chat) +
                    if (group) emptyList() else SubjectMaterials.read(context, role, input) +
                        ActionUnderstanding.materials(store.read(role)) + SubjectRestDreams.materials(store.read(role)))
        }

    suspend fun submit(context: Context, frame: InternalStateFrame, report: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val current = if (report.optJSONArray("inner")?.length()?.let { it > 0 } == true)
                sources(context, frame.roleId) else null
            InternalStateStore(context).submit(frame, report, current)
        }
}
