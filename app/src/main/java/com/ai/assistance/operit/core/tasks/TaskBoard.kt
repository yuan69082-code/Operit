package com.ai.assistance.operit.core.tasks

import android.content.Context
import com.ai.assistance.operit.core.companion.CompanionStore
import com.ai.assistance.operit.core.group.GroupStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

class TaskBoard(context: Context, val scope: String, val group: Boolean) {
    private val companionStore = CompanionStore(context)
    private val groups = GroupStore(context)
    private val key = if (group) "tasks" else "todos"
    private val role = scope.takeIf { !group && it.startsWith("role:") }?.removePrefix("role:")
    val usesArchive get() = role?.let { companionStore.researchActive(it) } == true
    companion object { val revision = MutableStateFlow(0L) }
    private fun read() = if (group) groups.read(scope) else companionStore.read(scope)
    private fun update(edit: (JSONObject) -> Unit) {
        if (group) groups.update(scope, edit) else companionStore.update(scope, edit)
        revision.update { it + 1 }
    }
    fun list(offset: Int = 0): List<JSONObject> {
        if (usesArchive) return page(offset).getJSONArray("items").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
        val rows = read().optJSONArray(key) ?: JSONArray()
        return (0 until rows.length()).map { TaskRecords.normalize(rows.getJSONObject(it)) }
    }
    fun page(offset: Int = 0, filter: String = "", stage: String = "", assignee: String = "*", category: String = "", query: String = "", limit: Int = 20): JSONObject {
        require(offset >= 0 && (stage.isBlank() || stage in TaskRecords.statuses))
        if (usesArchive) return companionStore.withResearch(requireNotNull(role)) { it.researchPage("task", offset, limit, filter = filter, stage = stage, assignee = assignee, category = category, query = query) }
        val rows = list().filter { row ->
            (if (filter == "all") TaskRecords.tab(row) != "archived" else filter.isBlank() || TaskRecords.tab(row) == filter) &&
                (stage.isBlank() || row.optString("status") == stage) && (assignee == "*" || row.optString("assignee") == assignee) &&
                (category.isBlank() || row.optString("category") == category) &&
                (query.isBlank() || listOf("title", "detail", "id", "conclusion", "next", "evidence", "origin", "goal", "blocker", "plan", "materials").any { row.optString(it).contains(query, true) })
        }
        return JSONObject().put("items", JSONArray(rows.drop(offset).take(limit))).put("total", rows.size)
            .put("next_offset", if (offset + limit < rows.size) offset + limit else JSONObject.NULL)
    }
    fun countOpen(): Long = if (usesArchive) listOf("todo", "doing", "blocked").sumOf { page(filter = it, limit = 1).getLong("total") }
        else list().count { it.optString("status") !in setOf("done", "archived") }.toLong()
    fun openPreview(): List<JSONObject> = if (usesArchive) companionStore.withResearch(requireNotNull(role)) {
        val rows = it.researchPage("task", 0, state = "active_task").getJSONArray("items")
        (0 until rows.length()).map { i -> rows.getJSONObject(i) }
    } else list().filter { it.optString("status") !in setOf("done", "archived") }
    fun save(actor: String, id: String?, patch: JSONObject, members: Set<String>): JSONObject {
        if (usesArchive) return companionStore.withResearch(requireNotNull(role)) { archive ->
            val rows = JSONArray()
            if (id != null) rows.put(archive.researchRow("task", id) ?: error("待办不存在"))
            val saved = TaskRecords.save(rows, actor, id, patch, members, System.currentTimeMillis(), true) { archive.researchRow("task", it) != null }
            archive.researchWrite("task", saved).also { revision.update { it + 1 } }
        }
        lateinit var saved: JSONObject
        update { data ->
            val rows = data.optJSONArray(key) ?: JSONArray()
            saved = TaskRecords.save(rows, actor, id, patch, members, System.currentTimeMillis())
            data.put(key, rows)
        }
        return saved
    }
    private fun row(id: String, requireOpen: Boolean = false, change: (JSONObject) -> Unit): JSONObject {
        if (usesArchive) return companionStore.withResearch(requireNotNull(role)) { archive ->
            val row = archive.researchRow("task", id) ?: error("待办不存在")
            row.put("_incremental_history", true)
            change(row)
            TaskRecords.receipt(archive.researchWrite("task", row)).also { revision.update { it + 1 } }
        }
        lateinit var saved: JSONObject
        update { data ->
            if (group && requireOpen) check(data.optBoolean("group_exists", true)) { "群已结束，不能提交执行" }
            val rows = data.optJSONArray(key) ?: error("待办不存在")
            val i = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == id } ?: error("待办不存在")
            val row = TaskRecords.normalize(rows.getJSONObject(i))
            change(row); rows.put(i, row)
            // Return the exact write, not a later list() that another writer may have changed.
            saved = TaskRecords.receipt(row)
        }
        return saved
    }
    fun comment(id: String, actor: String, text: String) = row(id) { TaskRecords.comment(it, actor, text, System.currentTimeMillis()) }
    fun submit(id: String, actor: String, recipient: String, members: Set<String>, minutes: Int) = row(id, requireOpen = true) {
        require(recipient in members) { "接手助手不在此范围" }
        TaskRecords.submit(it, actor, recipient, minutes, System.currentTimeMillis())
    }
    fun get(id: String, historyOffset: Int = 0): JSONObject {
        if (usesArchive) return companionStore.withResearch(requireNotNull(role)) { archive ->
            val row = archive.researchRow("task", id) ?: error("待办不存在")
            val history = archive.researchHistory("task", id, historyOffset)
            history.keys().forEach { row.put(it, history.get(it)) }
            row
        }
        return TaskRecords.detail(list().firstOrNull { it.getString("id") == id } ?: error("待办不存在"), historyOffset)
    }
    internal fun deleteArchived(id: String) = companionStore.withResearch(requireNotNull(role)) { archive ->
        check(archive.researchRow("task", id) != null) { "待办不存在" }
        archive.researchDeleteTask(id); revision.update { it + 1 }
    }
    fun running(): List<JSONObject> = if (usesArchive) companionStore.withResearch(requireNotNull(role)) {
        val rows = it.researchPage("task", 0, state = "running").getJSONArray("items")
        (0 until rows.length()).map { i -> rows.getJSONObject(i) }
    } else list().filter { it.optJSONObject("dispatch")?.optString("state") == "running" }

    // Claim and read the newest notes inside the same storage lock. A removed/cancelled event
    // cannot be resurrected by a worker holding an older snapshot.
    fun claim(id: String, event: String): JSONObject? {
        if (usesArchive) return companionStore.withResearch(requireNotNull(role)) { archive ->
            val row = archive.researchRow("task", id) ?: return@withResearch null
            row.put("_incremental_history", true)
            if (!TaskRecords.claim(row, event, System.currentTimeMillis())) return@withResearch null
            val saved = archive.researchWrite("task", row)
            val history = archive.researchHistory("task", id, 0).getJSONArray("history")
            saved.put("history", JSONArray((history.length() - 1 downTo 0).map { history.getJSONObject(it) }))
            revision.update { it + 1 }; saved
        }
        var claimed: JSONObject? = null
        update { data ->
            if (group && !data.optBoolean("group_exists", true)) return@update
            val rows = data.optJSONArray(key) ?: return@update
            val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).optString("id") == id } ?: return@update
            val row = TaskRecords.normalize(rows.getJSONObject(index))
            if (TaskRecords.claim(row, event, System.currentTimeMillis())) {
                rows.put(index, row)
                claimed = JSONObject(row.toString())
            }
        }
        return claimed
    }
    fun dispatchState(id: String, event: String, state: String, detail: String) = row(id) {
        val dispatch = it.getJSONObject("dispatch")
        check(dispatch.getString("id") == event) { "提交记录已变更" }
        dispatch.put("state", state).put("detail", detail).put("updated", System.currentTimeMillis())
        TaskRecords.history(it, "system", "处理$state", detail, System.currentTimeMillis())
        if (state == "handled" && dispatch.has("followup_at") && it.optString("status") !in TaskRecords.inactiveStatuses) {
            it.put("dispatch", JSONObject().put("id", java.util.UUID.randomUUID().toString())
                .put("recipient", dispatch.getString("followup_recipient")).put("by", dispatch.getString("followup_by"))
                .put("state", "pending").put("at", dispatch.getLong("followup_at")))
        }
    }
    fun pending(): JSONObject? = if (usesArchive) companionStore.withResearch(requireNotNull(role)) {
        it.researchPage("task", 0, 1, state = "pending", due = System.currentTimeMillis()).getJSONArray("items").optJSONObject(0)
    } else list().firstOrNull {
        it.optString("status") !in TaskRecords.inactiveStatuses && it.optJSONObject("dispatch")?.let { d -> d.optString("state") == "pending" && d.optLong("at") <= System.currentTimeMillis() } == true
    }
    fun prompt(row: JSONObject): String = """
        [待办事件，范围=${if (group) "本群共同事项" else "你自己的私人事项"}]
        以下记录包含个人意愿、批注和资料，按署名区分来源；不把其他助手的想法记成自己的。
        ${JSONObject().apply {
            listOf("id", "author", "title", "priority", "status", "horizon", "assignee", "detail", "conclusion", "completed", "next", "needs_decision", "category", "batch", "evidence", "linked_ids", "version", "origin", "goal", "blocker", "plan", "materials").forEach {
                if (row.has(it)) put(it, row.get(it).let { value -> if (value is String) value.take(1600) else value })
            }
            val history = row.optJSONArray("history") ?: JSONArray()
            put("recent_notes", JSONArray((maxOf(0, history.length() - 4) until history.length()).map { JSONObject(history.getJSONObject(it).toString()).apply { put("text", optString("text").take(800)) } }))
        }.toString()}
        长内容仅提供摘录；原文可用工具 get/get_task 按 ID 查看。
        你可以自主处理自己关心的事、批注、调整计划、整理资料或暂缓，不必等待用户审批。
        只有确实需要用户选择才写 needs_decision。使用${if (group) "group_chat save_task/comment_task" else "companion_todo save/comment"}更新实际进展和下一步。
        执行结束不等于事项完成；不得编造已完成、已验证。无新内容可保持安静。
        本次已有处理提交，勿为轮询而重复提交；以后确需继续时可主动安排，但不要制造空转。
    """.trimIndent()
}
