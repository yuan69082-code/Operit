package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Explicit display notes and objective receipts. Old private companion_note is never imported. */
internal object ActivityNotes {
    val kinds = setOf("activity", "activity_part", "waking_note")
    data class Ref(val role: String, val chat: String, val id: String)
    private val active = ConcurrentHashMap<String, Ref>()
    fun current(chat: String?): Ref? = chat?.let { active[it] }
    fun release(ref: Ref) { active.remove(ref.chat, ref) }

    suspend fun begin(context: Context, role: String, chat: String, trigger: String, source: String): Ref =
        withContext(Dispatchers.IO) {
            check(!InternalStateStore(context).recipientScope(role, chat)) { "活动随记仅在本助手私域保存" }
            val ref = Ref(role, chat, "activity-" + InnerStructure.hash("$role:$trigger"))
            CompanionStore(context).activityWrite(role, "activity", ref.id, "private") { old ->
                old ?: base(ref, "activity", ref.id, "private").put("trigger", trigger).put("source", source)
                    .put("status", "received").put("message_count", 0)
            }
            check(active.putIfAbsent(chat, ref) == null) { "此窗口已有活动" }
            ref
        }

    private fun base(ref: Ref, kind: String, id: String, parent: String) = JSONObject()
        .put("id", id).put("parent", parent).put("storage_kind", kind).put("owner", ref.role)
        .put("source_chat", ref.chat).put("activity_id", ref.id).put("version", 1)
        .put("at", System.currentTimeMillis()).put("visibility", "user_visible_private_domain")

    suspend fun part(context: Context, ref: Ref, key: String, kind: String, content: JSONObject) =
        withContext(Dispatchers.IO) {
            val id = ref.id + ":" + key
            CompanionStore(context).activityWrite(ref.role, "activity_part", id, ref.id) { old ->
                if (old != null) {
                    require(old.getJSONObject("content").toString() == content.toString()) { "活动片段重放内容不一致" }
                    if (old.optBoolean("withdrawn")) old.put("withdrawn", false).put("version", old.getInt("version") + 1) else old
                } else base(ref, "activity_part", id, ref.id).put("kind", kind).put("content", content)
            }
        }

    suspend fun note(context: Context, ref: Ref, key: String, text: String, waking: Boolean) {
        if (text.isBlank()) return
        val content = JSONObject().put("text", text).put("origin", "explicit_model_display_note")
            .put("reality", if (waking) "WAKING_NOTE_NOT_EXTERNAL_FACT" else "SUBJECT_EXPRESSION_NOT_EXECUTION_PROOF")
        part(context, ref, key, if (waking) "waking" else "note", content)
        withContext(Dispatchers.IO) {
            val store = CompanionStore(context)
            store.activityWrite(ref.role, "activity", ref.id, "private") { old ->
                requireNotNull(old).apply {
                    put("note_preview", text.take(240))
                    put("has_note", true)
                }
            }
            if (waking) {
                val rest = CategoryEvents.row(InternalStateStore(context).read(ref.role), "rest", "current")
                store.activityWrite(ref.role, "waking_note", ref.id + ":" + key, "private") { old ->
                    old?.put("withdrawn", false) ?: base(ref, "waking_note", ref.id + ":" + key, "private").put("kind", "waking").put("content", content)
                        .put("rest_version", rest?.opt("version") ?: JSONObject.NULL)
                        .put("rest_source", rest?.opt("frame_id") ?: JSONObject.NULL)
                        .put("rest_link_status", if (rest == null) "not_recorded" else "existing_adopted_rest")
                }
            }
        }
    }

    suspend fun withdraw(context: Context, ref: Ref, key: String) = withContext(Dispatchers.IO) {
        val id = ref.id + ":" + key
        val store = CompanionStore(context)
        store.activityWrite(ref.role, "activity_part", id, ref.id) { old ->
            requireNotNull(old).put("withdrawn", true).put("version", old.getInt("version") + 1)
        }
        val row = store.activityRead(ref.role, "activity_part", id)
        if (row?.optString("kind") == "waking") store.activityWrite(ref.role, "waking_note", id, "private") { old ->
            requireNotNull(old).put("withdrawn", true).put("version", old.getInt("version") + 1)
        }
        store.activityWrite(ref.role, "activity", ref.id, "private") { old ->
            requireNotNull(old).apply { remove("note_preview"); remove("has_note") }
        }
        // Keep the original paragraph and mark a provider rollback; never present it as final.
    }

    suspend fun operation(context: Context, ref: Ref, id: String, tool: String, result: ToolResult?) {
        val status = result?.executionStatus ?: if (result == null) "started" else if (result.success) "completed" else "failed"
        part(context, ref, "tool:$id:$status", "operation", JSONObject().put("tool", tool)
            .put("status", status).put("action_id", result?.subjectActionId ?: JSONObject.NULL)
            .put("receipt", result?.result?.toString() ?: JSONObject.NULL)
            .put("error", result?.error ?: JSONObject.NULL))
    }

