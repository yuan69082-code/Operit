package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An intentional public message, not a copy of the caller's private conversation. */
object GroupDelivery {
    /** Send only the author's chosen words to their own user-selected private conversation. */
    suspend fun whisper(context: Context, actor: String, source: String, text: String): JSONObject {
        require(text.isNotBlank() && text.length <= 12000) { "私聊消息需为 1–12000 字" }
        val repo = ChatHistoryManager.getInstance(context)
        val metadata = requireNotNull(repo.getChatMetadata(source)) { "群会话不存在" }
        val group = requireNotNull(metadata.characterGroupId?.let {
            CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it)
        }) { "whisper 须在群内使用" }
        check(group.members.any { it.characterCardId == actor }) { "你不在本群内" }
        com.ai.assistance.operit.core.companion.AssistantWindowRouter.initialize(context)
        val target = requireNotNull(com.ai.assistance.operit.core.companion.AssistantWindowRouter.chat(context, actor)) {
            "尚无你的活跃私聊，请先建立你和用户的私聊"
        }
        val privateChat = requireNotNull(repo.getChatMetadata(target)) { "私聊已删除" }
        check(privateChat.characterCardId == actor && privateChat.characterGroupId.isNullOrBlank()) { "私聊归属不匹配" }
        val card = com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context).getCharacterCard(actor)
        val body = GroupTurnPolicy.publicBody(GroupGamePrivacy.publicText(text))
        require(body.isNotBlank()) { "私聊正文为空" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val core = com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
                .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN)
            core.getChatHistoryDelegate().addMessageToChat(ChatMessage(sender = "ai", roleName = card.name,
                content = body, timestamp = ChatMessageTimestampAllocator.next(), completedAt = System.currentTimeMillis()), target)
            com.ai.assistance.operit.api.chat.AIForegroundService.notifyReplyCompleted(
                context, target, card.name, body, null, notifyReplyOverride = true, notifyWhileForeground = true)
        }
        // Group activity records only delivery, never the private message body or destination.
        GroupStore(context).log(source, "私聊", "已向用户发送私聊", actor)
        return JSONObject().put("sent", true).put("chat_id", target)
    }

    suspend fun groups(context: Context, actor: String): JSONArray {
        val result = JSONArray()
        val manager = CharacterGroupCardManager.getInstance(context)
        for (chat in ChatHistoryManager.getInstance(context).getAllChatMetadata()) {
            val group = chat.characterGroupId?.let { manager.getCharacterGroupCard(it) } ?: continue
            if (group.members.any { it.characterCardId == actor }) result.put(JSONObject()
                .put("chat_id", chat.id).put("group_id", group.id).put("name", chat.title)
                .put("group_exists", !group.ended)
                .put("members", JSONArray(group.members.map { it.characterCardId }))
                .put("muted", !GroupModeration(context).mayPublish(chat.id, actor)))
        }
        return result
    }

    suspend fun enqueue(context: Context, actor: String, chat: String, text: String, targets: List<String>): JSONObject {
        val metadata = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chat)) { "群会话不存在" }
        val group = requireNotNull(metadata.characterGroupId?.let { CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }) { "目标必须是已有群聊" }
        val ids = group.members.map { it.characterCardId }
        check(!group.ended) { "目标群已结束，不能发送消息" }
        require(actor in ids && targets.all { it in ids && it != actor }) { "只能以自己身份向已加入的群和群成员发言" }
        require(text.isNotBlank() && text.length <= 12000) { "公开消息需为 1–12000 字" }
        check(GroupModeration(context).mayPublish(chat, actor)) { "你目前在目标群被禁言" }
        val store = GroupStore(context)
        val item = JSONObject().put("id", UUID.randomUUID().toString()).put("actor", actor)
            .put("text", GroupTurnPolicy.cleanLegacyPrefix(text)).put("targets", JSONArray(targets))
            .put("timestamp", ChatMessageTimestampAllocator.next())
        store.update(chat) { state ->
            check(state.optBoolean("group_exists", true)) { "群已结束，不能提交待发消息" }
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
