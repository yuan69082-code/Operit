package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.awareness.PhoneAwareness
import com.ai.assistance.operit.core.awareness.PhoneAwarenessStore
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhoneAwarenessPanel(currentChat: String?) {
    val context = LocalContext.current
    val store = remember { PhoneAwarenessStore(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.enabled) }
    var wake by remember { mutableStateOf(store.wakeEnabled) }
    var interval by remember { mutableStateOf((store.intervalMs / 1000).toString()) }
    var selectedChat by remember { mutableStateOf(store.chat) }
    var observer by remember { mutableStateOf("尚未选择") }
    var status by remember { mutableStateOf(JSONObject()) }
    var hasUsage by remember { mutableStateOf(false) }
    var notificationConnected by remember { mutableStateOf(false) }
    val accessibilityConnected by com.ai.assistance.operit.data.repository.UIHierarchyManager.isBound.collectAsState()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var excluded by remember { mutableStateOf(store.excluded) }
    var contentApps by remember { mutableStateOf(store.contentApps) }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
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
    fun openSettings(action: String) { perform { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    LaunchedEffect(selectedChat) {
        try {
            observer = if (selectedChat == null) "尚未选择" else {
                val chat = ChatHistoryManager.getInstance(context).getChatMetadata(checkNotNull(selectedChat))
                val card = CharacterCardManager.getInstance(context).getAllCharacterCards().firstOrNull { it.id == chat?.characterCardId }
                if (chat == null || card == null || !chat.characterGroupId.isNullOrBlank()) "目标会话已失效，请重新指定"
                else card.name + " · " + chat.title
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "无法读取观察助手：${failure.message}" }
    }
    LaunchedEffect(Unit) {
        try {
            apps = withContext(Dispatchers.IO) {
                context.packageManager.getInstalledApplications(0).map { it.packageName to context.packageManager.getApplicationLabel(it).toString() }
                    .sortedBy { it.second.lowercase() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "应用列表读取失败：${failure.message}" }
        while (isActive) {
            try {
                withContext(Dispatchers.IO) { Triple(store.data(), store.rows().takeLast(20).asReversed(), PhoneAwareness.usageAccess(context)) }
                    .let { (data, events, access) -> status = data; rows = events; hasUsage = access }
                notificationConnected = com.ai.assistance.operit.services.notification.OperitNotificationStore.connected &&
                    context.packageName in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "观察记录读取失败：${failure.message}" }
            delay(2500)
        }
    }
    LaunchedEffect(query) { page = 0 }

    Text("手机感知", style = MaterialTheme.typography.titleLarge)
    Text("本机记录亮灭屏、锁定/解锁，以及应用进入、离开和停留时间。应用事件约每2秒读取一次；不代表她一定在看或操作。服务被系统停止、强制退出或权限不足时会有记录缺口。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("记录手机状态", Modifier.weight(1f))
        Switch(enabled = !busy, checked = enabled, onCheckedChange = { value -> perform {
            store.preferences.edit().putBoolean("phone_enabled", value).apply()
            enabled = value; wake = store.wakeEnabled
            AIForegroundService.refreshBackgroundKeepAlive(context)
        } })
    }
    Text("使用情况访问：" + if (hasUsage) "已授权" else "未授权，暂时只能记录亮灭屏和锁定")
    TextButton(enabled = !busy, onClick = { perform {
        withContext(Dispatchers.IO) { PhoneAwareness.grantUsageWithShizuku(context) }
        hasUsage = PhoneAwareness.usageAccess(context)
    } }) { Text("用已授权 Shizuku 开通使用情况访问") }
    TextButton(onClick = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("打开系统使用情况权限设置") }
    Text("观察助手：$observer")
    TextButton(enabled = !busy && currentChat != null, onClick = { perform {
        val id = checkNotNull(currentChat)
        val chat = ChatHistoryManager.getInstance(context).getChatMetadata(id) ?: error("请先打开目标助手的私聊")
        check(chat.characterGroupId.isNullOrBlank()) { "请选择独立助手的私聊" }
        val role = requireNotNull(chat.characterCardId) { "当前会话尚未绑定助手，请先发送一条消息" }
        CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", "phone_awareness", true)
        store.preferences.edit().putString("phone_chat", id).putString("phone_role", role).apply()
        selectedChat = id
    } }) { Text("让当前私聊的助手负责观察") }
    Text("可先打开主助手的私聊再指定。仅这位助手可在自己的私聊查记录；群聊和其他助手不会自动收到。选择时会启用她/他的手机感知查询工具，其他能力仍使用各自设置。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("解锁或进入应用时唤醒助手", Modifier.weight(1f))
        Switch(enabled = enabled && selectedChat != null && !busy, checked = wake, onCheckedChange = { value ->
            store.preferences.edit().putBoolean("phone_wake_enabled", value).apply(); wake = value
        })
    }
    OutlinedTextField(interval, { interval = it }, label = { Text("两次事件判断至少间隔多少秒（5–600）") }, singleLine = true)
    TextButton(enabled = !busy, onClick = { perform {
        val seconds = interval.toIntOrNull()
        require(seconds != null && seconds in 5..600) { "请填写5–600的整数秒数" }
        store.preferences.edit().putInt("phone_wake_seconds", seconds).apply()
    } }) { Text("保存间隔") }
    Text("每次状态变化先记在本机，密集切换合并成一次判断。对话或通话中延后；AI 工具运行期间及结束后5秒内的变化仅记录，避免操作自己触发自己。超过5分钟的待处理事件不再触发干预。判断会消耗所选助手的模型额度。内部事件不显示在聊天框，助手可以安静、使用工具或主动发消息。")

    HorizontalDivider()
    Text("应用与内容授权", style = MaterialTheme.typography.titleMedium)
    Text("默认记录所有应用的进出，不读取正文。每个应用可单独授权通知和已见页面缓存。取消记录会清除该应用已保存的观察数据；取消内容授权会清除页面缓存。查询不会偷偷打开应用。")
    TextButton(onClick = { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("通知读取权限") }
    TextButton(onClick = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("页面文字读取所需的无障碍权限") }
    Text("通知读取通道：${if (notificationConnected) "已连接" else "未连接"}；页面读取通道：${if (accessibilityConnected) "已连接" else "未连接"}。内容授权与通道连接分别生效。")
    Text("通知只能反映应用发出的内容；已见页面由无障碍服务在应用可见、解锁时最多每15秒缓存一次。未见过的页面、未显示的消息和应用私有数据库不会凭 Shizuku 自动变得可读。")
    OutlinedTextField(query, { query = it }, label = { Text("搜索应用名称或包名") }, singleLine = true)
    val found = apps.filter { it.first.contains(query, true) || it.second.contains(query, true) }
    found.drop(page * 12).take(12).forEach { (pkg, name) ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(name); Text(pkg, style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(checked = pkg !in excluded, enabled = !busy, onCheckedChange = { value -> perform {
                        excluded = if (value) excluded - pkg else excluded + pkg
                        if (!value) contentApps = contentApps - pkg
                        store.preferences.edit().putStringSet("phone_excluded", excluded).putStringSet("phone_content_apps", contentApps).apply()
                        withContext(Dispatchers.IO) { store.prune() }
                    } })
                    Text("记录进出", Modifier.padding(top = 12.dp))
                    Checkbox(checked = pkg in contentApps, enabled = pkg !in excluded && !busy, onCheckedChange = { value -> perform {
                        contentApps = if (value) contentApps + pkg else contentApps - pkg
                        store.preferences.edit().putStringSet("phone_content_apps", contentApps).apply()
                        withContext(Dispatchers.IO) { store.prune() }
                    } })
                    Text("允许内容", Modifier.padding(top = 12.dp))
                }
            }
        }
    }
    Row {
        TextButton(enabled = page > 0, onClick = { page-- }) { Text("上一页") }
        TextButton(enabled = (page + 1) * 12 < found.size, onClick = { page++ }) { Text("下一页") }
    }
    HorizontalDivider()
    Text("最近观察", style = MaterialTheme.typography.titleMedium)
    Text(status.optString("status", "尚未开始"))
    Text("最多保留7天、1500条事件及每个授权应用最近一次页面。时间是本机观察时间；灭屏不等于关机。助手具体的工具、安静或主动消息决定，可在“唤醒与日志”查看。")
    val kinds = mapOf("screen_on" to "亮屏", "screen_off" to "灭屏", "lock" to "锁定", "unlock" to "解锁", "app_enter" to "进入应用", "app_leave" to "离开应用", "collection_started" to "采集开始（此前可能有缺口）", "usage_access_started" to "应用记录权限可用", "usage_access_unavailable" to "应用记录权限不可用")
    val dispatch = mapOf("pending" to "待判断", "running" to "判断中", "handled" to "已判断", "recorded" to "仅记录", "expired" to "已过时，仅记录", "failed" to "判断失败", "interrupted" to "判断中断", "cancelled" to "已取消判断")
    rows.forEach { row ->
        Text(time.format(Date(row.optLong("at"))) + " · " + kinds.getOrDefault(row.optString("kind"), row.optString("kind")) +
            " " + row.optString("app_name") + (if (row.has("duration_ms")) " · 停留${row.optLong("duration_ms") / 1000}秒" else "") +
            " · " + dispatch.getOrDefault(row.optString("dispatch"), row.optString("dispatch")) +
            (if (row.optBoolean("during_ai_work")) "（AI 活动期间）" else ""), style = MaterialTheme.typography.bodySmall)
        if (row.optString("result").isNotBlank()) Text(row.optString("result"), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(enabled = !busy, onClick = { clearConfirm = true }) { Text("清除手机观察记录与页面缓存") }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清除观察数据？") },
        text = { Text("删除本机手机观察事件和已见页面缓存，取消尚未处理的事件。已发送的聊天消息保持不变。") },
        confirmButton = { TextButton(onClick = {
            clearConfirm = false
            perform {
                store.preferences.edit().putLong("phone_clear_revision", System.currentTimeMillis()).apply()
                withContext(Dispatchers.IO) { store.clear() }
                rows = emptyList()
            }
        }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
}
