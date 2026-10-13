package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.core.companion.ExplorationPolicy
import com.ai.assistance.operit.core.companion.ExplorationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExplorationPanel(role: String, chat: String) {
    val context = LocalContext.current
    val store = remember(context) { ExplorationStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var status by remember(role) { mutableStateOf(JSONObject()) }
    var choices by remember(role) { mutableStateOf(setOf("reading")) }
    var limit by remember(role) { mutableStateOf("4") }
    var loaded by remember(role) { mutableStateOf(false) }
    var busy by remember(role) { mutableStateOf(false) }
    var error by remember(role) { mutableStateOf("") }
    LaunchedEffect(role) {
        try {
            val data = withContext(Dispatchers.IO) { store.read(role) }
            choices = ExplorationPolicy.choices(data)
            limit = data.optInt("limit", 4).toString()
            loaded = true
            while (true) {
                status = withContext(Dispatchers.IO) { store.status(role) }
                delay(3000)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message.orEmpty() }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                error = ""
                action()
                status = withContext(Dispatchers.IO) { store.status(role) }
                AIForegroundService.refreshBackgroundKeepAlive(context)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { busy = false }
        }
    }
    fun configure(enable: Boolean) = perform {
        withContext(Dispatchers.IO) {
            if (!enable) {
                // Turning off must still work with invalid, unsaved form fields.
                val data = store.read(role)
                store.configure(role, data.optString("chat", chat), false,
                    ExplorationPolicy.choices(data), data.optInt("limit", 4))
            } else {
                val dailyLimit = requireNotNull(limit.toIntOrNull()) { "每日轮次请填写整数" }
                store.configure(role, chat, true, choices, dailyLimit)
            }
        }
    }

    Text("自主探索", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("允许这位助手自主探索", modifier = Modifier.weight(1f))
        Switch(checked = status.optBoolean("enabled", false), enabled = loaded && !busy,
            onCheckedChange = { configure(it) })
    }
    Text("每位助手默认关闭，由你开启。开启后，它可以自行安排阅读、探索或整理，也可以选择休息；是否联系你另作决定。")
    Text("关闭会停止这位助手当前的探索，并撤销后续探索预约。已保存的资料、私人待办与日志保留；主动联系、感知事件和明确提交的待办仍由各自设置控制。")
    HorizontalDivider()
    Text("可做的事", style = MaterialTheme.typography.titleMedium)
    ExplorationPolicy.activities.forEach { (key, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = key in choices, enabled = loaded && !busy, onCheckedChange = { selected ->
                choices = if (selected) choices + key else choices - key
            })
            Text(label)
        }
    }
    OutlinedTextField(limit, { limit = it }, enabled = loaded && !busy, singleLine = true,
        label = { Text("每天最多探索几轮（1–24）") })
    Text("默认每天最多4轮，每轮最多5分钟和12次工具调用；这些限制不是固定的 token 金额。资料按需查阅，不会把整库放进每轮聊天。")
    TextButton(enabled = loaded && !busy && status.optBoolean("enabled"), onClick = { configure(true) }) {
        Text("保存设置并使用当前私聊")
    }
    val targetHere = status.optString("chat") == chat
    if (status.optBoolean("enabled") && !targetHere) Text("探索目前使用这位助手的另一个私聊。可保存设置改用当前私聊。")
    Text("今天已启动 ${status.optInt("used_today")} / ${status.optInt("daily_limit", 4)} 轮")
    val at = status.optLong("at")
    Text(when {
        !status.optBoolean("enabled") -> "当前关闭，不会自动开始探索"
        status.optBoolean("running") -> "正在探索"
        status.optBoolean("paused", true) || at <= 0 -> "暂无下一次探索预约"
        else -> "下次计划：" + DateFormat.getDateTimeInstance().format(Date(at))
    })
    if (status.optBoolean("enabled") && status.optString("purpose").isNotBlank()) Text(status.optString("purpose"))
    Row {
        TextButton(enabled = loaded && !busy && status.optBoolean("enabled") && targetHere,
            onClick = { perform { withContext(Dispatchers.IO) { store.schedule(role, chat, 15, "用户安排一次自主探索，可自己选择方向或安静。") } } }) {
            Text("15分钟后探索一次")
        }
        TextButton(enabled = loaded && !busy && status.optBoolean("enabled"),
            onClick = { perform { withContext(Dispatchers.IO) { store.pause(role) } } }) { Text("暂停后续预约") }
    }
    Text("首次开启安排在15分钟后；此后由 AI 决定继续的时间。需要后台服务运行，实际执行也取决于手机系统和当前对话是否空闲。达到每日上限后，预约等待下一天；关闭再开启不会重置当天次数。过程见“唤醒与日志”，收获见资料库和私人待办。")
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}