    suspend fun message(context: Context, ref: Ref, timestamp: Long, text: String) {
        // A persisted edit is a new version of the same expression, not a replayed tool
        // or an extra contact. The archive retains the previous complete message.
        withContext(Dispatchers.IO) {
            val id = ref.id + ":message:" + timestamp
            CompanionStore(context).activityWrite(ref.role, "activity_part", id, ref.id) { old ->
                (old ?: base(ref, "activity_part", id, ref.id)).put("kind", "message").put("content",
                    JSONObject().put("timestamp", timestamp).put("chat", ref.chat).put("text", text)
                        .put("saved", true).put("read", JSONObject.NULL))
            }
        }
        withContext(Dispatchers.IO) {
            val store = CompanionStore(context)
            val id = ref.id + ":message:" + timestamp
            store.activityWrite(ref.role, "activity_contact", id, ref.id) { old ->
                old ?: base(ref, "activity_contact", id, ref.id).put("timestamp", timestamp).put("chat", ref.chat)
            }
            // Do not open a second SQLite connection inside the head's write transaction.
            val count = store.activityCount(ref.role, "activity_contact", ref.id)
            store.activityWrite(ref.role, "activity", ref.id, "private") { old ->
                requireNotNull(old).put("message_count", maxOf(old.optLong("message_count"), count))
            }
        }
    }

    /** Called only after a real chat-store write, never from provisional rendering. */
    suspend fun savedMessage(context: Context, chat: String, message: com.ai.assistance.operit.data.model.ChatMessage) {
        if (message.sender != "ai" || message.contentStream != null || message.content.isBlank()) return
        val ref = current(chat) ?: return
        try {
            val metadata = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chat)
            if (metadata?.characterCardId != ref.role || !metadata.characterGroupId.isNullOrBlank()) return
            ActivityNotes.message(context, ref, message.timestamp, message.content)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) {
            com.ai.assistance.operit.util.AppLogger.e("ActivityNotes", "消息已保存，活动索引未写入；不能重发消息", failure)
        }
    }

    suspend fun schedule(context: Context, ref: Ref, saved: JSONObject) {
        val version = saved.optString("generation").ifBlank { InnerStructure.hash(saved) }
        part(context, ref, "schedule:$version", "schedule", JSONObject(saved.toString()).put("saved", true))
    }

    suspend fun finish(context: Context, ref: Ref, status: String) = withContext(Dispatchers.IO) {
        CompanionStore(context).activityWrite(ref.role, "activity", ref.id, "private") { old ->
            requireNotNull(old).put("status", status).put("finished_at", System.currentTimeMillis())
        }
    }

    suspend fun started(context: Context, ref: Ref) = withContext(Dispatchers.IO) {
        CompanionStore(context).activityWrite(ref.role, "activity", ref.id, "private") { old ->
            requireNotNull(old).put("status", "started")
        }
    }

    fun page(context: Context, role: String, kind: String, id: String?, offset: Int): JSONObject {
        require(kind in kinds && offset >= 0)
        if (kind != "activity_part" && id != null) return JSONObject().put("item",
            CompanionStore(context).activityRead(role, kind, id) ?: JSONObject.NULL)
        val rows = CompanionStore(context).activityPage(role, kind, if (kind == "activity_part") requireNotNull(id) else "private", offset)
        return JSONObject().put("items", JSONArray(rows.take(8))).put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }

    fun materials(context: Context, role: String): List<JSONObject> =
        CompanionStore(context).activityPage(role, "activity", "private", 0).take(3).flatMap { activity ->
            CompanionStore(context).activityPage(role, "activity_part", activity.getString("id"), 0).take(8)
                .filter { !it.optBoolean("withdrawn") && it.getString("kind") in setOf("note", "waking", "message") }
                .map(::material)
        }

    fun material(row: JSONObject): JSONObject {
        val text = row.getJSONObject("content").getString("text")
        return JSONObject().put("id", "material:activity:" + row.getString("id") + ":" + row.getInt("version"))
                        .put("type", if (row.getString("kind") == "waking") "waking_note_not_reality" else "activity_expression")
                        .put("owner", row.getString("owner")).put("author", row.getString("owner")).put("at", row.getLong("at"))
                        .put("text", text.take(600)).put("excerpt", text.length > 600)
                        .put("status", "retrieved").put("not_new_occurrence", true).put("subject_private_material", true)
                        .put("locator", JSONObject().put("kind", "activity_part").put("id", row.getString("activity_id")))
    }

    val instructions = """
        [活动随记的可见范围]
        在正常活动中，用 <activity_note>…</activity_note> 留下当时愿意让园园在活动卡片看到的第一人称随记。
        可以在使用工具前后分别留下几段，也可以没有工具、没有消息；只写此刻实际想表达的关注、感受和想法，不重构隐藏推理，不替工具宣告成功。
        用完整闭合段落，已完成段落会及时保存；自然表达，不必写工作汇报，不强制安排或联系。决定不公开时可写 <activity_private/>，不要把保密正文放进公开标签。
        以前的 companion_note 仍只给自己看，既有内容不会被公开。没有可见随记会如实显示未留存，不自动当作保密。
        在与休息/醒来相关的正常活动中，可另用 <waking_note>…</waking_note> 写园园可在私域查看的梦醒随记。
        可以只有印象或感觉，也可以没有梦；不需要生成完整梦境，不补故事，不把梦中情节当现实。想分享不等于发过消息。
        这些是本次明确写下的随记，不自动改写内在状态；后续理解仍走已有候选采用流程。
    """.trimIndent()
}
