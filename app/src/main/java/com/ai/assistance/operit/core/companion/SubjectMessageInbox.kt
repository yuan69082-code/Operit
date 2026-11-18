package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator
import org.json.JSONArray
import org.json.JSONObject

/** Durable accepted input before acquiring a model slot. The original user content is never
 * rewritten by sleep, compression or a background cancellation. */
internal class SubjectMessageInbox(private val context: Context) {
    private val store = CompanionStore(context)
    fun accept(role: String, chat: String, id: String, text: String, attachments: List<AttachmentInfo>, reply: com.ai.assistance.operit.data.model.ChatMessage? = null): JSONObject = synchronized(lock) {
        store.activityRead(role, "message_inbox", id)?.let {
            check(it.getString("source_chat") == chat && it.getString("text") == text)
            return it
        }
        val receipt = store.activityWrite(role, "message_inbox", id, "queued") { old ->
            if (old != null) {
                check(old.getString("source_chat") == chat && old.getString("text") == text)
                old
            } else JSONObject().put("id", id).put("parent", "queued").put("owner", role)
                .put("source_chat", chat).put("text", text).put("timestamp", ChatMessageTimestampAllocator.next())
                .put("at", System.currentTimeMillis()).put("status", "queued")
                .put("reply", reply?.let { kotlinx.serialization.json.Json.encodeToString(com.ai.assistance.operit.data.model.ChatMessage.serializer(), it) } ?: JSONObject.NULL)
                .put("attachments", JSONArray(attachments.map { a -> JSONObject().put("filePath", a.filePath)
                    .put("fileName", a.fileName).put("mimeType", a.mimeType).put("fileSize", a.fileSize)
                    .put("content", a.content).put("stickerCategory", a.stickerCategory ?: JSONObject.NULL) }))
        }
        SubjectInteractionClock(context).record(role, "user", receipt.getLong("at"))
        return receipt
    }
    fun read(role: String, id: String) = requireNotNull(store.activityRead(role, "message_inbox", id))
    fun reply(row: JSONObject) = if (row.isNull("reply")) null else
        kotlinx.serialization.json.Json.decodeFromString(com.ai.assistance.operit.data.model.ChatMessage.serializer(), row.getString("reply"))
    fun page(role: String, offset: Int) = store.activityQueuePage(role, "message_inbox", listOf("queued", "pending"), offset)
    fun next(role: String) = store.activityPage(role, "message_inbox", "queued", 0).firstOrNull()
    /** Preflight failed before a model turn could claim this receipt. Keep the original
     * destination/content but remove it from automatic dispatch, including after restart. */
    fun rejectQueued(role: String, chat: String, id: String, reason: String): Boolean = synchronized(lock) {
        val row = read(role, id)
        check(row.getString("source_chat") == chat) { "输入来源会话不匹配" }
        if (row.getString("status") != "queued") return false
        store.activityWrite(role, "message_inbox", id, "pending") { current ->
            requireNotNull(current).put("parent", "pending").put("status", "failed")
                .put("dispatch_error", reason).put("updated_at", System.currentTimeMillis())
        }
        true
    }
    fun claim(role: String, id: String): Boolean = synchronized(lock) {
        if (read(role, id).getString("status") != "queued") return false
        var claimed = false
        store.activityWrite(role, "message_inbox", id, "pending") { old ->
            requireNotNull(old)
            if (old.getString("status") == "queued") { old.put("status", "sending").put("parent", "pending"); claimed = true }
            old
        }
        return claimed
    }
    fun finish(role: String, id: String, status: String) = synchronized(lock) {
        require(status in setOf("saved", "completed", "failed", "unknown", "queued"))
        val parent = if (status == "completed") "completed" else if (status == "queued") "queued" else "pending"
        store.activityWrite(role, "message_inbox", id, parent) { requireNotNull(it).put("parent", parent)
            .put("status", status).put("updated_at", System.currentTimeMillis()) }
        if (status == "completed") SubjectLife(context).inputCompleted(role, id)
    }
    fun attachments(row: JSONObject): List<AttachmentInfo> = row.getJSONArray("attachments").let { array ->
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            AttachmentInfo(it.getString("filePath"), it.getString("fileName"), it.getString("mimeType"), it.getLong("fileSize"),
                it.getString("content"), if (it.isNull("stickerCategory")) null else it.getString("stickerCategory"))
        } }
    }
    companion object { private val lock = Any() } // Short receipt transactions only, never model/network work.
}
