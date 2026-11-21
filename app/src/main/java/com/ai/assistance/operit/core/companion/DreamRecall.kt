package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class DreamRecall(private val context: Context) {
    private val store = CompanionStore(context)

    fun pending(role: String, rest: JSONObject?): JSONObject? {
        val content = rest?.optJSONObject("content") ?: return null
        if (content.optString("state") != "awake" || !content.has("previous_rest_version")) return null
        val session = content.getString("previous_rest").takeIf { it.isNotBlank() } ?: return null
        if (store.activityRead(role, "life_run", receipt(session)) != null) return null
        val archive = DreamArchive(context)
        val process = store.activityRead(role, "dream_process", "dream:$session")
        val supportedProcess = process?.takeUnless { it.optString("status") in setOf("running", "resume_authorized") }
        val processSegments = supportedProcess?.let { archive.segments(role, it) }.orEmpty()
        val dream = supportedProcess?.takeIf { processSegments.isNotEmpty() }
            ?: store.activityPage(role, "dream", null, 0).firstOrNull { row ->
                val record = row.getJSONObject("content")
                if (record.optString("rest_session").isNotBlank()) record.getString("rest_session") == session
                else record.optInt("rest_version") == content.getInt("previous_rest_version")
            } ?: return null
        val segments = if (dream === supportedProcess) processSegments else archive.segments(role, dream)
        val id = dream.getString("id")
        val kind = dream.getString("storage_kind")
        val version = dream.getInt("version")
        val material = JSONObject().put("id", "material:waking_recall:$kind:$id:$version")
            .put("type", "dream_not_reality").put("owner", role).put("author", role).put("at", content.getLong("actual_end"))
            .put("status", "retrieved").put("not_new_occurrence", true).put("subject_private_material", true)
            .put("waking_recall", true).put("rest_session", session)
            .put("locator", JSONObject().put("kind", kind).put("id", id).put("version", version))
            .put("retrieval", if (kind == "dream_process") "companion_internal_state get section=dream_step id=$id offset=0"
                else "companion_internal_state get section=dream id=$id")
        val digest = InnerStructure.hash(session)
        val full = JSONArray(segments.map { it.json() }).toString()
        if (digest.first().digitToInt(16) % 2 == 0 && SubjectMaterialBudget.estimate(full) <= 300) {
            return material.put("recall_mode", "full").put("text", full)
        }
        val chosen = segments[digest.takeLast(4).toInt(16) % segments.size]
        val length = minOf(160, chosen.text.length)
        val end = if (length > 0 && Character.isHighSurrogate(chosen.text[length - 1])) length - 1 else length
        return material.put("recall_mode", "fragment").put("text", chosen.text.take(end))
    }

    fun delivered(frame: InternalStateFrame) {
        val recall = frame.materialSources.entries.firstOrNull { it.key.startsWith("material:waking_recall:") } ?: return
        val row = JSONObject(recall.value)
        if (!row.optBoolean("waking_recall") || row.optString("owner") != frame.roleId) return
        val id = receipt(row.getString("rest_session"))
        store.activityWrite(frame.roleId, "life_run", id, "dream_recall") { old ->
            old ?: JSONObject().put("id", id).put("owner", frame.roleId).put("parent", "dream_recall")
                .put("status", "delivered").put("at", System.currentTimeMillis())
        }
    }

    private fun receipt(session: String) = "dream-recall-delivery:$session"
}
