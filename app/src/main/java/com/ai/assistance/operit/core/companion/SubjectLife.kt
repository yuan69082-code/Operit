package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Technical lifecycle indexes in the existing archive. Rest remains authoritative in the
 * report journal. No model call or psychological change occurs on read. */
internal class SubjectLife(private val context: Context) {
    private val store = CompanionStore(context)
    fun rest(role: String): JSONObject? = if (!InternalStateStore(context).enabled(role)) null else
        CategoryEvents.row(InternalStateStore(context).read(role), "rest", "current")

    fun buffer(role: String, chat: String, id: String, source: String, text: String): JSONObject {
        val prior = store.activityRead(role, "life_event", id)
        if (prior != null) {
            check(prior.getString("source_chat") == chat && prior.getString("source") == source && prior.getString("text") == text)
            return prior
        }
        return store.activityWrite(role, "life_event", id, "pending") { old ->
            if (old != null) {
                check(old.getString("source_chat") == chat && old.getString("source") == source && old.getString("text") == text) {
                    "同一事件标识不能替换原始内容"
                }
                old
            } else JSONObject().put("storage_kind", "life_event").put("id", id).put("owner", role)
                .put("source_chat", chat).put("source", source).put("text", text).put("at", System.currentTimeMillis())
                .put("version", 1).put("status", "pending").put("parent", "pending")
        }
    }

    fun defer(role: String, chat: String, id: String, source: String, text: String): Boolean {
        val record = rest(role) ?: return false
        val content = record.getJSONObject("content")
        if (content.getString("state") != "resting") return false
        buffer(role, chat, id, source, text)
        return true // One dispatcher owns the subsequent admission, including already-due sleep.
    }

    fun page(role: String, kind: String, offset: Int, id: String? = null): JSONObject {
        require(kind in setOf("life_event", "life_activity", "dream_process", "dream_step") && offset >= 0)
        if (id != null && kind != "dream_step") return JSONObject().put("item", store.activityRead(role, kind, id) ?: JSONObject.NULL)
        val rows = store.activityPage(role, kind, if (kind == "dream_step") requireNotNull(id) else "pending", offset)
        return JSONObject().put("items", JSONArray(rows.take(8))).put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }

    fun acknowledge(role: String, ids: List<String>) {
        ids.forEach { id -> store.activityWrite(role, "life_event", id, "handled") { old ->
            requireNotNull(old).put("status", "handled").put("handled_at", System.currentTimeMillis()).put("parent", "handled")
        } }
    }

    fun checkpoint(ref: ActivityNotes.Ref, event: String, phase: String) {
        store.activityWrite(ref.role, "life_activity", ref.id, if (phase == "paused") "pending" else phase) { old ->
            (old ?: JSONObject().put("id", ref.id).put("owner", ref.role).put("source_chat", ref.chat)
                .put("event", event).put("at", System.currentTimeMillis()).put("version", 1))
                .put("phase", phase).put("parent", if (phase == "paused") "pending" else phase)
        }
    }

    fun chooseActivity(role: String, id: String, choice: String, note: String): JSONObject {
        require(choice in setOf("continue", "pause", "end"))
        require(note.isNotBlank())
        return store.activityWrite(role, "life_activity", id, if (choice == "continue") "ready" else if (choice == "pause") "pending" else "ended") { old ->
            requireNotNull(old)
            require(old.getString("phase") !in setOf("completed", "ended"))
            old.put("choice", choice).put("choice_note", note).put("choice_at", System.currentTimeMillis())
                .put("parent", if (choice == "continue") "ready" else if (choice == "pause") "pending" else "ended")
                .put("phase", if (choice == "continue") "ready" else if (choice == "pause") "paused" else "ended")
        }
    }

    data class Work(val role: String, val chat: String, val id: String, val text: String, val activity: String? = null, val dream: Boolean = false,
        val input: JSONObject? = null)

    fun next(role: String, chat: String, now: Long): Work? {
        val r = rest(role)
        val c = r?.getJSONObject("content")
        if (c?.optString("state") == "resting") {
            val session = c.optString("session_id", "legacy-${r.getInt("version")}")
            if (SubjectRestPolicy.due(c, now)) return claim(role, chat, "wake:$session:${r.getInt("version")}",
                "你先前安排的休息时段已到期。当前仍是休息状态，请决定醒来或延长（新结束安排）。这不是新的现实经历。用companion_life events分页取回暂存事件，决定哪些已处理并ack；旧内部预约尚未改期，按需要重新安排。休息安排：$c")
            if (c.has("dream_plan")) return claim(role, chat, "dream:$session", c.getJSONObject("dream_plan").toString(), dream = true)
                ?: nextInterrupt(role, chat, r, now)
            return nextInterrupt(role, chat, r, now)
        }
        val input = SubjectMessageInbox(context).page(role, 0).firstOrNull { it.optString("status") == "queued" }
        if (input != null) return Work(role, input.getString("source_chat"), "input:${input.getString("id")}", input.getString("text"), input = input)
        val resume = store.activityPage(role, "life_activity", "ready", 0).firstOrNull()
        if (resume != null) return claim(role, chat, "resume:${resume.getString("id")}:${resume.getInt("version")}",
            "续接同一活动。已执行的工具与回执必须先核对，未知结果不能盲目重放。原方向：${resume.getString("event")}\n新的决定：${resume.optString("choice_note")}\n" +
                "可用companion_internal_state action/activity_part按需读取已保存步骤。活动ID：${resume.getString("id")}", resume.getString("id"))
        val events = store.activityPage(role, "life_event", "pending", 0)
        val first = events.firstOrNull() ?: return null
        return claim(role, chat, "event:${first.getString("id")}", "醒后待处理材料（不是新发生）：" + JSONArray(events.take(8)) +
            "\n按需继续分页。处理后用companion_life ack明确标记ID；保存过的表达或操作不能重复执行。")
    }

