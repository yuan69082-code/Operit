package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** No polling or model calls. Full original text is loaded only when Details is opened. */
@Composable
internal fun SubjectConcernsPanel(role: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember(role) { mutableStateOf<JSONObject?>(null) }
    var offset by remember(role) { mutableIntStateOf(0) }
    var longCount by remember(role) { mutableStateOf("2") }
    var shortCount by remember(role) { mutableStateOf("2") }
    var oldCount by remember(role) { mutableStateOf("1") }
    var mode by remember(role) { mutableStateOf("mixed") }
    var busy by remember(role) { mutableStateOf(false) }
    var error by remember(role) { mutableStateOf("") }
    var notice by remember(role) { mutableStateOf("") }
    var drafts by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var brief by remember(role) { mutableStateOf("") }
    var original by remember(role) { mutableStateOf("") }
    var importance by remember(role) { mutableStateOf("重要") }
    var focus by remember(role) { mutableStateOf("目前仍在意") }
    var tags by remember(role) { mutableStateOf("") }
    var horizon by remember(role) { mutableStateOf("long_term") }
    var locked by remember(role) { mutableStateOf(true) }
    suspend fun load() {
        val page = withContext(Dispatchers.IO) { InternalStateStore(context).concernStatus(role, offset) }
        snapshot = page
        val config = page.getJSONObject("configuration")
        longCount = config.getInt("long_term").toString(); shortCount = config.getInt("short_term").toString()
        oldCount = config.getInt("old").toString(); mode = config.getString("mode")
    }
    fun run(block: suspend () -> Unit) { scope.launch {
        busy = true; error = ""
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "便签操作失败" }
        finally { busy = false }
    } }
    LaunchedEffect(role, offset) {
        try { load() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "便签读取失败" }
    }
    Text("挂心的事", style = MaterialTheme.typography.titleLarge)
    Text("常驻简况接续到聊天与自主活动；详细原文按需查看。进度会变化，锁定的本意和位置不会随排序被换掉。")
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (notice.isNotBlank()) Text(notice)
    OutlinedTextField(longCount, { longCount = it }, label = { Text("每轮长期便签数量") }, enabled = !busy)
    OutlinedTextField(shortCount, { shortCount = it }, label = { Text("每轮短期便签数量") }, enabled = !busy)
    OutlinedTextField(oldCount, { oldCount = it }, label = { Text("每轮旧事抽选数量") }, enabled = !busy)
    Text("数量是展示位置，不是保存上限。常驻占对应名额；仍共用主体材料总预算，很多位置不代表全文都能装入。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("mixed" to "混合", "relevant" to "关联", "rotation" to "轮换", "random" to "随机").forEach { (value, label) ->
            FilterChip(mode == value, { mode = value }, label = { Text(label) }, enabled = !busy)
        }
    }
    val initialized = snapshot?.getJSONObject("configuration")?.getBoolean("initialized") == true
    if (!initialized && snapshot != null) {
        Text("首次共同设置", style = MaterialTheme.typography.titleMedium)
        Text("可以先写好几张，再一起保存；也可以留空，让他之后自己形成便签。来源会如实标记为初始设置。")
        OutlinedTextField(brief, { brief = it }, label = { Text("日常简况") }, enabled = !busy)
        OutlinedTextField(original, { original = it }, label = { Text("详细本意 · 原文保存") }, minLines = 3, enabled = !busy)
        OutlinedTextField(tags, { tags = it }, label = { Text("性质，可填野心、目标、决定等，用逗号分开") }, enabled = !busy)
        OutlinedTextField(importance, { importance = it }, label = { Text("长期重要程度") }, enabled = !busy)
        OutlinedTextField(focus, { focus = it }, label = { Text("当前注意") }, enabled = !busy)
        Row {
            FilterChip(horizon == "long_term", { horizon = "long_term" }, label = { Text("长期") }, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            FilterChip(horizon == "short_term", { horizon = "short_term" }, label = { Text("短期") }, enabled = !busy)
        }
        Row { Checkbox(locked, { locked = it }, enabled = !busy); Text("常驻位置＋本意保护", Modifier.padding(top = 12.dp)) }
        OutlinedButton(enabled = !busy && brief.isNotBlank() && original.isNotBlank(), onClick = {
            drafts = drafts + JSONObject().put("text", original).put("attention", JSONObject().put("brief", brief)
                .put("horizon", horizon).put("importance", importance).put("focus", focus).put("pinned", locked).put("protected", locked)
                .put("tags", JSONArray(tags.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }.distinct())))
            brief = ""; original = ""; tags = ""
        }) { Text("加入待保存清单") }
        drafts.forEachIndexed { index, row ->
            Text(row.getJSONObject("attention").getString("brief"))
            Row {
                TextButton(enabled = !busy && brief.isBlank() && original.isBlank(), onClick = {
                    val a = row.getJSONObject("attention")
                    brief = a.getString("brief"); original = row.getString("text"); importance = a.getString("importance")
                    focus = a.getString("focus"); horizon = a.getString("horizon"); locked = a.getBoolean("pinned")
                    val values = a.getJSONArray("tags")
                    tags = (0 until values.length()).joinToString("，") { values.getString(it) }
                    drafts = drafts.filterIndexed { i, _ -> i != index }
                }) { Text("编辑") }
                TextButton(enabled = !busy, onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } }) { Text("移出清单") }
            }
        }
    }
    Button(enabled = !busy && snapshot != null, onClick = { run {
        require(brief.isBlank() && original.isBlank()) { "还有未加入清单的便签，请先加入或清空输入框。" }
        fun number(value: String): Int = requireNotNull(value.toIntOrNull()) { "数量请填非负整数" }.also { require(it >= 0) { "数量不能为负数" } }
        val settings = JSONObject().put("expected_version", snapshot!!.getJSONObject("configuration").getInt("version"))
            .put("long_term", number(longCount)).put("short_term", number(shortCount)).put("old", number(oldCount)).put("mode", mode)
        withContext(Dispatchers.IO) { InternalStateStore(context).configureConcerns(role, settings, if (initialized) null else JSONArray(drafts)) }
        drafts = emptyList(); load(); notice = "已保存。后续改变常驻或本意需明确变更，完整历史保留。"
    } }) { Text(if (initialized) "保存数量与抽选方式" else "完成首次设置") }
    TextButton(enabled = !busy, onClick = { run { load() } }) { Text("刷新便签") }
    snapshot?.optJSONArray("items")?.let { items ->
        if (items.length() == 0) Text("尚无便签。保存记录不等于必须行动，也不会自动安排唤醒。")
        for (i in 0 until items.length()) key(items.getJSONObject(i).getString("id")) {
            ConcernCard(role, items.getJSONObject(i))
        }
    }
    Row {
        TextButton(enabled = !busy && offset > 0, onClick = { offset = (offset - 8).coerceAtLeast(0) }) { Text("上一页") }
        TextButton(enabled = !busy && snapshot?.isNull("next_offset") == false,
            onClick = { offset = snapshot!!.getInt("next_offset") }) { Text("下一页") }
    }
}

