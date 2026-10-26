package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

object GroupLifecycle {
    private val lock = Mutex()
    suspend fun requireOpen(context: Context, chatId: String) {
        val chat = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chatId)) { "群会话不存在" }
        val group = requireNotNull(chat.characterGroupId?.let { CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }) { "群聊不存在" }
        check(!group.ended) { "这个群已结束，只能查看历史；重新开放后才能发言" }
    }

    suspend fun setExists(context: Context, chatId: String, operator: String?, exists: Boolean) = lock.withLock {
        val history = ChatHistoryManager.getInstance(context)
        val groupId = requireNotNull(history.getChatMetadata(chatId)?.characterGroupId) { "群聊不存在" }
        val manager = CharacterGroupCardManager.getInstance(context)
        val group = requireNotNull(manager.getCharacterGroupCard(groupId)) { "群聊已删除" }
        check(operator == null || group.members.any { it.characterCardId == operator } ||
            CharacterCardManager.getInstance(context).isPrimaryAssistant(operator)) { "只有本群成员、主助手或用户可改变群状态" }
        manager.setGroupEnded(groupId, !exists)
        val store = GroupStore(context)
        for (chat in history.getAllChatMetadata().filter { it.characterGroupId == groupId }) {
            store.update(chat.id) { state ->
                state.put("group_exists", exists)
                if (!exists) {
                    state.put("proactive", false).put("wakes", JSONObject())
                        .put("pending", JSONArray()).put("mentions", JSONArray()).put("outbox", JSONArray())
                        .put("status", "ended").remove("decision")
                }
            }
            if (!exists && operator == null) {
                val core = com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
                    .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN)
                withContext(Dispatchers.Main.immediate) { core.cancelMessageAndWait(chat.id)?.join() }
            }
            if (!exists && GroupGames(context).state(chat.id)?.phase?.let { it != "ended" } == true) GroupGames(context).end(chat.id)
            store.log(chat.id, "群状态", if (exists) "群已重新开放；主动唤醒保持关闭，需另行开启" else "群已结束，历史和记忆保留", operator ?: "user")
        }
    }
}
