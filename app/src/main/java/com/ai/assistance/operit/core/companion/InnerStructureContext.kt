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
            // Dispatch instructions describe every possible activity. They are not the
            // subject's present research question and must not drown it in retrieval matches.
            val selectionInput = if (wake) CompanionRuntime.currentExplorationWork(chat)
                ?.takeIf { it.role == role }?.purpose ?: ActivityNotes.selectionInput(context, role, chat) ?: input else input
            val prepared = store.prepare(role, chat, persist, wake, capabilities, input, group,
                sources(context, role),
                CharacterCardManager.getInstance(context).getAllCharacterCards().map { it.id }.toSet() + "user",
                hostMaterials, retrievedMaterials + SubjectActions.materials(context, role, chat) +
                    (if (group) emptyList() else SubjectMaterials.read(context, role, selectionInput) +
                        store.read(role).let { data ->
                            SubjectRestDreams.materials(data, selectionInput, chat) +
                                (if (persist) listOfNotNull(DreamRecall(context).pending(role, CategoryEvents.row(data, "rest", "current")))
                                 else emptyList())
                        } +
                        ActivityNotes.materials(context, role) +
                        SubjectLife(context).materials(role) + PrivateTime(context).materials(role, selectionInput) + ParticipatoryDreams.materials(context, role, selectionInput, chat)),
                selectionInput)
            prepared.copy(instructions = prepared.instructions +
                if (!group && prepared.internalStateFrame != null) {
                    val trigger = DreamSettings(context).trigger(role)
                    PrivateTime(context).setupInstruction(role) + "\n下次新入睡的做梦方式为「${trigger.label}」。${trigger.instruction}"
                } else "")
        }

    suspend fun submit(context: Context, frame: InternalStateFrame, report: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val current = if (report.optJSONArray("inner")?.length()?.let { it > 0 } == true)
                sources(context, frame.roleId) else null
            InternalStateStore(context).submit(frame, report, current)
        }
}
