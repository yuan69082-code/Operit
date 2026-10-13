package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An intentional public message, not a copy of the caller's private conversation. */
object GroupDelivery {
    suspend fun groups(context: Context, actor: String): JSONArray {
        val result = JSONArray()
        val manager = CharacterGroupCardManager.getInstance(context)
        for (chat in ChatHistoryManager.getInstance(context).getAllChatMetadata()) {
            val group = chat.characterGroupId?.let { manager.getCharacterGroupCard(it) } ?: continue
            if (group.members.any { it.characterCardId == actor }) result.put(JSONObject()
                .put("chat_id", chat.id).put("group_id", group.id).put("name", chat.title)
                .put("members", JSONArray(group.members.map { it.characterCardId }))
                .put("muted", !GroupModeration(context).mayPublish(chat.id, actor)))
        }
        return result
    }

    suspend fun enqueue(context: Context, actor: String, chat: String, text: String, targets: List<String>): JSONObject {
        val metadata = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chat)) { "群会话不存在" }
        val group = requireNotNull(metadata.characterGroupId?.let { CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }) { "目标必须是已有群聊" }
        val ids = group.members.map { it.characterCardId }
        require(actor in ids && targets.all { it in ids && it != actor }) { "只能以自己身份向已加入的群和群成员发言" }
        require(text.isNotBlank() && text.length <= 12000) { "公开消息需为 1–12000 字" }
        check(GroupModeration(context).mayPublish(chat, actor)) { "你目前在目标群被禁言" }
        val store = GroupStore(context)
        val item = JSONObject().put("id", UUID.randomUUID().toString()).put("actor", actor)
            .put("text", GroupTurnPolicy.cleanLegacyPrefix(text)).put("targets", JSONArray(targets))
            .put("timestamp", ChatMessageTimestampAllocator.next())
        store.update(chat) { state ->
            val outbox = state.optJSONArray("outbox") ?: JSONArray()
            require(outbox.length() < 30) { "此群待发消息已满，请等待投递" }
            outbox.put(item); state.put("outbox", outbox)
        }
        store.log(chat, "待发送", "私聊提交公开消息，等待当前对话空闲后投递：${item.getString("id")}", actor)
        return JSONObject().put("queued", true).put("message_id", item.getString("id")).put("chat_id", chat)
    }
    fun remove(store: GroupStore, chat: String, id: String) = store.update(chat) { state ->
        val old = state.optJSONArray("outbox") ?: JSONArray()
        state.put("outbox", JSONArray((0 until old.length()).map { old.getJSONObject(it) }.filter { it.optString("id") != id }))
    }
}
