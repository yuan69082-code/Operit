package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.preferences.AssistantScope
import org.json.JSONArray
import org.json.JSONObject

internal class DreamArchive(context: Context) {
    private val store = CompanionStore(context)

    data class Segment(val actor: String, val text: String) {
        fun json() = JSONObject().put("actor", actor).put("text", text)
    }

    fun segments(role: String, dream: JSONObject): List<Segment> {
        if (dream.getString("storage_kind") == "dream") {
            return listOf(Segment("dream", dream.getJSONObject("content").getString("content")))
        }
        val id = dream.getString("id")
        val rows = (0 until 12).mapNotNull { store.activityRead(role, "dream_step", "$id:$it") }
        return rows.filter { it.optString("status") == "completed" }
            .map { Segment(it.getString("actor"), it.getString("text")) }
    }

    fun page(role: String, offset: Int): JSONObject {
        require(AssistantScope.callerId() == null) { "完整梦境仅在用户私域页面显示" }
        store.readPrivateCategories(role)
        val rows = store.dreamArchivePage(role, offset)
        return JSONObject().put("items", JSONArray(rows.take(8).map(::card)))
            .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }

    fun detail(role: String, kind: String, id: String): JSONObject? {
        require(AssistantScope.callerId() == null) { "完整梦境仅在用户私域页面显示" }
        require(kind in setOf("dream", "dream_process"))
        store.readPrivateCategories(role)
        val dream = store.activityRead(role, kind, id) ?: return null
        return card(dream).put("segments", JSONArray(segments(role, dream).map { it.json() }))
    }

    private fun card(row: JSONObject): JSONObject {
        val kind = row.getString("storage_kind")
        val original = row.optJSONObject("content")
        return JSONObject().put("id", row.getString("id")).put("kind", kind)
            .put("at", row.getLong("at")).put("status", row.optString("status", "saved"))
            .put("content", JSONObject().put("title", original?.getString("title") ?: "参与式梦"))
    }
}
