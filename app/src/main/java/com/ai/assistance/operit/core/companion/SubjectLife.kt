package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Technical lifecycle indexes in the existing archive. Rest remains authoritative in the
 * report journal. No model call or psychological change occurs on read. */
internal class SubjectLife(private val context: Context) {
    class Deferred : Exception("触发已可靠暂存，尚未执行主体判断")
    private val store = CompanionStore(context)
    fun rest(role: String): JSONObject? = if (!InternalStateStore(context).enabled(role) ||
        com.ai.assistance.operit.core.engine.EngineWindow.associated(context, role)) null else
        CategoryEvents.row(InternalStateStore(context).read(role), "rest", "current")

    fun buffer(role: String, chat: String, id: String, source: String, text: String): JSONObject {
        val prior = store.activityRead(role, "life_event", id)
        if (prior != null) {
            check(prior.getString("source_chat") == chat && prior.getString("source") == source && prior.getString("text") == text)
            return prior
        }
        val parent = if (source == "user_wake_request") "user_request" else if (SubjectRestPolicy.admission(rest(role)?.getJSONObject("content"), source, System.currentTimeMillis()) in setOf("judge", "wake")) "interrupt" else "pending"
        return store.activityWrite(role, "life_event", id, parent) { old ->
            if (old != null) {
                check(old.getString("source_chat") == chat && old.getString("source") == source && old.getString("text") == text) {
                    "同一事件标识不能替换原始内容"
                }
                old
            } else JSONObject().put("storage_kind", "life_event").put("id", id).put("owner", role)
                .put("source_chat", chat).put("source", source).put("text", text).put("at", System.currentTimeMillis())
                .put("version", 1).put("status", "pending").put("parent", parent)
        }
    }

    fun defer(role: String, chat: String, id: String, source: String, text: String): Boolean {
        val record = rest(role) ?: return false
        val content = record.getJSONObject("content")
        if (content.getString("state") != "resting") return false
        if (source == "internal" && !content.optBoolean("defer_internal", true)) return false
        buffer(role, chat, id, source, text)
        return true // One dispatcher owns the subsequent admission, including already-due sleep.
    }

    suspend fun requestWake(role: String, chat: String): JSONObject {
        check(!InternalStateStore(context).requireRecipient(role, chat))
        check(rest(role)?.getJSONObject("content")?.optString("state") == "resting")
        check(busy(role) == null || busy(role) == "梦境活动") { "正在处理本次活动，请等本轮结束" }
        val existing = store.activityPage(role, "life_event", "user_request", 0).firstOrNull {
            it.optString("source") == "user_wake_request" && it.optString("status") == "pending"
        }
        if (existing != null) return existing
        return buffer(role, chat, "wake-request:${java.util.UUID.randomUUID()}", "user_wake_request",
            "用户在首页明确请求醒来或恢复续接。先核对已完成表达和真实结果，再决定如何回应；不是系统自动判定已醒。")
    }

    fun page(role: String, kind: String, offset: Int, id: String? = null): JSONObject {
        SubjectContracts.queryId(kind, id)
        require(kind in setOf("life_event", "life_activity", "dream_process", "dream_step") && offset >= 0)
        if (id != null && kind != "dream_step") return JSONObject().put("item", store.activityRead(role, kind, id) ?: JSONObject.NULL)
        val rows = store.activityPage(role, kind, if (kind == "dream_step") requireNotNull(id) else if (kind == "life_event") null else "pending", offset)
        return JSONObject().put("items", JSONArray(rows.take(8))).put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }

    fun acknowledge(role: String, ids: List<String>) {
        ids.forEach { id -> store.activityWrite(role, "life_event", id, "handled") { old ->
            requireNotNull(old).put("status", "handled").put("handled_at", System.currentTimeMillis()).put("parent", "handled")
        } }
    }

    fun inputCompleted(role: String, id: String) {
        if (store.activityRead(role, "life_event", "input:$id") != null) acknowledge(role, listOf("input:$id"))
    }

    fun checkpoint(ref: ActivityNotes.Ref, event: String, phase: String, exploration: Boolean = false, toolsUsed: Int? = null) {
        store.activityWrite(ref.role, "life_activity", ref.id, if (phase == "paused") "pending" else phase) { old ->
            (old ?: JSONObject().put("id", ref.id).put("owner", ref.role).put("source_chat", ref.chat)
                .put("event", event).put("exploration", exploration).put("at", System.currentTimeMillis()).put("version", 1))
                .put("phase", phase).put("parent", if (phase == "paused") "pending" else phase)
                .apply { if (toolsUsed != null) put("tools_used", toolsUsed) }
        }
    }
    fun isExploration(role: String, activity: String?) = activity != null &&
        store.activityRead(role, "life_activity", activity)?.optBoolean("exploration") == true
    fun toolsUsed(role: String, activity: String?) = activity?.let { store.activityRead(role, "life_activity", it)?.optInt("tools_used") } ?: 0

