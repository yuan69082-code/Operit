package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** An unadopted draft. It uses original request provenance and never writes psychological state. */
internal class EpisodeRepair(context: Context) {
    private val store = CompanionStore(context)
    private val kind = "episode_repair"
    private fun key(chat: String, id: String) = InnerStructure.hash("$chat:$id")
    private fun pending(chat: String) = "pending:$chat"

    fun save(frame: InternalStateFrame, draft: JSONObject) {
        val id = draft.getString("id")
        val sources = JSONObject()
        CategoryEvents.strings(draft.getJSONArray("evidence"), Int.MAX_VALUE).forEach { ref ->
            frame.materialSources[ref]?.let { sources.put(ref, it) }
        }
        val key = key(frame.chatId, id)
        val parent = pending(frame.chatId)
        store.activityWrite(frame.roleId, kind, key, parent) { old ->
            // Preserve the first rejected draft and its original evidence through repeated attempts.
            if (old != null && old.optString("parent") == parent) old
            else JSONObject().put("id", key).put("parent", parent).put("status", "pending").put("owner", frame.roleId)
                .put("source_chat", frame.chatId).put("frame_id", frame.id).put("at", System.currentTimeMillis())
                .put("draft", draft).put("sources", sources)
        }
    }

    fun sources(role: String, chat: String, ids: List<String>): Map<String, JSONObject> = ids.mapNotNull { id ->
        store.activityRead(role, kind, key(chat, id))?.takeIf {
            it.optString("parent") == pending(chat)
        }?.optJSONObject("sources")?.let { id to it }
    }.toMap()

    fun complete(role: String, chat: String, versions: Map<String, Int>) {
        versions.forEach { (id, expectedVersion) ->
            val key = key(chat, id)
            val draft = store.activityRead(role, kind, key) ?: return@forEach
            if (draft.optString("parent") == pending(chat) && draft.getJSONObject("draft").getInt("expected_version") == expectedVersion) {
                store.activityWrite(role, kind, key, "resolved:$chat") { current ->
                    check(requireNotNull(current).getInt("version") == draft.getInt("version"))
                    current.put("parent", "resolved:$chat").put("status", "resolved")
                        .put("resolved_at", System.currentTimeMillis())
                }
            }
        }
    }

    fun page(role: String, chat: String, id: String?, offset: Int): JSONObject {
        require(offset >= 0)
        if (id != null) {
            val row = store.activityRead(role, kind, key(chat, id))
            return JSONObject().put("item", row?.apply {
                put("id", id); remove("sources"); remove("parent")
            } ?: JSONObject.NULL)
        }
        val rows = store.activityPage(role, kind, pending(chat), offset)
        return JSONObject().put("items", JSONArray(rows.take(8).map {
            val draft = it.getJSONObject("draft")
            JSONObject().put("id", draft.getString("id")).put("category_ref", draft.getString("category_ref"))
                .put("at", it.getLong("at"))
        })).put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }
}
