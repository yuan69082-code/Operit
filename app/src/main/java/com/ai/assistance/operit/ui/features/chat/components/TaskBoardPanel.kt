package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.tasks.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskBoardPanel(board: TaskBoard, members: Map<String, String>, privateOwner: String? = null,
                   onFinish: ((String, String, String) -> Unit)? = null) {
    val revision by TaskBoard.revision.collectAsState()
    val groupRevision by com.ai.assistance.operit.core.group.GroupStore.revision.collectAsState()
    var refresh by remember(board.scope) { mutableIntStateOf(0) }
    var rows by remember(board.scope) { mutableStateOf(emptyList<JSONObject>()) }
    var archived by remember(board.scope) { mutableStateOf(false) }
    var offset by remember(board.scope) { mutableIntStateOf(0) }
    var nextOffset by remember(board.scope) { mutableStateOf<Int?>(null) }
    var total by remember(board.scope) { mutableLongStateOf(0) }
    var loading by remember(board.scope) { mutableStateOf(true) }
    var tabCounts by remember(board.scope) { mutableStateOf(emptyMap<String, Long>()) }
    var query by remember(board.scope) { mutableStateOf("") }
    var filter by remember(board.scope) { mutableStateOf("todo") }
    var stage by remember(board.scope) { mutableStateOf("") }
    var owner by remember(board.scope) { mutableStateOf("*") }
    var category by remember(board.scope) { mutableStateOf("") }
    var selected by remember(board.scope) { mutableStateOf(emptySet<String>()) }
    var selecting by remember(board.scope) { mutableStateOf(false) }
    var deleting by remember(board.scope) { mutableStateOf<String?>(null) }
    var editing by remember(board.scope) { mutableStateOf<JSONObject?>(null) }
    var error by remember(board.scope) { mutableStateOf<String?>(null) }
    var expanded by remember(board.scope) { mutableStateOf<String?>(null) }
    var note by remember(expanded) { mutableStateOf("") }
    var historyLimit by remember(expanded) { mutableIntStateOf(12) }
    var historyOffset by remember(expanded) { mutableIntStateOf(0) }
    var historyPage by remember(expanded) { mutableStateOf<JSONObject?>(null) }
    var notify by remember(board.scope) { mutableStateOf(true) }
    val names = members + mapOf("user" to "你", "" to "待认领", "unknown" to "旧记录未署名", "system" to "软件")
    fun name(id: String) = names[id] ?: id
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(board.scope, revision, groupRevision, refresh, offset, filter, stage, owner, category, query) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val migrated = board.usesArchive
                val page = if (migrated) board.page(offset, filter, stage, owner, category, query, displayOrder = true) else null
                val records = page?.getJSONArray("items")?.let { (0 until it.length()).map { i -> it.getJSONObject(i) } } ?: board.list()
                val counts = if (migrated) listOf("todo", "doing", "blocked", "done", "all", "archived").associateWith { board.page(filter = it, limit = 1).getLong("total") } else emptyMap()
                Triple(migrated, page, records to counts)
            }
            archived = result.first; rows = result.third.first; tabCounts = result.third.second
            total = result.second?.getLong("total") ?: rows.size.toLong()
            nextOffset = result.second?.let { if (it.isNull("next_offset")) null else it.getInt("next_offset") }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { loading = false }
    }
    LaunchedEffect(query, filter, stage, owner, category) { offset = 0; expanded = null }
    LaunchedEffect(offset, query, filter, stage, owner, category) { selected = emptySet() }
    LaunchedEffect(expanded, historyOffset, revision, refresh, archived) {
        val id = expanded
        if (archived && id != null) try {
            historyPage = withContext(Dispatchers.IO) { board.get(id, historyOffset) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    fun work(action: () -> Unit) {
        coroutineScope.launch {
            try { withContext(Dispatchers.IO) { action() }; error = null; refresh++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
        }
    }
    fun recipient(row: JSONObject) = row.optString("assignee").ifBlank { privateOwner.orEmpty() }
    fun submit(row: JSONObject) {
        board.submit(row.getString("id"), "user", recipient(row), members.keys, 0)
    }
    val tabs = listOf("todo" to "待处理", "doing" to "在做", "blocked" to "卡住", "done" to "已完成", "all" to "全部", "archived" to "已归档")
    val filtered = rows.filter { row ->
        val tab = TaskRecords.tab(row)
        (if (filter == "all") tab != "archived" else tab == filter) &&
        (stage.isBlank() || row.optString("status") == stage) &&
        (owner == "*" || row.optString("assignee") == owner) &&
        (category.isBlank() || row.optString("category") == category) &&
        (query.isBlank() || listOf("title", "detail", "id", "conclusion", "next", "evidence", "origin", "goal", "blocker", "plan", "materials").any { row.optString(it).contains(query, true) })
    }.sortedWith(compareBy<JSONObject> { it.optString("priority") }.thenByDescending { it.optLong("updated") })
    Text(if (board.group) "群里的共同打算" else "自己的打算与待办", style = MaterialTheme.typography.titleMedium)
    Text("要做什么、急不急、做到哪、卡在哪、下一步，都在卡片上。点卡片看原话、材料和批注。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { (key, label) ->
            val count = if (archived) tabCounts[key] ?: 0L else rows.count { if (key == "all") TaskRecords.tab(it) != "archived" else TaskRecords.tab(it) == key }.toLong()
            FilterChip(filter == key, onClick = { filter = key }, label = { Text("$label $count") })
        }
    }
    if (board.group) FlowRow {
        (mapOf("*" to "所有接手人", "" to "待认领") + members).forEach { (id, label) ->
            FilterChip(owner == id, onClick = { owner = id }, label = { Text(label) })
        }
    }
    OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("搜索标题、原话、材料或 ID") }, modifier = Modifier.fillMaxWidth())
    var showStatuses by remember { mutableStateOf(false) }
    FlowRow {
        Box {
            OutlinedButton(onClick = { showStatuses = true }) { Icon(Icons.Default.FilterList, null); Spacer(Modifier.width(6.dp)); Text(TaskRecords.statuses[stage] ?: "全部状态") }
            DropdownMenu(showStatuses, onDismissRequest = { showStatuses = false }) {
                (mapOf("" to "全部状态") + TaskRecords.statuses).forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(if (archived) label else "$label (${rows.count { key.isBlank() || it.optString("status") == key }})") },
                        onClick = { stage = key; showStatuses = false })
                }
            }
        }
        FilterChip(category.isBlank(), onClick = { category = "" }, label = { Text("全部分类") })
        rows.map { it.optString("category") }.filter { it.isNotBlank() }.distinct().forEach { value ->
            FilterChip(category == value, onClick = { category = value }, label = { Text(value) })
        }
    }
    if (archived) OutlinedTextField(category, { category = it }, label = { Text("按分类查全部记录，可留空") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { editing = JSONObject().put("assignee", privateOwner.orEmpty()).put("priority", "P2").put("status", "open").put("horizon", "short") }) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("新增打算")
        }
        Spacer(Modifier.weight(1f))
        IconToggleButton(checked = selecting, onCheckedChange = { selecting = it; if (!it) selected = emptySet() }) {
            Icon(Icons.Default.Checklist, if (selecting) "退出批量选择" else "批量选择")
        }
        IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "刷新待办") }
    }
    if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { selected = filtered.map { it.getString("id") }.toSet() }) { Text(if (archived) "全选当前页" else "全选当前列表") }
        TextButton(onClick = { selected = emptySet() }) { Text("清空选择") }
        FilledTonalButton(enabled = selected.isNotEmpty(), onClick = { work {
            rows.filter { it.getString("id") in selected }.forEach {
                board.save("user", it.getString("id"), JSONObject().put("status", "archived").put("expected_version", it.getInt("version")), members.keys)
            }; selected = emptySet()
        } }) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(6.dp)); Text("归档 ${selected.size} 项") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (archived) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("共 $total 项 · 第 ${offset / 20 + 1} 页", Modifier.weight(1f))
        TextButton(enabled = offset > 0 && !loading, onClick = { offset = maxOf(0, offset - 20); expanded = null }) { Text("上一页") }
        TextButton(enabled = nextOffset != null && !loading, onClick = { offset = requireNotNull(nextOffset); expanded = null }) { Text("下一页") }
    }
    if (!loading && error == null && filtered.isEmpty()) Text("这一栏现在是空的。", style = MaterialTheme.typography.bodySmall)
    filtered.forEach { row ->
        val id = row.getString("id")
        val tab = TaskRecords.tab(row)
        val open = expanded == id
        OutlinedCard(onClick = { expanded = if (open) null else id }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selecting) Checkbox(id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                    PriorityBadge(row.optString("priority"))
                    StatusBadge(tab, TaskRecords.statuses[row.optString("status")].orEmpty())
                    Text(row.optString("title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = if (open) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                // Collapsed card still answers: where is it, what's next, what blocks it.
                row.optString("blocker").takeIf { it.isNotBlank() }?.let {
                    Text("卡在：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                }
                if (!open) {
                    row.optString("conclusion").takeIf { it.isNotBlank() }?.let { Text("进展：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    row.optString("next").takeIf { it.isNotBlank() }?.let { Text("下一步：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (row.optString("needs_decision").isNotBlank()) Text("等你决定：${row.optString("needs_decision")}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text("${if (row.optString("horizon") == "long") "长期" else "近期"} · 接手 ${name(row.optString("assignee"))} · 创建 ${name(row.optString("author"))} · ID ${id.take(8)}" +
                    materialLines(row).size.let { if (it > 0) " · 材料 $it" else "" }, style = MaterialTheme.typography.labelSmall)
                row.optJSONObject("dispatch")?.let { d -> Text("处理提交：${when(d.optString("state")) { "pending" -> "等待空闲"; "running" -> "处理中"; "handled" -> "已处理"; "cancelled" -> "已取消"; "failed" -> "未成功"; else -> "已中断" }} · ${name(d.optString("recipient"))}\n${d.optString("detail")}", style = MaterialTheme.typography.bodySmall) }
                if (open) {
                    HorizontalDivider()
                    listOf("detail" to "要做什么", "origin" to "原话原文", "goal" to "做完要什么效果", "conclusion" to "现在进展",
                        "completed" to "已经做了什么", "next" to "下一步", "plan" to "后面的规划", "batch" to "共同计划",
                        "evidence" to "其他原始记录").forEach { (key, label) ->
                        if (row.optString(key).isNotBlank()) Section(label) { SelectionContainer { Text(row.optString(key), style = MaterialTheme.typography.bodySmall) } }
                    }
                    val materials = materialLines(row)
                    if (materials.isNotEmpty()) Section("材料（${materials.size}）") {
                        materials.forEach { line -> MaterialRow(line) }
                    }
                    val links = row.optJSONArray("linked_ids") ?: JSONArray()
                    if (links.length() > 0) Text("关联事项：" + (0 until links.length()).joinToString { i ->
                        rows.firstOrNull { it.getString("id") == links.getString(i) }?.optString("title") ?: links.getString(i)
                    }, style = MaterialTheme.typography.bodySmall)
                    Text("重要程度", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..3).forEach { n ->
                            FilterChip(row.optString("priority") == "P$n", onClick = { work {
                                board.save("user", id, JSONObject().put("priority", "P$n").put("expected_version", row.getInt("version")), members.keys)
                            } }, label = { Text(priorityLabel("P$n")) })
                        }
                    }
                    FlowRow {
                        OutlinedButton(onClick = { editing = JSONObject(row.toString()) }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("编辑／交接") }
                        FilledTonalButton(enabled = row.optString("status") !in TaskRecords.inactiveStatuses && recipient(row) in members,
                            onClick = { work { submit(row) } }) { Text("安排接手人继续") }
                        if (onFinish != null) IconButton(onClick = { deleting = id }) { Icon(Icons.Default.DeleteOutline, "删除待办") }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("我的批注（AI 的批注也会分别署名）") }, modifier = Modifier.fillMaxWidth())
                    val canNotify = row.optString("status") !in TaskRecords.inactiveStatuses && recipient(row) in members
                    Row { Checkbox(notify && canNotify, enabled = canNotify, onCheckedChange = { notify = it }); Text("保存后通知接手助手", Modifier.padding(top = 12.dp)) }
                    Button(enabled = note.isNotBlank(), onClick = { work {
                        board.comment(id, "user", note); note = ""
                        if (notify && canNotify && row.optJSONObject("dispatch")?.optString("state") != "pending") submit(row)
                    } }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("保存批注") }
                    if (onFinish != null && row.optString("status") == "done") {
                        TextButton(onClick = { work { onFinish(id, "distill", row.optString("completed").ifBlank { row.optString("conclusion") }) } }) { Text("将实际收获存入书房") }
                    }
                    val history = (if (archived) historyPage?.optJSONArray("history") else row.optJSONArray("history")) ?: JSONArray()
                    Text(if (archived) "处理历史与批注（${historyPage?.optLong("history_total") ?: 0} 条）" else "处理历史与批注（最近80条）", style = MaterialTheme.typography.titleSmall)
                    if (archived) Row {
                        TextButton(enabled = historyOffset > 0, onClick = { historyOffset = maxOf(0, historyOffset - 5) }) { Text("较新记录") }
                        TextButton(enabled = historyPage?.isNull("next_history_offset") == false, onClick = { historyOffset = requireNotNull(historyPage).getInt("next_history_offset") }) { Text("更早记录") }
                    } else if (history.length() > historyLimit) TextButton(onClick = { historyLimit += 12 }) { Text("查看更早记录") }
                    for (i in (if (archived) (0 until history.length()).toList() else (history.length() - 1 downTo maxOf(0, history.length() - historyLimit)).toList())) {
                        val event = history.getJSONObject(i)
                        Text("${name(event.optString("actor"))} · ${event.optString("kind")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.optLong("at")))}", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(event.optString("text"), style = MaterialTheme.typography.bodySmall) }
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
        TaskEditor(original, rows, names, error, lookup = if (archived) { queryText, pageOffset -> board.page(pageOffset, query = queryText) } else null, onDismiss = { editing = null }, onSave = { patch -> work {
            board.save("user", original.optString("id").takeIf { it.isNotBlank() }, patch, members.keys)
            editing = null
        } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditor(original: JSONObject, rows: List<JSONObject>, names: Map<String, String>, error: String?,
                       lookup: ((String, Int) -> JSONObject)? = null,
                       onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var patch by remember(original) { mutableStateOf(JSONObject().apply {
        TaskRecords.fields.filter { original.has(it) }.forEach { put(it, original.get(it)) }
        if (original.has("version")) put("expected_version", original.getInt("version"))
    }) }
    fun change(key: String, value: Any) { patch = JSONObject(patch.toString()).put(key, value) }
    var linkQuery by remember(original) { mutableStateOf("") }
    var linkOffset by remember(original) { mutableIntStateOf(0) }
    var linkPage by remember(original) { mutableStateOf<JSONObject?>(null) }
    var linkError by remember(original) { mutableStateOf<String?>(null) }
    LaunchedEffect(linkQuery) { linkOffset = 0 }
    LaunchedEffect(original, linkQuery, linkOffset) {
        if (lookup != null) try { linkPage = withContext(Dispatchers.IO) { lookup(linkQuery, linkOffset) }; linkError = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { linkError = failure.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("待办与心愿") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("title" to "标题", "detail" to "要做什么", "origin" to "原话原文（谁说的、什么时间）",
                "goal" to "做完要什么效果", "conclusion" to "现在进展", "completed" to "已经做了什么",
                "blocker" to "卡在哪（没卡留空）", "next" to "下一步", "plan" to "后面的规划",
                "materials" to "材料：每行一条，路径或编号＋说明", "category" to "分类，自定义", "batch" to "共同计划／批次",
                "needs_decision" to "确实需要用户决定的内容，可留空", "evidence" to "其他原始记录或链接").forEach { (key, label) ->
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
            if (lookup != null) {
                OutlinedTextField(linkQuery, { linkQuery = it }, label = { Text("查找全部事项，不限当前页") })
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val currentLinks = patch.optJSONArray("linked_ids") ?: JSONArray()
                for (i in 0 until currentLinks.length()) {
                    val linkedId = currentLinks.getString(i)
                    TextButton(onClick = { change("linked_ids", JSONArray((0 until currentLinks.length()).map { currentLinks.getString(it) }.filter { it != linkedId })) }) { Text("取消关联：$linkedId") }
                }
                Row {
                    TextButton(enabled = linkOffset > 0, onClick = { linkOffset = maxOf(0, linkOffset - 20) }) { Text("上一页") }
                    TextButton(enabled = linkPage?.isNull("next_offset") == false, onClick = { linkOffset = requireNotNull(linkPage).getInt("next_offset") }) { Text("下一页") }
                }
            }
            val availableLinks = if (lookup == null) rows else linkPage?.getJSONArray("items")?.let { values -> (0 until values.length()).map { values.getJSONObject(it) } } ?: emptyList()
            availableLinks.filter { it.getString("id") != original.optString("id") }.forEach { row ->
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


private fun priorityLabel(p: String) = when (p) { "P0" -> "P0 最急"; "P1" -> "P1 急"; "P2" -> "P2 一般"; else -> "P3 不急" }

/** One material per line: a local path, ob/library id or link, optionally followed by a description. */
internal fun materialLines(row: JSONObject): List<String> =
    row.optString("materials").lines().map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun TagBadge(text: String, container: Color, content: Color) {
    Text(text, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(container, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun PriorityBadge(p: String) {
    val scheme = MaterialTheme.colorScheme
    when (p) {
        "P0" -> TagBadge("P0", scheme.error, scheme.onError)
        "P1" -> TagBadge("P1", scheme.errorContainer, scheme.onErrorContainer)
        "P2" -> TagBadge("P2", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> TagBadge(p.ifBlank { "P3" }, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBadge(tab: String, label: String) {
    val scheme = MaterialTheme.colorScheme
    when (tab) {
        "blocked" -> TagBadge("卡住·$label", scheme.errorContainer, scheme.onErrorContainer)
        "doing" -> TagBadge(label, scheme.primaryContainer, scheme.onPrimaryContainer)
        "done", "archived" -> TagBadge(label, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> TagBadge(label, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(label: String, body: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        body()
    }
}

private val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif")

@Composable
private fun MaterialRow(line: String) {
    val target = line.substringBefore(' ').substringBefore('	')
    val isImage = (target.startsWith("/") || target.startsWith("content://") || target.startsWith("http")) &&
        target.substringAfterLast('.', "").lowercase() in imageExt
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isImage) AsyncImage(model = if (target.startsWith("/")) java.io.File(target) else target,
            contentDescription = line, modifier = Modifier.size(56.dp))
        SelectionContainer(Modifier.weight(1f)) { Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}
