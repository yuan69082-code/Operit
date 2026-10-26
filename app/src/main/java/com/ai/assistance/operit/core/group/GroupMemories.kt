package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import org.json.JSONObject

/** User notes are separate from observed excerpts and retain the original participation range. */
object GroupMemoryRules {
    /** Apply deletion boundaries inside the same store transaction as observation. */
    fun observe(state: JSONObject, owner: String, entries: List<Pair<Long, String>>, prefix: String = "") {
        val boundary = state.optJSONObject("forgotten_through")?.optLong(owner, -1L) ?: -1L
        val fresh = entries.filter { it.first > boundary }.sortedBy { it.first }
        if (fresh.isEmpty()) return
        val seen = state.optJSONObject("seen") ?: JSONObject()
        val own = seen.optJSONObject(owner) ?: JSONObject().put("first", fresh.first().first)
        if (fresh.last().first < own.optLong("through")) return
        own.put("through", fresh.last().first)
            .put("excerpt", (prefix + fresh.joinToString("\n") { it.second }).takeLast(3000))
        seen.put(owner, own)
        state.put("seen", seen)
    }

    fun recalled(record: JSONObject): String =
        if (record.has("note")) record.optString("note") else record.optString("excerpt")

    fun save(state: JSONObject, owner: String, note: String, deletable: Boolean, editor: String, at: Long) {
        require(note.length <= 12000) { "群记忆最多 12000 字" }
        val record = requireNotNull(state.optJSONObject("seen")?.optJSONObject(owner)) { "没有这位助手的参与记忆" }
        record.put("note", note).put("deletable", deletable).put("edited_by", editor).put("edited_at", at)
    }
    fun delete(state: JSONObject, owner: String) {
        val seen = requireNotNull(state.optJSONObject("seen")) { "群记忆不存在" }
        val record = requireNotNull(seen.optJSONObject(owner)) { "群记忆不存在" }
        check(record.optBoolean("deletable", true)) { "这条记忆受保护，请先明确解除保护" }
        val forgotten = state.optJSONObject("forgotten_through") ?: JSONObject()
        forgotten.put(owner, maxOf(forgotten.optLong(owner), record.optLong("through")))
        state.put("forgotten_through", forgotten)
        seen.remove(owner)
    }
}

class GroupMemories(private val context: Context) {
    private val store = GroupStore(context)
    private suspend fun authorize(operator: String?, owner: String) {
        check(operator == null || operator == owner || CharacterCardManager.getInstance(context).isPrimaryAssistant(operator)) {
            "只能管理自己的群记忆；用户和主助手可管理全部"
        }
    }
    suspend fun list(operator: String?, owner: String): List<JSONObject> {
        authorize(operator, owner)
        return store.ids().mapNotNull { id ->
            val record = store.read(id).optJSONObject("seen")?.optJSONObject(owner) ?: return@mapNotNull null
            val chat = ChatHistoryManager.getInstance(context).getChatMetadata(id)
            val group = chat?.characterGroupId?.let { CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }
            JSONObject(record.toString()).put("chat_id", id).put("owner", owner)
                .put("title", chat?.title ?: "原群会话已删除")
                .put("group_exists", group != null && !group.ended).put("chat_available", chat != null)
        }.sortedByDescending { it.optLong("through") }
    }
    suspend fun save(operator: String?, owner: String, chat: String, note: String, deletable: Boolean) {
        authorize(operator, owner)
        store.update(chat) { GroupMemoryRules.save(it, owner, note, deletable, operator ?: "user", System.currentTimeMillis()) }
    }
    suspend fun delete(operator: String?, owner: String, chat: String) {
        authorize(operator, owner)
        store.update(chat) { GroupMemoryRules.delete(it, owner) }
    }
}
