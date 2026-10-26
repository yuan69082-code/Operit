package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.awareness.SoftwareAwareness
import com.ai.assistance.operit.core.awareness.SoftwareAwarenessStore
import com.ai.assistance.operit.core.companion.CompanionRuntime
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SoftwareAwarenessPanel() {
    val context = LocalContext.current
    val store = remember { SoftwareAwarenessStore(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.preferences.getBoolean("capabilities_enabled", false)) }
    var wake by remember { mutableStateOf(store.wakeEnabled) }
    var role by remember { mutableStateOf(store.role) }
    var observer by remember { mutableStateOf("尚未指定") }
    var interval by remember { mutableStateOf((store.intervalMs / 1000).toString()) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val time = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = ""; block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { busy = false }
        }
    }
    fun changed() {
        CompanionRuntime.noteUserInteraction()
        store.revokePending()
        SoftwareAwareness.configurationChanged(context)
        AIForegroundService.refreshBackgroundKeepAlive(context)
    }
    LaunchedEffect(role) {
        observer = role?.let { selected ->
            CharacterCardManager.getInstance(context).getAllCharacterCards().firstOrNull { it.id == selected }?.name
                ?: "指定助手已删除，请重新选择"
        } ?: "尚未指定"
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            try { rows = withContext(Dispatchers.IO) { store.rows().asReversed() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "日志读取失败：${failure.message}" }
            delay(2500)
        }
    }
    LaunchedEffect(query) { page = 0 }
    SpaceSectionTitle("观察与权限")
    Text("记录 Vio Life 内发生的变化：模型与助手增删、设置变更、页面访问、进入后台、聊天停留及实际发送。只有你指定的助手能在自己的私聊接收和查询。与手机感知、主助手管理权分别设置。")
    Text("观察助手：$observer")
    AssistantTargetPicker(role, !busy) { selected -> perform {
        check(CharacterCardManager.getInstance(context).getAllCharacterCards().any { it.id == selected }) { "助手不存在" }
        listOf("software_awareness", "companion_capabilities").forEach {
            CharacterCardManager.getInstance(context).setToolSelected(selected, "builtin", it, true)
        }
        store.preferences.edit().putString("software_role", selected)
            .putLong("software_revision", store.revision + 1).apply()
        role = selected; changed()
    } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("记录变化并在对话中提供", Modifier.weight(1f))
        Switch(enabled = role != null && !busy, checked = enabled, onCheckedChange = { value ->
            store.preferences.edit().putBoolean("capabilities_enabled", value).putLong("software_revision", store.revision + 1).apply()
            enabled = value; wake = store.wakeEnabled; changed()
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("有新事件时主动唤醒观察助手", Modifier.weight(1f))
        Switch(enabled = enabled && role != null && !busy, checked = wake, onCheckedChange = { value ->
            store.preferences.edit().putBoolean("software_wake_enabled", value).putLong("software_revision", store.revision + 1).apply()
            wake = value; changed()
        })
    }
    SpaceExplanation("事件怎样交给助手", "不开事件唤醒也会保留记录，并在你下次与观察助手聊天时提供简报。开启后等对话/通话空闲再判断，密集操作合并；助手可安静处理，不强制发消息。判断会使用该助手的模型额度。")
    OutlinedTextField(interval, { interval = it }, label = { Text("两次事件判断至少间隔秒数（15–600）") }, singleLine = true)
    Button(enabled = !busy, onClick = { perform {
        val seconds = interval.toIntOrNull()
        require(seconds != null && seconds in 15..600) { "请填写15–600的整数秒数" }
        store.preferences.edit().putInt("software_wake_seconds", seconds).apply()
    } }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("保存间隔") }
    Text("停留时间表示已观察到的前台聊天页面可见时长，不等于注意力。软件退到后台与进程关闭分开看；被系统直接终止时，不能知道精确关闭时间。设置记录不保存密钥、令牌、提示词或聊天正文。")
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    HorizontalDivider()
    Text("变化与访问日志", style = MaterialTheme.typography.titleMedium)
    Text("本机保留最近14天、最多3000条。观察助手只能查这里保留的范围。")
    OutlinedTextField(query, { query = it }, label = { Text("搜索模型、助手或事件") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    val found = rows.filter { query.isBlank() || it.toString().contains(query, true) }
    Text("共 ${found.size} 条")
    found.drop(page * 20).take(20).forEach { row ->
        val id = row.getLong("sequence")
        val state = when (row.optString("dispatch")) {
            "pending" -> "待判断"; "running" -> "判断中"; "handled" -> "已处理"
            "failed" -> "处理失败"; "interrupted" -> "已中断"; "cancelled" -> "已取消判断"
            else -> "已记录"
        }
        val origin = when (val actor = row.optString("actor")) {
            "user_navigation", "user_message" -> "用户操作"
            "tool_or_user_uncertain" -> "工具活动期间，来源未确认"
            "system" -> "系统"
            else -> if (actor.startsWith("assistant:")) "助手上下文：${actor.removePrefix("assistant:")}" else "软件内变化"
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.getString("summary"))
                Text("${time.format(Date(row.getLong("at")))} · $origin · $state", style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { expanded = if (expanded == id) null else id }) { Icon(if (expanded == id) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded == id) "收起详情" else "查看详情") }
                if (expanded == id) {
                    val detail = row.getJSONObject("detail")
                    if (detail.has("observed_duration_ms")) Text("本次停留 ${detail.optLong("observed_duration_ms") / 1000} 秒")
                    detail.optJSONObject("before")?.let { Text("变更前：${it.optString("name")} ${it.optString("model")}") }
                    detail.optJSONObject("after")?.let { Text("变更后：${it.optString("name")} ${it.optString("model")}") }
                    detail.optJSONArray("changed_fields")?.let { fields ->
                        Text("变更项目：" + (0 until fields.length()).joinToString("、") { fields.optString(it) })
                    }
                    detail.optJSONArray("fields")?.let { fields ->
                        repeat(fields.length()) { i ->
                            val field = fields.getJSONObject(i)
                            Text("${field.optString("field")}：${field.opt("before")} → ${field.opt("after")}")
                        }
                    }
                    Text("记录编号 $id；${row.optString("kind")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Row {
        TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
        TextButton(enabled = (page + 1) * 20 < found.size, onClick = { page++ }) { Text("下一页") }
        OutlinedButton(onClick = { clearConfirm = true }) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("清除记录") }
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清除软件观察记录？") },
        text = { Text("会删除这里的日志并取消待判断事件。不会删除聊天内容。") },
        confirmButton = { TextButton(onClick = { perform {
            withContext(Dispatchers.IO) { store.clear() }; changed(); rows = emptyList(); page = 0; clearConfirm = false
        } }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
}
