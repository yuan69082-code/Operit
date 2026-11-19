package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun SubjectLifePanel(role: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(role) { mutableStateOf("读取中") }
    var rest by remember(role) { mutableStateOf<JSONObject?>(null) }
    var inbox by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var offset by remember(role) { mutableIntStateOf(0) }
    var error by remember(role) { mutableStateOf("") }
    var notice by remember(role) { mutableStateOf("") }
    var detail by remember(role) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(role, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) {
            try {
                val projection = withContext(Dispatchers.IO) {
                    val r = SubjectLife(context).rest(role)?.getJSONObject("content")?.let { full ->
                        JSONObject().also { view -> listOf("state", "mode", "until", "meaning", "interruption").forEach {
                            if (full.has(it)) view.put(it, full.get(it))
                        } }
                    }
                    val streaming = ChatRuntimeHolder.activeChatsIfCreated()
                    val chatBusy = streaming.any { ChatHistoryManager.getInstance(context).getChatMetadata(it)?.characterCardId == role }
                    r to SubjectRestPolicy.label(r, SubjectLife.busy(role) ?: if (chatBusy) "正在交流" else null, System.currentTimeMillis())
                }
                // JSONObject uses reference equality: replacing an unchanged record every
                // two seconds needlessly recomposed the entire expanded card.
                if (rest?.toString() != projection.first?.toString()) rest = projection.first
                status = projection.second; error = withContext(Dispatchers.IO) { SubjectLife.failure(context, role).orEmpty() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "状态读取失败：${failure.javaClass.simpleName}" }
            delay(2_000)
        } }
    }
    LaunchedEffect(role, offset, detail, lifecycle) {
        if (!detail) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) {
            try {
                val page = withContext(Dispatchers.IO) { SubjectMessageInbox(context).page(role, offset) }
                if (inbox.map { it.toString() } != page.map { it.toString() }) inbox = page
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "待发送消息读取失败：${failure.javaClass.simpleName}" }
            delay(2_000)
        } }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (notice.isNotBlank()) Text(notice)
            TextButton(onClick = { detail = !detail }) { Text(if (detail) "收起" else "查看安排与待发送消息") }
            if (detail) {
                rest?.let { row ->
                    Text(row.optString("meaning"))
                    if (row.optLong("until") > 0) Text("预计结束：" + java.time.Instant.ofEpochMilli(row.getLong("until")).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
                    Text("打扰安排：" + when (row.optString("interruption")) { "judge" -> "指定来源到达后由他判断"; "wake" -> "按已选来源续接"; else -> "先暂存，醒后取用" })
                    if (row.optString("state") == "resting") TextButton(onClick = { scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                SubjectLife(context).requestWake(role, requireNotNull(AssistantWindowRouter.chat(context, role)) { "请先为这位助手打开私聊" })
                            }
                            notice = "唤醒请求已保存，等待可用的执行位置。"
                        } catch (failure: Exception) { error = "请求未保存：${failure.message}" }
                    } }) { Text("请求醒来或续接") }
                }
                if (inbox.isEmpty()) Text("没有此页待处理的输入。")
                inbox.take(8).forEach { row ->
                    var expanded by remember(row.getString("id")) { mutableStateOf(false) }
                    Text(when (row.getString("status")) {
                        "queued" -> "消息已保存，等待处理"; "sending", "saved" -> "发送中"; "unknown" -> "上次处理结果尚未确认"; else -> "发送失败，原输入保留"
                    })
                    Text(if (expanded) row.getString("text") else row.getString("text").take(240))
                    if (row.getString("status") == "failed" && row.optString("dispatch_error").isNotBlank())
                        Text("未发送原因：${row.getString("dispatch_error")}。原文仍保留，请核对原会话。")
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "查看原文") }
                    if (row.getString("status") == "failed") TextButton(onClick = { scope.launch {
                        try { withContext(Dispatchers.IO) { SubjectMessageInbox(context).finish(role, row.getString("id"), "queued") } }
                        catch (failure: Exception) { error = "重试未保存：${failure.javaClass.simpleName}" }
                    } }) { Text("重新提交这条消息") }
                    if (row.getString("status") == "unknown") Text("请先在聊天核对已保存回复和操作；不会自动重发。")
                }
                Row {
                    TextButton(enabled = offset > 0, onClick = { offset = (offset - 8).coerceAtLeast(0) }) { Text("上一页") }
                    TextButton(enabled = inbox.size > 8, onClick = { offset += 8 }) { Text("下一页") }
                }
            }
        }
    }
}

@Composable
internal fun SummaryAddressPanel(role: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember(role) { mutableStateOf("") }
    var result by remember(role) { mutableStateOf("") }
    LaunchedEffect(role) { value = withContext(Dispatchers.IO) { AssistantRoom(context, role).read().optString("summary_user_address") } }
    OutlinedTextField(value, { value = it }, label = { Text("对话回顾里怎样称呼你") }, supportingText = { Text("由你自定义，保存一次后持续沿用。这位助手统一写“我”，你使用这里的称呼；原聊天不改写。") })
    TextButton(onClick = { scope.launch {
        try { withContext(Dispatchers.IO) { AssistantRoom(context, role).summaryAddress(value) }; result = "已保存，以后的压缩沿用。" }
        catch (failure: Exception) { result = "未保存：${failure.message}" }
    } }) { Text("保存称呼") }
    if (result.isNotBlank()) Text(result)
}