    private fun nextInterrupt(role: String, chat: String, r: JSONObject, now: Long): Work? {
        val c = r.getJSONObject("content")
        val rows = store.activityPage(role, "life_event", "pending", 0)
        val selected = rows.firstOrNull { SubjectRestPolicy.admission(c, it.getString("source"), now) in setOf("judge", "wake") } ?: return null
        // A subject-selected source admits one bounded judgement for this rest version.
        // A new rest decision may change the conditions; rereads never restart the old judgement.
        return claim(role, chat, "interrupt:${c.optString("session_id")}:${r.getInt("version")}",
            "休息期间你已选择关注的来源到达。是否醒来、简短处理或继续休息由你决定；这不表示自动提高事件重要性。" + selected.toString())
    }

    private fun claim(role: String, chat: String, id: String, text: String, activity: String? = null, dream: Boolean = false): Work? {
        var claimed = false
        store.activityWrite(role, "life_run", id, "technical") { old ->
            if (old != null) old else {
                claimed = true
                JSONObject().put("id", id).put("parent", "technical").put("owner", role).put("source_chat", chat).put("at", System.currentTimeMillis())
                    .put("status", "claimed").put("text", text).put("activity", activity ?: JSONObject.NULL).put("dream", dream)
            }
        }
        return if (claimed) Work(role, chat, id, text, activity, dream) else null
    }
    fun finish(work: Work, outcome: String) {
        if (work.input != null) return
        store.activityWrite(work.role, "life_run", work.id, "technical") { requireNotNull(it).put("status", outcome).put("finished_at", System.currentTimeMillis()) }
    }
    fun recover(role: String) {
        while (true) {
            val batch = store.activityPage(role, "life_activity", "executing", 0)
            if (batch.isEmpty()) break
            for (row in batch) checkpoint(ActivityNotes.Ref(role, row.getString("source_chat"), row.getString("id")), row.getString("event"), "paused")
        }
        var offset = 0
        while (true) {
            val rows = SubjectMessageInbox(context).page(role, offset)
            rows.take(8).filter { it.optString("status") in setOf("sending", "saved") }.forEach {
                SubjectMessageInbox(context).finish(role, it.getString("id"), "unknown")
            }
            if (rows.size <= 8) break
            offset += 8
        }
    }
    fun materials(role: String): List<JSONObject> = listOf("life_event", "life_activity").flatMap { kind ->
        store.activityPage(role, kind, "pending", 0).take(3).map { row ->
            JSONObject().put("id", "material:$kind:${row.getString("id")}:${row.optInt("version", 1)}")
                .put("type", kind).put("owner", role).put("author", row.optString("source", role)).put("at", row.getLong("at"))
                .put("text", row.toString().take(1200)).put("excerpt", row.toString().length > 1200)
                .put("status", "retrieved").put("not_new_occurrence", true).put("subject_private_material", true)
                .put("locator", JSONObject().put("kind", kind).put("id", row.getString("id")))
        }
    }

    companion object {
        private val running = ConcurrentHashMap<String, String>()
        fun started(role: String, description: String) { running[role] = description }
        fun stopped(role: String) { running.remove(role) }
        fun busy(role: String): String? = running[role]
        val instructions = """
            [活动交接]
            收到用户消息时，可以暂停并正常回复，或自己写短回复后继续原活动，或改变/结束方向。程序不替你说忙。
            已暂停活动在life_activity材料中，原工具回执仍在action与activity_part。用companion_life activity，id为活动ID，details:{choice:"continue|pause|end",note:本次决定}。continue在本轮结束腾出执行位置后续接同一活动，不从头重放。
            companion_life events/activities分页查看暂存事件/暂停活动；ack用details.ids标记实际处理的事件，不禁止以后重新认识。未读取不算已处理。安排学习仍沿用companion_explore；经验整理可安排一次明确的organize活动，不需闲置轮询。
        """.trimIndent()
    }
}
