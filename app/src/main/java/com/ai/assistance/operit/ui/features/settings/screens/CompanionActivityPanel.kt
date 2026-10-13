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
    val context = androidx.compose.ui.platform.LocalContext.current
    val actor = identity.removePrefix("role:")
    val board = remember(identity) { com.ai.assistance.operit.core.tasks.TaskBoard(context, identity, false) }
    val card by com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context)
        .getCharacterCardFlow(actor).collectAsState(initial = null)
    com.ai.assistance.operit.ui.features.chat.components.TaskBoardPanel(board, mapOf(actor to (card?.name ?: actor)), actor,
        onFinish = { id, action, learning -> store.finishTodo(identity, id, action, learning) })
}
