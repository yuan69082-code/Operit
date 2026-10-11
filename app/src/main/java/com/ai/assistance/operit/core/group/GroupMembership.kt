package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.model.GroupMemberConfig
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

object GroupMembership {
    private val mutex = Mutex()
    suspend fun change(context: Context, groupId: String, target: String, add: Boolean) = mutex.withLock {
        val manager = CharacterGroupCardManager.getInstance(context)
        val group = requireNotNull(manager.getCharacterGroupCard(groupId)) { "群聊不存在" }
        if (add) require(CharacterCardManager.getInstance(context).getAllCharacterCards().any { it.id == target }) { "助手不存在" }
        val ids = group.members.sortedBy { it.orderIndex }.map { it.characterCardId }
        val next = if (add) (ids + target).distinct() else ids - target
        if (next == ids) return@withLock
        manager.updateCharacterGroupCard(group.copy(members = next.mapIndexed { i, id -> GroupMemberConfig(id, i) }, updatedAt = System.currentTimeMillis()))
        val store = GroupStore(context)
        ChatHistoryManager.getInstance(context).getAllChatMetadata().filter { it.characterGroupId == groupId }.forEach { chat ->
            if (!add) store.cancelWake(chat.id, target)
            store.update(chat.id) {
                it.put("pending", JSONArray(store.strings(it.optJSONArray("pending")).filter { id -> id in next }))
                it.put("mentions", JSONArray(store.strings(it.optJSONArray("mentions")).filter { id -> id in next }))
            }
            if (GroupGames(context).state(chat.id)?.phase?.let { it != "ended" } == true) {
                GroupGames(context).end(chat.id)
                store.log(chat.id, "游戏结束", "成员变动，本局结束，避免牌局与身份错位")
            }
            store.log(chat.id, "成员", "${if (add) "加入" else "离开"}：$target")
        }
    }
}
