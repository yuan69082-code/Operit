package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CompanionActivityPanel(store: CompanionStore, role: String) {
    val scope = rememberCoroutineScope()
    var chatId by remember(role) { mutableStateOf<String?>(null) }
    var logs by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var schedule by remember(role) { mutableStateOf(JSONObject()) }
    var technical by remember(role) { mutableStateOf(false) }
    LaunchedEffect(role) {
        while (true) {
            chatId = AssistantWindowRouter.chat(store.context, role)
            schedule = withContext(Dispatchers.IO) { store.read(store.scheduleKey(role)) }
            delay(3000)
        }
    }
    LaunchedEffect(role, technical) {
        if (technical) logs = withContext(Dispatchers.IO) { chatId?.let { store.logs(it) } ?: emptyList() }
    }
    Text("自主学习已移到“自主探索”：按助手独立开启，默认关闭，和这里的主动联系计划分别安排。")
    val at = schedule.optLong("at")
    Text(if (schedule.optBoolean("paused")) "下一次唤醒已暂停" else if (at > 0)
        "下次唤醒：" + DateFormat.getDateTimeInstance().format(Date(at)) else "下次时间尚未安排")
    Text(schedule.optString("purpose"))
    FilledTonalButton(enabled = store.proactiveEnabled && store.proactiveRole == role, onClick = {
        scope.launch(Dispatchers.IO) {
            store.update(store.scheduleKey(role)) { CompanionSchedule.supersede(it); it.put("at", System.currentTimeMillis() + 60_000).put("paused", false)
                .put("purpose", "用户要求唤醒一次，请检查当前情况，自行安排下一次。").put("owner", "user") }
            chatId?.let { store.log(it, "安排", "用户安排一分钟后唤醒") }
        }
    }) { Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp)); Text("一分钟后唤醒一次") }
    ActivityNotesPanel(role)
    TextButton(onClick = { technical = !technical }) { Text(if (technical) "收起技术记录" else "技术记录与旧活动日志") }
    if (technical) Text("旧日志没有可见随记时，保留当时记录，不补写心理过程。最近300条技术记录如下。", style = MaterialTheme.typography.bodySmall)
    if (technical) logs.forEach { row ->
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