@Composable
private fun ConcernCard(role: String, entry: JSONObject) {
    val context = LocalContext.current
    var expanded by remember(role, entry.getString("id")) { mutableStateOf(false) }
    var row by remember(role, entry.getString("id"), entry.getInt("version")) { mutableStateOf<JSONObject?>(null) }
    var error by remember(role, entry.getString("id")) { mutableStateOf("") }
    LaunchedEffect(role, entry.getString("id"), entry.getInt("version"), expanded) {
        if (!expanded) { row = null; return@LaunchedEffect }
        try { row = withContext(Dispatchers.IO) {
            val data = InternalStateStore(context).read(role)
            CategoryEvents.row(data, entry.getString("kind"), entry.getString("target"))
        }; error = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "详情读取失败" }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((if (entry.getString("parent").endsWith(":pinned")) "🔒 " else "") + entry.getString("brief"), style = MaterialTheme.typography.titleMedium)
            Text(when {
                entry.getString("parent") == "inactive" -> "已退居背景 · ${entry.getString("status") }"
                entry.getString("parent").startsWith("long_term") -> "长期"
                else -> "短期"
            })
            if (entry.optString("progress").isNotBlank()) Text("进度：${entry.getString("progress")}")
            if (entry.optString("importance").isNotBlank()) Text("重要程度：${entry.getString("importance")}")
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详细" else "详细") }
            if (expanded) {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                val current = row
                if (current == null && error.isBlank()) Text("读取详细原文…")
                if (current != null) {
                    val content = current.getJSONObject("content")
                    SelectionContainer { Column {
                        Text("本意原文", style = MaterialTheme.typography.titleSmall); Text(content.getString("text"))
                        val a = content.getJSONObject("attention")
                        for ((field, label) in listOf("importance" to "重要程度", "focus" to "当前注意", "progress" to "当前进度", "waiting" to "等待条件"))
                            if (a.optString(field).isNotBlank()) Text("$label：${a.getString(field)}")
                        if (a.has("tags")) Text("性质：${a.getJSONArray("tags")}")
                        if (content.has("attention_change")) Text("本次明确变更：${content.getString("attention_change")}")
                        Text("版本 ${current.getInt("version")} · 原始来源：" + if (content.optString("original_source", current.optString("source")) == "user_initial_setting") "初始共同设置" else "主体报告")
                    } }
                    PrivateFold("来源与关联") { SelectionContainer { Text(content.optJSONObject("bindings")?.toString(2).orEmpty() + "\n" + content.optJSONArray("related")?.toString().orEmpty()) } }
                    PrivateFold("变化历史") { PrivateRows(role, "category_versions", id = entry.getString("id")) }
                }
            }
        }
    }
}
