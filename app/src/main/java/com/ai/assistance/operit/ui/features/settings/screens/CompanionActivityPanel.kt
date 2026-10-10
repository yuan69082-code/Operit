package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CompanionActivityPanel(store: CompanionStore, chatId: String) {
    var logs by remember(chatId) { mutableStateOf(emptyList<JSONObject>()) }
    var schedule by remember(chatId) { mutableStateOf(JSONObject()) }
    var choices by remember { mutableStateOf(store.preferences.getStringSet("learning_choices", emptySet()).orEmpty().toSet()) }
    LaunchedEffect(chatId) {
        while (true) {
            withContext(Dispatchers.IO) { store.logs(chatId) to store.read("schedule:$chatId") }
                .let { logs = it.first; schedule = it.second }
            delay(3000)
        }
    }
    Text("主动学习", style = MaterialTheme.typography.titleMedium)
    Text("可多选。AI 按兴趣和待办自行安排，使用当前角色已允许的工具；不开启的活动不会作为主动学习目标。学习结果可保存到资料库。")
    listOf("mcp" to "已有 MCP（包括 hy）", "web" to "上网探索与学习", "reading" to "读书和复习资料库", "organize" to "整理软件资料与待办").forEach { (key, label) ->
        Row {
            Checkbox(checked = key in choices, onCheckedChange = { enabled ->
                choices = if (enabled) choices + key else choices - key
                store.preferences.edit().putStringSet("learning_choices", choices).apply()
            })
            Text(label, modifier = Modifier.padding(top = 12.dp))
        }
    }
    val at = schedule.optLong("at")
    Text(if (schedule.optBoolean("paused")) "下一次唤醒已暂停" else if (at > 0)
        "下次唤醒：" + DateFormat.getDateTimeInstance().format(Date(at)) else "下次时间尚未安排")
    Text(schedule.optString("purpose"))
    TextButton(enabled = store.proactiveEnabled && store.preferences.getString("target_chat", null) == chatId, onClick = {
        store.update("schedule:$chatId") { it.put("at", System.currentTimeMillis() + 60_000).put("paused", false)
            .put("purpose", "用户要求唤醒一次，请检查当前情况，自行安排下一次。").put("owner", "user") }
        store.log(chatId, "安排", "用户安排一分钟后唤醒")
    }) { Text("一分钟后唤醒一次") }
    Text("活动日志（最近300条）", style = MaterialTheme.typography.titleMedium)
    if (logs.isEmpty()) Text("尚无记录；这里会记录开始、跳过、安静、联系、学习、下次安排以及 XC 请求结果。")
    logs.forEach { row ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(DateFormat.getDateTimeInstance().format(Date(row.getLong("at"))) + " · " + row.getString("kind"))
                Text(row.getString("detail"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun CompanionTodoPanel(store: CompanionStore, identity: String) {
    var rows by remember(identity) { mutableStateOf(store.todos(identity)) }
    var id by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var longTerm by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    fun perform(action: () -> Unit) {
        try { action(); rows = store.todos(identity); error = "" } catch (e: Exception) { error = e.message.orEmpty() }
    }
    Text("AI 可创建、修改和完成待办，完成后可沉淀收获或删除。旧保留片段在资料库的“旧保留记忆”分类中。")
    TextButton(onClick = { rows = store.todos(identity) }) { Text("刷新待办") }
    rows.sortedWith(compareBy<JSONObject> { it.optString("status") == "done" }.thenByDescending { it.optString("urgency") == "urgent" }).forEach { row ->
        OutlinedCard(onClick = {
            id = row.getString("id"); title = row.getString("title"); detail = row.getString("detail")
            urgent = row.getString("urgency") == "urgent"; longTerm = row.getString("horizon") == "long"; done = row.getString("status") == "done"
        }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(row.getString("title"))
                Text((if (row.getString("status") == "done") "已完成" else "未完成") +
                    (if (row.getString("urgency") == "urgent") " · 紧急" else " · 普通") +
                    (if (row.getString("horizon") == "long") " · 长期" else " · 近期"))
            }
        }
    }
    TextButton(onClick = { id = null; title = ""; detail = ""; urgent = false; longTerm = false; done = false }) { Text("新建待办") }
    OutlinedTextField(title, { title = it }, label = { Text("标题") })
    OutlinedTextField(detail, { detail = it }, label = { Text("说明；沉淀时填写实际收获") }, minLines = 3)
    Row { Checkbox(urgent, { urgent = it }); Text("紧急"); Checkbox(longTerm, { longTerm = it }); Text("长期"); Checkbox(done, { done = it }); Text("完成") }
    Row {
        TextButton(onClick = { perform {
            id = store.saveTodo(identity, id, title, detail, if (urgent) "urgent" else "normal",
                if (longTerm) "long" else "short", if (done) "done" else "open").getString("id")
        } }) { Text("保存") }
        TextButton(enabled = id != null, onClick = { deleting = true }) { Text("删除") }
        TextButton(enabled = id != null && done, onClick = { perform {
            store.finishTodo(identity, checkNotNull(id), "distill", detail); id = null; title = ""; detail = ""
        } }) { Text("沉淀到资料库") }
    }
    if (error.isNotBlank()) Text(error)
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这项待办？") },
        confirmButton = { TextButton(onClick = { deleting = false; perform { store.finishTodo(identity, checkNotNull(id), "delete"); id = null; title = ""; detail = "" } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}
