package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
internal fun ExplorationPanel(initialRole: String) {
    val context = LocalContext.current
    val store = remember(context) { ExplorationStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var role by remember(initialRole) { mutableStateOf(initialRole) }
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
                store.configure(role, false,
                    ExplorationPolicy.choices(data), data.optInt("limit", 4))
            } else {
                val dailyLimit = requireNotNull(limit.toIntOrNull()) { "每日轮次请填写整数" }
                store.configure(role, true, choices, dailyLimit)
            }
        }
    }

    SpaceSectionTitle("探索权限")
    AssistantTargetPicker(role, !busy) { role = it }
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
    Button(enabled = loaded && !busy && status.optBoolean("enabled"), onClick = { configure(true) }) {
        Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("保存探索设置")
    }
    val chat = status.optString("chat")
    Text("今天已启动 ${status.optInt("used_today")} / ${status.optInt("daily_limit", 4)} 轮")
    val at = status.optLong("at")
    Text(when {
        !status.optBoolean("enabled") -> "当前关闭，不会自动开始探索"
        status.optBoolean("running") -> "正在探索"
        status.optBoolean("paused", true) || at <= 0 -> "暂无下一次探索预约"
        else -> "下次计划：" + DateFormat.getDateTimeInstance().format(Date(at))
    })
    if (status.optBoolean("enabled") && status.optString("purpose").isNotBlank()) Text(status.optString("purpose"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(enabled = loaded && !busy && status.optBoolean("enabled") && chat.isNotBlank(),
            onClick = { perform { withContext(Dispatchers.IO) { store.schedule(role, chat, 15, "用户只提供一次自由判断机会；是否探索、探索什么、转向还是休息，由你自己决定。") } } }) {
            Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp)); Text("15分钟后探索一次")
        }
        OutlinedButton(enabled = loaded && !busy && status.optBoolean("enabled"),
            onClick = { perform { withContext(Dispatchers.IO) { store.pause(role) } } }) { Icon(Icons.Default.Pause, null); Spacer(Modifier.width(8.dp)); Text("暂停后续预约") }
    }
    SpaceExplanation("探索如何安排", "首次开启会在15分钟后给一次自由判断机会。之后 AI 可以自己安排下一次或暂停；若一轮结束时两者都没做，软件默认3小时后再给一次自由判断机会，这不等于继续同一方向。需要后台服务运行，实际执行也取决于手机系统和当前对话是否空闲。达到每日上限后，预约等待下一天；关闭再开启不会重置当天次数。过程见“唤醒与记录”，收获见书房资料和私人打算。")
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}
