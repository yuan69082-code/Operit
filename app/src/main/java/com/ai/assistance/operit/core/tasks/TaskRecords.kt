package com.ai.assistance.operit.core.tasks

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure record operations shared by private and group boards; storage remains in the owning scope. */
object TaskRecords {
    val statuses = linkedMapOf("open" to "待确认", "checking" to "了解中", "waiting_evidence" to "等资料",
        "ready" to "待行动", "doing" to "进行中", "review" to "待验收", "waiting_effect" to "等待结果",
        "paused" to "暂缓", "done" to "已完成", "archived" to "已归档")
    val inactiveStatuses = setOf("paused", "done", "archived")
    val fields = setOf("title", "detail", "priority", "horizon", "status", "assignee", "conclusion",
        "completed", "next", "needs_decision", "category", "batch", "evidence", "linked_ids", "urgency")

    fun normalize(row: JSONObject): JSONObject = JSONObject(row.toString()).apply {
        if (!has("priority")) put("priority", if (optString("urgency") == "urgent") "P1" else "P2")
        if (!has("author")) put("author", "unknown")
        if (!has("version")) put("version", 0)
    }

    fun save(rows: JSONArray, actor: String, id: String?, patch: JSONObject, members: Set<String>, now: Long): JSONObject {
        require(patch.keys().asSequence().all { it in fields || it == "expected_version" }) { "包含不支持的待办字段" }
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("id") == id }
        require(id == null || index != null) { "待办不存在" }
        require(index != null || rows.length() < 300) { "待办已满，请先整理" }
        val row = if (index == null) JSONObject().put("id", UUID.randomUUID().toString())
            .put("author", actor).put("created_at", now).put("status", "open").put("priority", "P2")
            .put("horizon", "short").put("version", 0) else normalize(rows.getJSONObject(index))
        check(!patch.has("expected_version") || patch.getInt("expected_version") == row.getInt("version")) { "待办已被更新，请刷新后再保存" }
        patch.keys().asSequence().filter { it in fields }.forEach { row.put(it, patch.get(it)) }
        if (patch.has("urgency") && !patch.has("priority")) {
            require(patch.getString("urgency") in setOf("urgent", "normal"))
            row.put("priority", if (patch.getString("urgency") == "urgent") "P1" else "P2")
        }
        require(row.optString("title").isNotBlank() && row.optString("title").length <= 160) { "标题需为 1–160 字" }
        require(row.optString("priority") in setOf("P0", "P1", "P2", "P3")) { "优先级须为 P0–P3" }
        require(row.optString("status") in statuses) { "未知待办状态" }
        require(row.optString("horizon") in setOf("short", "long")) { "时间线须为 short/long" }
        val assignee = row.optString("assignee")
        require(assignee.isBlank() || assignee == "user" || assignee in members) { "接手人不属于此待办范围" }
        fields.filter { it !in setOf("linked_ids") }.forEach { require(row.optString(it).length <= 4000) { "$it 最多 4000 字" } }
        if (row.has("linked_ids")) {
            val links = row.getJSONArray("linked_ids")
            val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
            require(links.length() <= 30 && (0 until links.length()).all { links.getString(it) in ids && links.getString(it) != row.getString("id") }) { "只能关联此列表中的其他待办" }
        }
        row.put("urgency", if (row.getString("priority") in setOf("P0", "P1")) "urgent" else "normal")
        row.put("updated", now).put("updated_by", actor).put("version", row.getInt("version") + 1)
        if (row.optString("status") in inactiveStatuses) {
            row.optJSONObject("dispatch")?.let { dispatch ->
                if (dispatch.optString("state") == "pending") {
                    dispatch.put("state", "cancelled").put("detail", "事项已暂停、完成或归档，取消待执行的提交")
                    history(row, actor, "取消提交", dispatch.getString("detail"), now)
                }
                dispatch.remove("followup_at"); dispatch.remove("followup_by"); dispatch.remove("followup_recipient")
            }
        }
        history(row, actor, "修改", patch.toString(), now)
        if (index == null) rows.put(row) else rows.put(index, row)
        return row
    }
    // Summaries are cheap to list; full notes are fetched only for the requested item.
    fun brief(row: JSONObject): JSONObject = JSONObject().apply {
        listOf("id", "title", "author", "assignee", "priority", "horizon", "status", "category", "batch", "version", "updated").forEach {
            if (row.has(it)) put(it, row.get(it).let { value -> if (value is String) value.take(200) else value })
        }
        put("has_decision", row.optString("needs_decision").isNotBlank())
        row.optJSONObject("dispatch")?.let { put("dispatch_state", it.optString("state")) }
    }
    fun detail(row: JSONObject, historyOffset: Int): JSONObject = JSONObject(row.toString()).apply {
        require(historyOffset >= 0)
        val history = row.optJSONArray("history") ?: JSONArray()
        val newest = (history.length() - 1 downTo 0).map { history.getJSONObject(it) }
        put("history", JSONArray(newest.drop(historyOffset).take(5)))
        put("history_total", history.length())
        put("next_history_offset", if (historyOffset + 5 < history.length()) historyOffset + 5 else JSONObject.NULL)
    }
    fun claim(row: JSONObject, event: String, now: Long): Boolean {
        val dispatch = row.optJSONObject("dispatch") ?: return false
        if (dispatch.optString("id") != event || dispatch.optString("state") != "pending" ||
            dispatch.optLong("at") > now || row.optString("status") in inactiveStatuses) return false
        dispatch.put("state", "running").put("detail", "接手助手正在查看最新记录").put("updated", now)
        history(row, "system", "开始处理", "提交已接收", now)
        return true
    }
    fun history(row: JSONObject, actor: String, kind: String, text: String, now: Long) {
        val old = row.optJSONArray("history") ?: JSONArray()
        val entries = JSONArray()
        for (i in maxOf(0, old.length() - 79) until old.length()) entries.put(old.get(i))
        entries.put(JSONObject().put("actor", actor).put("kind", kind).put("text", text.take(5000)).put("at", now))
        row.put("history", entries)
    }
    fun comment(row: JSONObject, actor: String, text: String, now: Long) {
        require(text.isNotBlank() && text.length <= 4000) { "批注需为 1–4000 字" }
        history(row, actor, "批注", text, now)
        row.put("updated", now).put("version", row.optInt("version") + 1)
    }
    fun submit(row: JSONObject, actor: String, recipient: String, minutes: Int, now: Long) {
        require(recipient.isNotBlank() && recipient != "user") { "请指定要继续处理此事的助手" }
        require(minutes in 0..43200) { "安排时间须为 0–43200 分钟" }
        check(row.optString("status") !in inactiveStatuses) { "暂缓、完成或归档的事项请先重新打开" }
        val current = row.optJSONObject("dispatch")
        if (current?.optString("state") == "running") {
            check(actor == "user" || actor == current.optString("recipient") && minutes >= 1) { "本项正在处理；接手助手可安排至少一分钟后的继续" }
            current.put("followup_at", now + minutes * 60_000L).put("followup_by", actor).put("followup_recipient", recipient)
            history(row, actor, "安排继续", "本轮完成后交给 $recipient", now)
            return
        }
        check(current?.optString("state") != "pending") { "本项已提交，可在记录中查看进度" }
        row.put("dispatch", JSONObject().put("id", UUID.randomUUID().toString()).put("recipient", recipient)
            .put("by", actor).put("state", "pending").put("at", now + minutes * 60_000L))
        history(row, actor, "提交", "交给 $recipient，$minutes 分钟后处理", now)
    }
}
