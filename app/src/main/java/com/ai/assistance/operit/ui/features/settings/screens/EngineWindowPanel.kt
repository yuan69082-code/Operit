package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.engine.EngineWindow
import com.ai.assistance.operit.core.companion.CompanionRuntime
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun EngineWindowPanel(role: String?) {
    if (role == null) { Text("请先选择要关联主体的助手入口。"); return }
    val context = LocalContext.current
    val store = remember { EngineWindow(context) }
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf(store.connection()) }
    var binding by remember(role) { mutableStateOf(store.binding(role)) }
    var url by remember { mutableStateOf(connection.getString("url")) }
    var token by remember { mutableStateOf("") }
    var subject by remember(role) { mutableStateOf(binding?.getString("subjectId").orEmpty()) }
    var enabled by remember(role) { mutableStateOf(binding?.getBoolean("enabled") == true) }
    var grants by remember(role) { mutableStateOf(binding?.let(store::grants) ?: setOf("messages")) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmUnbind by remember { mutableStateOf(false) }
    var events by remember(role) { mutableStateOf(store.events(role).takeLast(100)) }
    fun perform(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                check(ChatRuntimeHolder.getInstance(context).activeConversationCount.value == 0 &&
                    !CompanionRuntime.hasActiveWork() &&
                    com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime.controller == null) {
                    "请等当前对话、工具和通话结束后再调整连接"
                }
                status = action()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message.orEmpty() }
            finally {
                connection = store.connection(); binding = store.binding(role)
                events = store.events(role).takeLast(100); busy = false
                AIForegroundService.refreshBackgroundKeepAlive(context)
            }
        }
    }
    Text("连续性引擎", style = MaterialTheme.typography.titleLarge)
    Text("这里关联已经存在的主体。身份、模型、记忆和自主活动由引擎管理，软件提供交流窗口和获得授权的外界事件。")
    Text("当前为接入准备版：可保存关联与平台事件，尚未确认正式主体协议，也不会启动或创建引擎主体。",
        color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(url, { url = it }, label = { Text("电脑共享服务地址") }, singleLine = true, enabled = !busy)
    OutlinedTextField(token, { token = it }, label = { Text(if (connection.optBoolean("tokenConfigured")) "共享服务 Token（留空保留）" else "共享服务 Token") },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
    Text("填写我们电脑端的共享服务地址和 Token。引擎服务令牌只在电脑端配置。")
    OutlinedButton(enabled = !busy, onClick = { perform {
        withContext(Dispatchers.IO) { store.saveConnection(url, token) }
        token = ""; "电脑连接设置已保存"
    } }) { Text("保存电脑连接") }
    OutlinedTextField(subject, { subject = it }, label = { Text("已有主体标识") }, singleLine = true,
        enabled = !busy && binding == null)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("开启这个软件的连接", Modifier.weight(1f))
        Switch(enabled, { enabled = it }, enabled = !busy)
    }
    Text("默认关闭。关联后，软件的人设生成、模型代答、自动记忆和自主判断停止参与这个入口；关闭连接仍保留关联。解除关联后恢复原软件设置。")
    listOf("messages" to "私聊原话", "group" to "群聊交流", "phone" to "手机感知",
        "software" to "软件感知", "call" to "通话事件", "helpers" to "软件辅助模块的事件与建议").forEach { (key, label) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f).padding(top = 12.dp))
            Checkbox(key in grants, { grants = if (it) grants + key else grants - key }, enabled = !busy)
        }
    }
    Text("手机和软件感知还需原感知开关与指定观察助手授权。辅助模块只递交信息，不替主体作决定。图片、音频和视频的正式投递协议暂未接通。")
    Button(enabled = !busy && subject.isNotBlank(), onClick = { perform {
        withContext(Dispatchers.IO) { store.bind(role, subject.trim(), enabled, grants) }
        "软件关联已保存，正式主体绑定待确认"
    } }) { Text("保存主体关联与授权") }
    OutlinedButton(enabled = !busy && binding?.getBoolean("enabled") == true, onClick = { perform {
        store.sync(role)
    } }) { Text("同步平台记录") }
    if (binding != null) TextButton(enabled = !busy, onClick = { confirmUnbind = true }) { Text("解除软件关联") }
    if (status.isNotBlank()) Text(status)
    Text("最近的平台事件", style = MaterialTheme.typography.titleMedium)
    Text("已同步表示电脑保存了记录，不表示引擎收到或作出回应。离线撤权会先保存在本机，电脑重新连通后优先同步。")
    events.asReversed().forEach { event ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(event.getString("kind") + " · " + event.getString("occurredAt"))
                Text(event.optString("message", if (event.optString("status") == "revoked") "授权已撤回" else "保存在本机，尚未同步"))
            }
        }
    }
    if (events.isEmpty()) Text("尚无事件。")
    if (confirmUnbind) AlertDialog(onDismissRequest = { confirmUnbind = false },
        title = { Text("解除这个入口的主体关联？") },
        text = { Text("之后恢复原软件助手设置；未发送记录撤销，不转交给其他主体。此操作不会停止或删除引擎。") },
        confirmButton = { TextButton(onClick = {
            confirmUnbind = false
            perform { withContext(Dispatchers.IO) { store.unbind(role) }; enabled = false; subject = ""; "已解除软件关联" }
        }) { Text("解除关联") } },
        dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } })
}
