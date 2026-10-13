package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.tasks.*
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskBoardPanel(board: TaskBoard, members: Map<String, String>, privateOwner: String? = null,
                   onFinish: ((String, String, String) -> Unit)? = null) {
    val revision by TaskBoard.revision.collectAsState()
    val groupRevision by com.ai.assistance.operit.core.group.GroupStore.revision.collectAsState()
    var refresh by remember(board.scope) { mutableIntStateOf(0) }
    val rows = remember(board.scope, revision, groupRevision, refresh) { board.list() }
    var query by remember(board.scope) { mutableStateOf("") }
    var filter by remember(board.scope) { mutableStateOf("pending") }
    var stage by remember(board.scope) { mutableStateOf("") }
    var owner by remember(board.scope) { mutableStateOf("*") }
    var category by remember(board.scope) { mutableStateOf("") }
    var selected by remember(board.scope) { mutableStateOf(emptySet<String>()) }
    var deleting by remember(board.scope) { mutableStateOf<String?>(null) }
    var editing by remember(board.scope) { mutableStateOf<JSONObject?>(null) }
    var error by remember(board.scope) { mutableStateOf<String?>(null) }
    var expanded by remember(board.scope) { mutableStateOf<String?>(null) }
    var note by remember(expanded) { mutableStateOf("") }
    var historyLimit by remember(expanded) { mutableIntStateOf(12) }
    var notify by remember(board.scope) { mutableStateOf(true) }
    val names = members + mapOf("user" to "你", "" to "待认领", "unknown" to "旧记录未署名", "system" to "软件")
    fun name(id: String) = names[id] ?: id
    fun work(action: () -> Unit) {
        try { action(); error = null; refresh++ } catch (failure: Exception) { error = failure.message }
    }
    fun recipient(row: JSONObject) = row.optString("assignee").ifBlank { privateOwner.orEmpty() }
    fun submit(row: JSONObject) {
        board.submit(row.getString("id"), "user", recipient(row), members.keys, 0)
    }
    val filtered = rows.filter { row ->
        (filter == "all" || if (filter == "archived") row.optString("status") == "archived" else row.optString("status") != "archived") &&
        (stage.isBlank() || row.optString("status") == stage) &&
        (owner == "*" || row.optString("assignee") == owner) &&
        (category.isBlank() || row.optString("category") == category) &&
        (query.isBlank() || listOf("title", "detail", "id", "conclusion", "next", "evidence").any { row.optString(it).contains(query, true) })
    }.sortedWith(compareBy<JSONObject> { it.optString("priority") }.thenByDescending { it.optLong("updated") })
    Text(if (board.group) "群里的共同打算" else "自己的打算与待办", style = MaterialTheme.typography.titleMedium)
    Text("想做的事、私人心愿、共同计划都可以放在这里。你和 AI 都能创建、批注和安排继续，必要时才等待你的决定。", style = MaterialTheme.typography.bodySmall)
    FlowRow {
        listOf("pending" to "未归档", "archived" to "已归档", "all" to "全部").forEach { (key, label) ->
            val count = rows.count { key == "all" || if (key == "archived") it.optString("status") == "archived" else it.optString("status") != "archived" }
            FilterChip(filter == key, onClick = { filter = key }, label = { Text("$label $count") })
        }
    }
    if (board.group) FlowRow {
        (mapOf("*" to "所有接手人", "" to "待认领") + members).forEach { (id, label) ->
            FilterChip(owner == id, onClick = { owner = id }, label = { Text(label) })
        }
    }
    OutlinedTextField(query, { query = it }, label = { Text("搜索标题、内容或 ID") })
    var showStatuses by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { showStatuses = true }) { Text(TaskRecords.statuses[stage] ?: "全部状态") }
        DropdownMenu(showStatuses, onDismissRequest = { showStatuses = false }) {
            (mapOf("" to "全部状态") + TaskRecords.statuses).forEach { (key, label) ->
                DropdownMenuItem(text = { Text("$label (${rows.count { key.isBlank() || it.optString("status") == key }})") },
                    onClick = { stage = key; showStatuses = false })
            }
        }
    }
    FlowRow {
        FilterChip(category.isBlank(), onClick = { category = "" }, label = { Text("全部分类") })
        rows.map { it.optString("category") }.filter { it.isNotBlank() }.distinct().forEach { value ->
            FilterChip(category == value, onClick = { category = value }, label = { Text(value) })
        }
    }
    FlowRow {
        TextButton(onClick = { selected = filtered.map { it.getString("id") }.toSet() }) { Text("选中当前列表") }
        TextButton(onClick = { selected = emptySet() }) { Text("清空选择") }
        TextButton(enabled = selected.isNotEmpty(), onClick = { work {
            rows.filter { it.getString("id") in selected }.forEach {
                board.save("user", it.getString("id"), JSONObject().put("status", "archived").put("expected_version", it.getInt("version")), members.keys)
            }; selected = emptySet()
        } }) { Text("归档选中项") }
        TextButton(onClick = { editing = JSONObject().put("assignee", privateOwner.orEmpty()).put("priority", "P2").put("status", "open").put("horizon", "short") }) { Text("新增") }
        TextButton(onClick = { refresh++ }) { Text("刷新") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    filtered.forEach { row ->
        val id = row.getString("id")
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Checkbox(id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                    TextButton(onClick = { expanded = if (expanded == id) null else id }) {
                        Text("${row.optString("priority")} · ${row.optString("title")}")
                    }
                }
                Text("${TaskRecords.statuses[row.optString("status")]} · ${if (row.optString("horizon") == "long") "长期" else "近期"} · 接手 ${name(row.optString("assignee"))}")
                Text("创建 ${name(row.optString("author"))} · ID ${id.take(8)}", style = MaterialTheme.typography.labelSmall)
                if (row.optString("needs_decision").isNotBlank()) Text("等你决定：${row.optString("needs_decision")}", color = MaterialTheme.colorScheme.primary)
                row.optJSONObject("dispatch")?.let { d -> Text("处理提交：${when(d.optString("state")) { "pending" -> "等待空闲"; "running" -> "处理中"; "handled" -> "已处理"; "cancelled" -> "已取消"; "failed" -> "未成功"; else -> "已中断" }} · ${name(d.optString("recipient"))}\n${d.optString("detail")}", style = MaterialTheme.typography.bodySmall) }
                if (expanded == id) {
                    listOf("detail" to "缘由／想做什么", "conclusion" to "当前想法与结论", "completed" to "已经做了什么",
                        "next" to "下一步", "batch" to "共同计划", "evidence" to "原始记录与参考").forEach { (key, label) ->
                        if (row.optString(key).isNotBlank()) Text("$label\n${row.optString(key)}")
                    }
                    val links = row.optJSONArray("linked_ids") ?: JSONArray()
                    if (links.length() > 0) Text("关联事项：" + (0 until links.length()).joinToString { i ->
                        rows.firstOrNull { it.getString("id") == links.getString(i) }?.optString("title") ?: links.getString(i)
                    })
                    FlowRow {
                        TextButton(onClick = { editing = JSONObject(row.toString()) }) { Text("编辑／交接") }
                        TextButton(onClick = { work {
                            val n = row.getString("priority").removePrefix("P").toInt()
                            board.save("user", id, JSONObject().put("priority", "P${(n - 1).coerceAtLeast(0)}").put("expected_version", row.getInt("version")), members.keys)
                        } }) { Text("提高优先级") }
                        TextButton(onClick = { work {
                            val n = row.getString("priority").removePrefix("P").toInt()
                            board.save("user", id, JSONObject().put("priority", "P${(n + 1).coerceAtMost(3)}").put("expected_version", row.getInt("version")), members.keys)
                        } }) { Text("降低优先级") }
                        TextButton(enabled = row.optString("status") !in TaskRecords.inactiveStatuses && recipient(row) in members,
                            onClick = { work { submit(row) } }) { Text("安排接手人继续") }
                        if (onFinish != null) TextButton(onClick = { deleting = id }) { Text("删除") }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("我的批注（AI 的批注也会分别署名）") })
                    val canNotify = row.optString("status") !in TaskRecords.inactiveStatuses && recipient(row) in members
                    Row { Checkbox(notify && canNotify, enabled = canNotify, onCheckedChange = { notify = it }); Text("保存后通知接手助手", Modifier.padding(top = 12.dp)) }
                    TextButton(enabled = note.isNotBlank(), onClick = { work {
                        board.comment(id, "user", note); note = ""
                        if (notify && canNotify && row.optJSONObject("dispatch")?.optString("state") != "pending") submit(row)
                    } }) { Text("保存批注") }
                    if (onFinish != null && row.optString("status") == "done") {
                        TextButton(onClick = { work { onFinish(id, "distill", row.optString("completed").ifBlank { row.optString("conclusion") }) } }) { Text("将实际收获沉淀到资料库") }
                    }
                    val history = row.optJSONArray("history") ?: JSONArray()
                    Text("处理历史与批注（最近80条）", style = MaterialTheme.typography.titleSmall)
                    if (history.length() > historyLimit) TextButton(onClick = { historyLimit += 12 }) { Text("查看更早记录") }
                    for (i in history.length() - 1 downTo maxOf(0, history.length() - historyLimit)) {
                        val event = history.getJSONObject(i)
                        Text("${name(event.optString("actor"))} · ${event.optString("kind")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.optLong("at")))}", style = MaterialTheme.typography.labelSmall)
                        Text(event.optString("text"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这项待办？") },
            text = { Text("待办及批注会一同删除。想保留记录可以使用归档。") },
            confirmButton = { TextButton(onClick = { work { requireNotNull(onFinish)(id, "delete", ""); deleting = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    editing?.let { original ->
        TaskEditor(original, rows, names, error, onDismiss = { editing = null }, onSave = { patch -> work {
            board.save("user", original.optString("id").takeIf { it.isNotBlank() }, patch, members.keys)
            editing = null
        } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditor(original: JSONObject, rows: List<JSONObject>, names: Map<String, String>, error: String?,
                       onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var patch by remember(original) { mutableStateOf(JSONObject().apply {
        TaskRecords.fields.filter { original.has(it) }.forEach { put(it, original.get(it)) }
        if (original.has("version")) put("expected_version", original.getInt("version"))
    }) }
    fun change(key: String, value: Any) { patch = JSONObject(patch.toString()).put(key, value) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("待办与心愿") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("title" to "标题", "detail" to "缘由／想做什么", "category" to "分类，自定义",
                "batch" to "共同计划／批次", "conclusion" to "当前想法与结论", "completed" to "已经做了什么",
                "next" to "下一步", "needs_decision" to "确实需要用户决定的内容，可留空", "evidence" to "原始记录、资料或链接").forEach { (key, label) ->
                OutlinedTextField(patch.optString(key), { change(key, it) }, label = { Text(label) })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("接手人")
            FlowRow { names.filterKeys { it !in setOf("unknown", "system") }.forEach { (id, name) ->
                FilterChip(patch.optString("assignee") == id, onClick = { change("assignee", id) }, label = { Text(name) })
            } }
            FlowRow { (0..3).forEach { n -> FilterChip(patch.optString("priority") == "P$n", onClick = { change("priority", "P$n") }, label = { Text("P$n") }) } }
            FlowRow { TaskRecords.statuses.forEach { (id, label) -> FilterChip(patch.optString("status") == id, onClick = { change("status", id) }, label = { Text(label) }) } }
            Row { Checkbox(patch.optString("horizon") == "long", onCheckedChange = { change("horizon", if (it) "long" else "short") }); Text("长期的事", Modifier.padding(top = 12.dp)) }
            Text("关联已有事项")
            rows.filter { it.getString("id") != original.optString("id") }.forEach { row ->
                val links = patch.optJSONArray("linked_ids") ?: JSONArray()
                val ids = (0 until links.length()).map { links.getString(it) }.toSet()
                Row {
                    Checkbox(row.getString("id") in ids, onCheckedChange = { enabled ->
                        change("linked_ids", JSONArray((if (enabled) ids + row.getString("id") else ids - row.getString("id")).toList()))
                    })
                    Text(row.optString("title"), Modifier.padding(top = 12.dp))
                }
            }
        } }, confirmButton = { TextButton(onClick = { onSave(patch) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
