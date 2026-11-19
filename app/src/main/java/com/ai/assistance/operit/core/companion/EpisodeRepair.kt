package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** An unadopted draft. It uses original request provenance and never writes psychological state. */
internal class EpisodeRepair(context: Context) {
    private val store = CompanionStore(context)
    private val kind = "episode_repair"

    fun save(frame: InternalStateFrame, draft: JSONObject) {
        val id = draft.getString("id")
        val sources = JSONObject()
        CategoryEvents.strings(draft.getJSONArray("evidence"), Int.MAX_VALUE).forEach { ref ->
            frame.materialSources[ref]?.let { sources.put(ref, it) }
        }
        store.activityWrite(frame.roleId, kind, id, "pending") { old ->
            // Preserve the first rejected draft and its original evidence through repeated attempts.
            if (old?.optString("parent") == "pending" && old.optString("source_chat") == frame.chatId) old
            else JSONObject().put("id", id).put("parent", "pending").put("owner", frame.roleId)
                .put("source_chat", frame.chatId).put("frame_id", frame.id).put("at", System.currentTimeMillis())
                .put("draft", draft).put("sources", sources)
        }
    }

    fun sources(role: String, chat: String, ids: List<String>): Map<String, JSONObject> = ids.mapNotNull { id ->
        store.activityRead(role, kind, id)?.takeIf {
            it.optString("parent") == "pending" && it.optString("source_chat") == chat
        }?.optJSONObject("sources")?.let { id to it }
    }.toMap()

    fun complete(role: String, chat: String, ids: List<String>) {
        ids.forEach { id ->
            val draft = store.activityRead(role, kind, id) ?: return@forEach
            if (draft.optString("parent") == "pending" && draft.optString("source_chat") == chat) {
                store.activityWrite(role, kind, id, "resolved") { current ->
                    requireNotNull(current).put("parent", "resolved").put("resolved_at", System.currentTimeMillis())
                }
            }
        }
    }

    fun page(role: String, chat: String, id: String?, offset: Int): JSONObject {
        require(offset >= 0)
        if (id != null) {
            val row = store.activityRead(role, kind, id)?.takeIf { it.optString("source_chat") == chat }
            return JSONObject().put("item", row?.apply { remove("sources") } ?: JSONObject.NULL)
        }
        val pending = store.activityPage(role, kind, "pending", offset)
        return JSONObject().put("items", JSONArray(pending.take(8).filter { it.optString("source_chat") == chat }.map {
            JSONObject().put("id", it.getString("id")).put("category_ref", it.getJSONObject("draft").getString("category_ref"))
                .put("at", it.getLong("at"))
        })).put("next_offset", if (pending.size > 8) offset + 8 else JSONObject.NULL)
    }
}
