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
    var scheduleError by remember(role) { mutableStateOf("") }
    var scheduling by remember(role) { mutableStateOf(false) }
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
    val scheduleState = CompanionSchedule.state(schedule)
    val canResume = CompanionSchedule.canResume(schedule)
    Text(when (scheduleState) {
        "running" -> "正在进行本次唤醒判断"
        "scheduled" -> "下次唤醒：" + DateFormat.getDateTimeInstance().format(Date(at))
        "paused" -> "后续主动联系已暂停"
        "interrupted" -> if (canResume) "上次唤醒中断；原预约保留，等待恢复" else "上次唤醒未完成，暂无下一次安排"
        else -> "本轮没有安排下一次唤醒"
    })
    Text(schedule.optString("purpose"))
    FilledTonalButton(enabled = !scheduling && scheduleState != "running" && store.proactiveEnabled && store.proactiveRole == role, onClick = {
        scheduling = true
        scope.launch {
            try {
                scheduleError = ""
                schedule = withContext(Dispatchers.IO) {
                    store.update(store.scheduleKey(role)) {
                        if (canResume) CompanionSchedule.resume(it)
                        else {
                            CompanionSchedule.supersede(it)
                            it.put("at", System.currentTimeMillis() + 60_000).put("paused", false)
                                .put("purpose", "用户要求唤醒一次，请检查当前情况，自行安排下一次。").put("owner", "user")
                        }
                    }.also {
                        try { chatId?.let { chat -> store.log(chat, "安排", if (canResume) "用户恢复中断前的原预约，时间和目的未改写" else "用户安排一分钟后唤醒") } }
                        catch (failure: Exception) { com.ai.assistance.operit.util.AppLogger.e("CompanionActivityPanel", "预约已保存，安排日志未写入", failure) }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { scheduleError = failure.message.orEmpty() }
            finally { scheduling = false }
        }
    }) { Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp)); Text(if (canResume) "恢复中断的原预约" else "一分钟后唤醒一次") }
    if (scheduleError.isNotBlank()) Text(scheduleError, color = MaterialTheme.colorScheme.error)
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
