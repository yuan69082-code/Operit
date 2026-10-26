package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
internal fun InternalStatePanel(role: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { InternalStateStore(context) }
    val coroutine = rememberCoroutineScope()
    var enabled by remember(role) { mutableStateOf(store.enabled(role)) }
    var reveal by remember(role) { mutableStateOf(false) }
    var all by remember(role) { mutableStateOf(false) }
    var page by remember(role) { mutableIntStateOf(0) }
    var limit by remember(role, page) { mutableIntStateOf(30) }
    var data by remember(role, reveal) { mutableStateOf(JSONObject()) }
    var error by remember(role) { mutableStateOf("") }
    var busy by remember(role) { mutableStateOf(false) }
    LaunchedEffect(role, reveal, enabled) {
        while (isActive) {
            try {
                data = withContext(Dispatchers.IO) {
                    val chat = AssistantWindowRouter.chat(context, role).orEmpty()
                    InternalStateEngine.observation(store.snapshot(role, chat, false), chat, System.currentTimeMillis(), reveal)
                }
                error = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "内部状态读取失败：${failure.message}"; data = JSONObject() }
            delay(3000)
        }
    }
    SpaceSectionTitle("内部状态 · Internal State", "由助手定义，由真实事件和时间计算。这里是观察窗。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("开启这位助手的内部状态")
        Switch(checked = enabled, enabled = !busy, onCheckedChange = { value ->
            busy = true
            coroutine.launch {
                try { withContext(Dispatchers.IO) { store.configure(role, value) }; enabled = value }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = "设置未保存：${failure.message}" }
                finally { busy = false }
            }
        })
    }
    Text(if (enabled) "已优先进入聊天与唤醒决策；XC 连接保留，不叠加自动状态提示。" else "已暂停计算与注入；定义和历史保留。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("展开助手愿意公开的私密层")
        Switch(checked = reveal, onCheckedChange = { reveal = it })
    }
    Text("收起只影响展示，不停止计算。未获助手许可的私密维度不会出现在卡片、趋势、事件或版本页。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("当前", "事件", "演化规则").forEachIndexed { index, title ->
            FilterChip(selected = page == index, onClick = { page = index }, label = { Text(title) })
        }
    }
    val states = InternalStateEngine.rows(data, "states")
    val events = InternalStateEngine.rows(data, "events")
    when (page) {
        0 -> {
            if (states.isEmpty()) Text("暂无可见维度。开启后，助手可在对话中根据真实经历自行定义，无需人类填写数值。")
            val sorted = states.sortedByDescending { abs(it.getDouble("intensity")) }
            (if (all) sorted else sorted.take(5)).forEach { state ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.getString("name") + if (state.getBoolean("private")) " · 私密已许可" else " · 公开",
                            style = MaterialTheme.typography.titleMedium)
                        Text(state.getString("description"))
                        Text("${state.getDouble("value").display()} · 基准 ${state.getDouble("baseline").display()} · v${state.getInt("version")}")
                        Text(if (state.getString("scope") == "global") "跨窗口持续" else "当前活跃会话局部",
                            style = MaterialTheme.typography.labelSmall)
                        val points = events.flatMap { event -> InternalStateEngine.rows(event, "changes")
                            .filter { it.getString("dimension") == state.getString("id") &&
                                it.getString("scope") == state.getString("instance_scope") }
                            .map { event.getLong("at") to it.getDouble("after") } }.takeLast(60)
                        StateTrend(points, state)
                        Text("最近${points.size}次可见变化 · ${state.getDouble("min").display()}–${state.getDouble("max").display()}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (states.size > 5) TextButton(onClick = { all = !all }) { Text(if (all) "收起" else "查看全部 ${states.size} 个可见维度") }
        }
        1 -> {
            if (events.isEmpty()) Text("暂无可见变化事件。")
            events.asReversed().take(limit).forEach { event ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${event.getLong("at").date()} · ${stateSourceLabel(event.getString("source"))}")
                        Text("事件 ${event.getString("id")} · 窗口 ${event.optString("chat")}", style = MaterialTheme.typography.labelSmall)
                        InternalStateEngine.rows(event, "changes").forEach {
                            Text("${it.getString("dimension")}：${it.getDouble("before").display()} → ${it.getDouble("after").display()} · 规则 v${it.getInt("version")}")
                        }
                    }
                }
            }
            if (events.size > limit) TextButton(onClick = { limit += 30 }) { Text("更早的事件") }
        }
        2 -> {
            val versions = InternalStateEngine.rows(data, "versions").asReversed()
            if (versions.isEmpty()) Text("暂无可见演化记录。")
            versions.take(limit).forEach { version ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${version.getString("dimension")} · v${version.getInt("version")} · ${version.getString("operation")}", style = MaterialTheme.typography.titleMedium)
                        Text(version.getLong("at").date())
                        Text(version.getString("reason"))
                        Text("经历依据：${version.getJSONArray("evidence")}")
                        val rule = version.getJSONObject("after")
                        Text(rule.getString("name") + "：" + rule.getString("description"))
                        Text("范围 ${rule.getDouble("min").display()}–${rule.getDouble("max").display()} · 基准 ${rule.getDouble("baseline").display()} · 单次上限 ${rule.getDouble("maxDelta").display()}")
                        Text(if (rule.getDouble("halfLifeHours") == 0.0) "不自动回归基准" else "每 ${rule.getDouble("halfLifeHours").display()} 小时，偏离基准的部分恢复一半")
                        Text(if (rule.getBoolean("participates")) "参与意向和行动判断" else "只记录，不参与决策")
                        InternalStateEngine.rows(rule, "triggers").forEach { trigger ->
                            Text("${stateSourceLabel(trigger.getString("source"))}：${trigger.getDouble("delta").display()}" +
                                if (trigger.optString("contains").isNotBlank()) "，内容含「${trigger.getString("contains")}」" else "")
                        }
                        InternalStateEngine.rows(rule, "effects").forEach { effect ->
                            Text("${stateActionLabel(effect.getString("action"))} · 权重 ${effect.getDouble("weight").display()}：${effect.getString("cue")}" +
                                if (effect.optString("target").isNotBlank()) "（${effect.getString("target")}）" else "")
                        }
                        if (version.has("target")) Text("合并至：${version.getString("target")}")
                    }
                }
            }
            if (versions.size > limit) TextButton(onClick = { limit += 30 }) { Text("更早的版本") }
        }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}