    suspend fun recordRequest(ref: ActivityNotes.Ref, frame: InternalStateFrame, round: Int) {
        if (store.activityRead(ref.role, "life_activity", ref.id) == null) return
        val key = "request:${frame.id}:$round"
        ActivityNotes.part(context, ref, key, "request", JSONObject().put("frame_id", frame.id)
            .put("state_version", frame.stateVersion).put("semantic_revision", frame.revision)
            .put("round", round).put("material", frame.materialText)
            .put("source_ids", JSONArray(frame.materialSources.keys.toList())).put("status", "response_started"))
        store.activityWrite(ref.role, "life_activity", ref.id, "executing") { old ->
            requireNotNull(old).put("parent", "executing").put("last_request_part", key)
                .put("last_frame_id", frame.id).put("last_round", round).put("state_version", frame.stateVersion)
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
        // Preserve the existing external-engine window's ownership. Dormant local
        // arrangements must not create a second model runner after that binding.
        if (com.ai.assistance.operit.core.engine.EngineWindow.associated(context, role)) return null
        val r = rest(role)
        val c = r?.getJSONObject("content")
        if (c?.optString("state") == "resting") {
            val session = c.optString("session_id", "legacy-${r.getInt("version")}")
            if (SubjectRestPolicy.due(c, now)) {
                return claim(role, chat, "wake:$session:${r.getInt("version")}",
                    "你先前安排的休息时段已到期。当前仍是休息状态，请决定醒来或延长（新结束安排）。这不是新的现实经历。用companion_life events分页取回暂存事件，决定哪些已处理并ack；旧内部预约尚未改期，按需要重新安排。休息安排：$c")
                    ?: nextInterrupt(role, chat, r, now)
            }
            nextInterrupt(role, chat, r, now)?.let { return it }
            val resumedDream = store.activityPage(role, "dream_process", "ready", 0).firstOrNull()
            if (resumedDream != null) return Work(role, chat, resumedDream.getString("id"), resumedDream.getJSONObject("plan").toString(), dream = true)
            if (c.has("dream_plan")) claim(role, chat, "dream:$session", c.getJSONObject("dream_plan").toString(), dream = true)?.let { return it }
            if (c.optString("consolidation").isNotBlank()) claim(role, chat, "review:$session",
                "你在休息安排中选择了一次经验整理：${c.getString("consolidation")}。只取实际提供的增量材料，按需检索本地记忆或资料。可形成experience/direction候选或保留不确定；不得把整理建议冒充已决定的计划，不自动安排预约。")?.let { return it }
            return null
        }
        val input = SubjectMessageInbox(context).next(role)
        if (input != null) return Work(role, input.getString("source_chat"), "input:${input.getString("id")}", input.getString("text"), input = input)
        val resume = store.activityPage(role, "life_activity", "ready", 0).firstOrNull()
        if (resume != null) return claim(role, chat, "resume:${resume.getString("id")}:${resume.getInt("version")}",
            "续接同一活动。已执行的工具与回执必须先核对，未知结果不能盲目重放。原方向与新的决定保存在life_activity原记录；未进入本轮预算时须按ID读取，不假定已读。\n" +
                "可用companion_internal_state get section=action或activity_part按需读取已保存步骤。活动ID：${resume.getString("id")}；最后已提供的请求：${resume.optString("last_request_part", "尚未取得响应")}。本次先核对原材料、原随记和回执后选择接续点；不是把旧请求从头再执行。", resume.getString("id"))
        val events = store.activityQueuePage(role, "life_event", listOf("interrupt", "pending", "user_request")).take(8)
        val first = events.firstOrNull() ?: return null
        val work = claim(role, chat, "event:${first.getString("id")}", "醒后待处理材料（不是新发生），本次索引：" +
            JSONArray(events.map { JSONObject().put("id", it.getString("id")).put("source", it.getString("source")) }) +
            "\n正文按统一材料预算提供；不足时用companion_life events按ID或分页读取原文。实际处理后才ack；保存过的表达或操作不能重复执行。")
        if (work != null) events.forEach { event -> store.activityWrite(role, "life_event", event.getString("id"), "offered") {
            requireNotNull(it).put("parent", "offered").put("status", "offered_not_acknowledged")
        } }
        return work
    }

    private fun admittedInterrupt(role: String, r: JSONObject, now: Long): JSONObject? {
        val c = r.getJSONObject("content")
        val alreadyJudged = store.activityRead(role, "life_run", "interrupt:${c.optString("session_id")}:${r.getInt("version")}") != null
        val rows = store.activityPage(role, "life_event", "user_request", 0) + store.activityPage(role, "life_event", "interrupt", 0)
        return rows.firstOrNull { it.getString("source") == "user_wake_request" ||
            (!alreadyJudged && SubjectRestPolicy.admission(c, it.getString("source"), now) in setOf("judge", "wake")) }
    }

    fun shouldPauseDream(role: String, now: Long): Boolean {
        val r = rest(role) ?: return true
        val c = r.getJSONObject("content")
        return c.optString("state") != "resting" || SubjectRestPolicy.due(c, now) || admittedInterrupt(role, r, now) != null
    }

    private fun nextInterrupt(role: String, chat: String, r: JSONObject, now: Long): Work? {
        val c = r.getJSONObject("content")
        val selected = admittedInterrupt(role, r, now) ?: return null
        // A subject-selected source admits one bounded judgement for this rest version.
        // A new rest decision may change the conditions; rereads never restart the old judgement.
        val key = if (selected.getString("source") == "user_wake_request") selected.getString("id") else
            "interrupt:${c.optString("session_id")}:${r.getInt("version")}"
        val work = claim(role, chat, key,
            "休息期间你已选择关注的来源或用户明确的唤醒请求到达。是否醒来、简短处理或继续休息由你决定；这不表示自动提高普通消息的重要性。来源：${selected.getString("source")}；事件ID：${selected.getString("id")}。" +
                "原文在本轮材料中，若未选入，用companion_life events及id取回后再判断。")
        if (work != null) store.activityWrite(role, "life_event", selected.getString("id"), "offered") {
            requireNotNull(it).put("parent", "offered").put("status", "offered_not_acknowledged")
        }
        return work
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
    fun dreamChoice(role: String, id: String, choice: String, note: String): JSONObject {
        require(choice in setOf("resume", "retry_step", "end") && note.isNotBlank())
        check(rest(role)?.getJSONObject("content")?.optString("state") == "resting" || choice == "end") { "继续梦境须仍在主体选择的休息中" }
        val prior = SubjectContracts.dreamProcess(store.activityRead(role, "dream_process", id))
        check(prior.getString("status") in setOf("failed", "interrupted")) { "只有已中断的梦可以继续；不能重复已完成的梦" }
        val step = prior.getInt("next_step")
        val pending = store.activityRead(role, "dream_step", "$id:$step")
        if (pending != null && choice == "resume") error("此步骤已发起且结果不完整；须明确选择retry_step重新生成该步骤或end保留片段")
        if (pending != null && choice == "retry_step") store.activityWrite(role, "dream_step", "$id:$step", id) {
            requireNotNull(it).put("status", "retry_authorized").put("retry_decision", note)
        }
        return store.activityWrite(role, "dream_process", id, if (choice == "end") "pending" else "ready") {
            requireNotNull(it).put("status", if (choice == "end") "ended" else "resume_authorized")
                .put("parent", if (choice == "end") "pending" else "ready")
                .put("continuation_decision", note).put("decided_at", System.currentTimeMillis())
        }
    }
    fun recover(role: String) {
        var processOffset = 0
        while (true) {
            val rows = store.activityPage(role, "dream_process", "pending", processOffset)
            rows.take(8).filter { it.optString("status") == "running" }.forEach { row ->
                store.activityWrite(role, "dream_process", row.getString("id"), "pending") {
                    requireNotNull(it).put("status", "interrupted").put("interruption", "process_recreated")
                }
            }
            if (rows.size <= 8) break
            processOffset += 8
        }
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
        val parents = if (kind == "life_event") listOf("pending", "interrupt", "offered", "user_request") else listOf("pending", "ready")
        val count = parents.sumOf { store.activityCount(role, kind, it) }
        val cursor = InternalStateStore(context).read(role).optLong("material_query_cursor")
        val offset = (cursor % ((count + 2) / 3).coerceAtLeast(1) * 3).toInt()
        store.activityQueuePage(role, kind, parents, offset).take(3).map { material(kind, it) }
    }

    companion object {
        private val failures = ConcurrentHashMap<String, String>()
        fun failure(role: String): String? = failures[role]
        fun recordFailure(role: String, stage: String, error: Exception) {
            failures[role] = "$stage：${error.javaClass.simpleName}；原记录保留，未自动重做。"
            com.ai.assistance.operit.util.AppLogger.e("SubjectLife", stage, error)
        }
        fun clearFailure(role: String) { failures.remove(role) }
        fun material(kind: String, row: JSONObject): JSONObject =
            JSONObject().put("id", "material:$kind:${row.getString("id")}:${row.optInt("version", 1)}")
                .put("type", kind).put("owner", row.getString("owner")).put("author", row.optString("source", row.getString("owner"))).put("at", row.getLong("at"))
                .put("text", row.toString().take(1200)).put("excerpt", row.toString().length > 1200)
                .put("status", "retrieved").put("not_new_occurrence", true).put("subject_private_material", true)
                .put("locator", JSONObject().put("kind", kind).put("id", row.getString("id")).put("version", row.getInt("version")))
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
