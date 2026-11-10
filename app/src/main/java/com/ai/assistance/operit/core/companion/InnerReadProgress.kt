package com.ai.assistance.operit.core.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Version-bound delivery progress, not a claim about a model's understanding. A partial
 * preview never grants adoption. Progress is recorded only after the actual request responds. */
internal object InnerReadProgress {
    const val CHUNK_CHARS = 512
    private fun key(candidate: JSONObject) = "candidate:" + InnerStructure.hash(candidate)
    private fun progress(data: JSONObject, candidate: JSONObject): JSONObject? =
        (data as? InternalStateDocument)?.history?.categoryRow("inner_read", key(candidate))
    fun complete(data: JSONObject, candidate: JSONObject): Boolean =
        progress(data, candidate)?.optBoolean("complete") == true

    fun fragment(data: JSONObject, candidate: JSONObject, offset: Int? = null): JSONObject {
        val original = InnerStructure.serialized(candidate)
        val start = offset ?: (progress(data, candidate)?.optInt("read_until") ?: 0)
        require(start in 0..original.length)
        var end = minOf(original.length, start + CHUNK_CHARS)
        if (end < original.length && end > start && Character.isHighSurrogate(original[end - 1])) end--
        return JSONObject().put("candidate_id", candidate.getString("id")).put("hash", InnerStructure.hash(candidate))
            .put("offset", start).put("end", end).put("total", original.length)
            .put("text", original.substring(start, end)).put("complete_record", end == original.length && start == 0)
            .put("next_offset", if (end < original.length) end else JSONObject.NULL)
    }

    /** Adopted text is paged too; reading it never changes the adopted version or meaning. */
    fun recordFragment(data: JSONObject, record: JSONObject, offset: Int? = null): JSONObject {
        val text = InnerStructure.serialized(record)
        val hash = InnerStructure.hash(record)
        val progress = (data as? InternalStateDocument)?.history?.categoryRow("inner_read", "record:$hash")
        val previous = progress?.optInt("read_until") ?: 0
        val start = offset ?: if (previous >= text.length) 0 else previous
        require(start in 0..text.length)
        var end = minOf(text.length, start + CHUNK_CHARS)
        if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) end--
        return JSONObject().put("record_id", record.getString("id")).put("version", record.getInt("version"))
            .put("hash", hash).put("offset", start).put("end", end).put("total", text.length)
            .put("text", text.substring(start, end)).put("complete_record", start == 0 && end == text.length)
            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
            .put("subject_private_material", SubjectRestDreams.privateProvenance(record))
    }

    /** Read-proof is assistant-owned, survives restarts and cannot be transferred to a revision.
     * Only host-bound fragments actually present in the submitted request may call this. */
    suspend fun responded(context: Context, frame: InternalStateFrame): InternalStateFrame = withContext(Dispatchers.IO) {
        if (frame.innerReadParts.isEmpty()) return@withContext frame
        val states = InternalStateStore(context)
        require(!states.requireRecipient(frame.roleId, frame.chatId))
        val pending = frame.innerPending.toMutableMap()
        val versions = frame.innerVersions.toMutableMap()
        val members = frame.interactionMembers.toMutableMap()
        for ((id, raw) in frame.innerReadParts) {
            val part = JSONObject(raw)
            if (part.has("record_id")) {
                val record = InnerStructure.record(states.read(frame.roleId), part.getString("record_id")) ?: continue
                val hash = InnerStructure.hash(record)
                if (hash != part.getString("hash")) continue
                val text = InnerStructure.serialized(record)
                val start = part.getInt("offset"); val end = part.getInt("end")
                require(start in 0..end && end <= text.length && part.getInt("total") == text.length &&
                    text.substring(start, end) == part.getString("text")) { "已采用内容读取片段与保存全文不一致" }
                val receipt = CompanionStore(context).activityWrite(frame.roleId, "inner_read", "record:$hash", "private") { old ->
                    val row = old ?: JSONObject().put("id", "record:$hash").put("storage_kind", "inner_read")
                        .put("parent", "private").put("owner", frame.roleId).put("version", 0)
                        .put("record_id", record.getString("id")).put("hash", hash).put("read_until", 0)
                    val read = row.getInt("read_until")
                    if (start <= read && end > read) row.put("read_until", end).put("last_frame", frame.id)
                    row.put("complete", row.getInt("read_until") == text.length)
                }
                if (receipt.getBoolean("complete")) {
                    versions[record.getString("id")] = record.getInt("version")
                    members["inner:" + record.getString("id")] = record.getInt("version")
                }
                continue
            }
            val candidate = InnerStructure.candidate(states.read(frame.roleId), id) ?: continue
            if (InnerStructure.hash(candidate) != part.getString("hash")) continue
            val text = InnerStructure.serialized(candidate)
            val start = part.getInt("offset"); val end = part.getInt("end")
            require(start in 0..end && end <= text.length && part.getInt("total") == text.length &&
                text.substring(start, end) == part.getString("text")) { "候选读取片段与保存全文不一致" }
            val receipt = CompanionStore(context).activityWrite(frame.roleId, "inner_read", key(candidate), "private") { old ->
                val row = old ?: JSONObject().put("id", key(candidate)).put("storage_kind", "inner_read")
                    .put("parent", "private").put("owner", frame.roleId).put("version", 0)
                    .put("candidate_id", id).put("hash", part.getString("hash")).put("read_until", 0)
                val read = row.getInt("read_until")
                // Out-of-order reads remain usable material, but cannot skip a missing range.
                if (start <= read && end > read) row.put("read_until", end).put("last_frame", frame.id)
                    .put("last_at", System.currentTimeMillis())
                row.put("complete", row.getInt("read_until") == text.length)
            }
            if (receipt.getBoolean("complete")) pending[id] = InnerStructure.hash(candidate)
        }
        frame.copy(innerPending = pending, innerVersions = versions, interactionMembers = members)
    }
}