private fun Double.display() = String.format(java.util.Locale.getDefault(), "%.2f", this)
private fun Long.date() = DateFormat.getDateTimeInstance().format(Date(this))
private fun stateSourceLabel(source: String) = mapOf("user_message" to "用户新消息", "assistant_reply" to "助手回复",
    "assistant_action" to "自主选择", "tool_success" to "工具成功", "tool_failure" to "工具失败", "reading" to "阅读",
    "exploration" to "探索结果", "todo_progress" to "待办推进", "todo_failed" to "待办失败", "todo_completed" to "待办完成",
    "wake" to "主动唤醒", "time" to "时间流逝（漂移按每小时）", "merge" to "维度合并").getValue(source)
private fun stateActionLabel(action: String) = mapOf("contact" to "联系", "explore" to "探索", "todo" to "待办",
    "tool" to "工具", "reply" to "表达", "defer" to "暂缓", "drop" to "放下", "rest" to "休息").getValue(action)

@Composable
private fun StateTrend(points: List<Pair<Long, Double>>, state: JSONObject) {
    val color = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val min = state.getDouble("min")
        val range = state.getDouble("max") - min
        fun y(value: Double) = size.height * (1 - ((value - min) / range).coerceIn(0.0, 1.0).toFloat())
        val baseline = y(state.getDouble("baseline"))
        drawLine(baselineColor, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        if (points.isEmpty()) drawCircle(color, 3.dp.toPx(), Offset(size.width, y(state.getDouble("value"))))
        else {
            val start = points.first().first
            val span = (points.last().first - start).coerceAtLeast(1)
            val positions = points.map { Offset(size.width * ((it.first - start).toDouble() / span).toFloat(), y(it.second)) }
            positions.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 2.dp.toPx()) }
            positions.lastOrNull()?.let { drawCircle(color, 3.dp.toPx(), it) }
        }
    }
}
