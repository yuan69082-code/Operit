package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.util.stream.*
import kotlinx.coroutines.CoroutineScope

/** Delay publication until the turn decision is known. Quiet turns never enter UI, TTS or storage. */
object GroupReplyGate {
    fun display(context: Context, chat: String, actor: String, upstream: Stream<String>,
                scope: CoroutineScope): SharedStream<String> = stream<String> {
        val buffer = StringBuilder()
        val moderation = GroupModeration(context)
        val permittedAtStart = moderation.mayPublish(chat, actor)
        upstream.collect { buffer.append(it) }
        val publicText = GroupGamePrivacy.publicText(buffer.toString())
        val control = GroupTurnPolicy.control(publicText)
        val store = GroupStore(context)
        val groupId = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chat)?.characterGroupId
        val members = groupId?.let { com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }?.members.orEmpty().map { it.characterCardId }
        if (control != null) {
            if (actor in members && (control.action != "respond" || store.read(chat).optBoolean("continuous") &&
                    control.targets.isNotEmpty() && control.targets.all { it in members && it != actor })) {
                store.update(chat) { it.put("decision", org.json.JSONObject().put("actor", actor).put("action", control.action)
                    .put("targets", org.json.JSONArray(control.targets)).put("reason", "本轮发言控制")) }
                store.log(chat, "决定", control.action, actor)
            }
        }
        val decision = store.read(chat).optJSONObject("decision")
        val action = decision?.takeIf { it.optString("actor") == actor }?.optString("action")
        if (actor in members && permittedAtStart && !moderation.manuallyMuted(chat, actor) && !GroupTurnPolicy.suppress(action)) {
            val text = GroupTurnPolicy.publicBody(publicText)
            if (text.isNotBlank()) emit(text)
        }
    }.share(scope, replay = 1)
}
