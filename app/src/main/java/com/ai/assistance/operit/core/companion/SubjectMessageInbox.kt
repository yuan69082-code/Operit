package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator
import org.json.JSONArray
import org.json.JSONObject

/** Durable accepted input before acquiring a model slot. The original user content is never
 * rewritten by sleep, compression or a background cancellation. */
internal class SubjectMessageInbox(context: Context) {
    private val store = CompanionStore(context)
    fun accept(role: String, chat: String, id: String, text: String, attachments: List<AttachmentInfo>): JSONObject =
        store.activityWrite(role, "message_inbox", id, "pending") { old ->
            if (old != null) {
                check(old.getString("source_chat") == chat && old.getString("text") == text)
                old
            } else JSONObject().put("id", id).put("parent", "pending").put("owner", role)
                .put("source_chat", chat).put("text", text).put("timestamp", ChatMessageTimestampAllocator.next())
                .put("at", System.currentTimeMillis()).put("status", "queued")
                .put("attachments", JSONArray(attachments.map { a -> JSONObject().put("filePath", a.filePath)
                    .put("fileName", a.fileName).put("mimeType", a.mimeType).put("fileSize", a.fileSize)
                    .put("content", a.content).put("stickerCategory", a.stickerCategory ?: JSONObject.NULL) }))
        }
    fun read(role: String, id: String) = requireNotNull(store.activityRead(role, "message_inbox", id))
    fun page(role: String, offset: Int) = store.activityPage(role, "message_inbox", "pending", offset)
    fun claim(role: String, id: String): Boolean {
        var claimed = false
        store.activityWrite(role, "message_inbox", id, "pending") { old ->
            requireNotNull(old)
            if (old.getString("status") == "queued") { old.put("status", "sending"); claimed = true }
            old
        }
        return claimed
    }
    fun finish(role: String, id: String, status: String) {
        require(status in setOf("saved", "completed", "failed", "unknown", "queued"))
        val parent = if (status == "completed") "completed" else "pending"
        store.activityWrite(role, "message_inbox", id, parent) { requireNotNull(it).put("parent", parent)
            .put("status", status).put("updated_at", System.currentTimeMillis()) }
    }
    fun attachments(row: JSONObject): List<AttachmentInfo> = row.getJSONArray("attachments").let { array ->
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            AttachmentInfo(it.getString("filePath"), it.getString("fileName"), it.getString("mimeType"), it.getLong("fileSize"),
                it.getString("content"), if (it.isNull("stickerCategory")) null else it.getString("stickerCategory"))
        } }
    }
}
