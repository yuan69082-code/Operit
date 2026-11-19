package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.AutonomousRuntimeControl
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun AutonomousRuntimePanel(role: String) {
    val context = LocalContext.current
    val control = remember { AutonomousRuntimeControl(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(role) { mutableStateOf(JSONObject()) }
    var error by remember(role) { mutableStateOf("") }
    var idle by remember(role) { mutableStateOf(false) }
    var minutes by remember(role) { mutableStateOf("30") }
    var daily by remember(role) { mutableStateOf("12") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(role) {
        withContext(Dispatchers.IO) { control.idleConfig(role) }.let {
            idle = it.optBoolean("enabled"); minutes = it.optInt("interval_minutes", 30).toString()
            daily = it.optInt("daily_limit", 12).toString()
        }
        while (isActive) {
            try { snapshot = withContext(Dispatchers.IO) { control.status(role) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "运行状态读取失败：${failure.javaClass.simpleName}" }
            delay(2000)
        }
    }
    fun change(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                snapshot = withContext(Dispatchers.IO) { control.status(role) }; error = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { busy = false }
        }
    }
    SpaceSectionTitle("自主运行", "总开关控制本机所有助手的后台自主活动；普通聊天仍可使用。空闲接续按当前助手设置。")
    RoomSwitch("允许后台自主运行", snapshot.optBoolean("enabled", true)) { value -> change { control.enable(value) } }
    Text(snapshot.optString("state", "读取中") + " " + snapshot.optString("reason"))
    snapshot.optJSONObject("last_process_exit")?.takeIf { it.optLong("at") > 0 }?.let { exit ->
        val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(exit.getLong("at")))
        Text("上次退出 · $time · ${exit.getString("label")}", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { change { control.pause() } }) { Text("暂停") }
        OutlinedButton(enabled = !busy, onClick = { change { control.pause(System.currentTimeMillis() + 3600000) } }) { Text("暂停1小时") }
        OutlinedButton(enabled = !busy, onClick = { change { control.resume() } }) { Text("恢复") }
    }
    Text("恢复只允许后续判断，不重放未知操作。断电、强制停止或系统杀进程期间无法运行；重新打开后核对中断记录。", style = MaterialTheme.typography.bodySmall)
    RoomSwitch("为此助手提供空闲判断机会", idle) { idle = it }
    OutlinedTextField(minutes, { minutes = it }, label = { Text("间隔分钟（1–1440）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(daily, { daily = it }, label = { Text("每日最多空闲轮次（1–96）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("需要此助手已开启内部状态；沿用已有活动与状态闭环。等待时没有模型思考，不要求成果或联系；额外调用消耗模型额度。助手可选择延后或暂停。", style = MaterialTheme.typography.bodySmall)
    if (snapshot.optJSONObject("idle")?.optBoolean("held") == true) Text("助手已选择暂停空闲接续；重新保存可恢复机会。")
    Button(enabled = !busy, onClick = {
        val m = minutes.toIntOrNull(); val d = daily.toIntOrNull()
        if (m == null || m !in 1..1440 || d == null || d !in 1..96) error = "请填写范围内整数"
        else change { control.configureIdle(role, idle, m, d) }
    }) { Text("保存空闲设置") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    AutonomousJournalPanel(role)
}
