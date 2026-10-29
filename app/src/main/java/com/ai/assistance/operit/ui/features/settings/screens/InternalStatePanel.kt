package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.companion.AssistantWindowRouter
import com.ai.assistance.operit.core.companion.InternalStateEngine
import com.ai.assistance.operit.core.companion.InternalStateStore
import com.ai.assistance.operit.core.companion.InternalStateTimeRules
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

@Composable
internal fun InternalStatePanel(role: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { InternalStateStore(context) }
    val coroutine = rememberCoroutineScope()
    var enabled by remember(role) { mutableStateOf(store.enabled(role)) }
    var reveal by remember(role) { mutableStateOf(false) }
    var category by remember(role) { mutableStateOf("drive") }
    var selectedId by remember(role, reveal) { mutableStateOf<String?>(null) }
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
            catch (failure: Exception) {
                AppLogger.e("InternalStatePanel", "状态观察读取失败", failure)
                error = "内部状态读取失败，请稍后重试。"
                data = JSONObject()
            }
            delay(3000)
        }
    }
    SpaceSectionTitle("内部状态 · Internal State", "由助手定义和回报，点开一个状态查看它自己的变化。")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("开启这位助手的内部状态", Modifier.weight(1f))
        Switch(checked = enabled, enabled = !busy, onCheckedChange = { value ->
            busy = true
            coroutine.launch {
                try { withContext(Dispatchers.IO) { store.configure(role, value) }; enabled = value }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLogger.e("InternalStatePanel", "状态开关保存失败", failure)
                    error = "设置未保存，请稍后重试。"
                } finally { busy = false }
            }
        })
    }
    if (!enabled) Text("已暂停计算与注入，定义和历史保留。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("展开助手愿意公开的私密层", Modifier.weight(1f))
        Switch(checked = reveal, onCheckedChange = { reveal = it })
    }
    Text("收起只影响展示。未获许可的私密状态和历史不会显示。", style = MaterialTheme.typography.bodySmall)
    val states = InternalStateEngine.rows(data, "states")
    Text("当前", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("drive", "sentiment", "emotion", "psychology").forEach { kind ->
            val count = states.count { InternalStateEngine.kind(it) == kind }
            FilterChip(selected = category == kind, onClick = { category = kind }, label = { Text("${stateKindLabel(kind)} $count") })
        }
    }
    val classified = states.filter { InternalStateEngine.kind(it) == category }
    if (classified.isEmpty()) Text("暂无可见的${stateKindLabel(category)}状态。", style = MaterialTheme.typography.bodySmall)
    classified.forEach { state ->
        key(state.getString("id"), state.getString("instance_scope")) {
            StateCompactCard(state, stateTimeline(data, state)) { selectedId = state.getString("id") }
        }
    }
    val pending = states.filter { InternalStateEngine.kind(it) == "unclassified" }
    if (pending.isNotEmpty()) {
        Text("待分类 · ${pending.size}", style = MaterialTheme.typography.titleSmall)
        Text("这些已有状态尚未归入四个模块，可让助手补充分类和新时间规则。", style = MaterialTheme.typography.bodySmall)
        pending.forEach { state ->
            key(state.getString("id"), state.getString("instance_scope")) {
                StateCompactCard(state, stateTimeline(data, state)) { selectedId = state.getString("id") }
            }
        }
    }
    if (states.isEmpty()) Text("开启后，助手可以根据经历新增状态，无需你填写数值。", style = MaterialTheme.typography.bodySmall)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    val selected = states.firstOrNull { it.getString("id") == selectedId }
    if (selected != null) {
        key(selected.getString("id"), selected.getString("instance_scope")) { StateDetailDialog(selected, data) { selectedId = null } }
    }
}

@Composable
private fun StateCompactCard(state: JSONObject, timeline: List<StateTimelineEntry>, open: () -> Unit) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val visible = timeline.takeLast(60)
    val selected = visible.firstOrNull { it.key == selectedKey }
    OutlinedCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看${state.getString("name")}详情", onClick = open)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.getBoolean("private")) Text("私密已许可", style = MaterialTheme.typography.labelSmall)
                Text(state.getDouble("value").display(), Modifier.widthIn(max = 110.dp), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
            StateTrend(visible, state, selectedKey, Modifier.fillMaxWidth().height(42.dp), { selectedKey = it.key }, open)
            if (selected != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.at.date()} · ${stateSourceLabel(selected.source)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { selectedKey = null }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("收起") }
                }
                Text("${selected.before.number()} → ${selected.after.number()}", style = MaterialTheme.typography.bodySmall)
                val reason = selected.experience?.optString("reason").orEmpty()
                if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall)
                selected.timeReasons.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun StateDetailDialog(state: JSONObject, observation: JSONObject, close: () -> Unit) {
    val timeline = stateTimeline(observation, state)
    val versions = InternalStateEngine.rows(observation, "versions").filter { it.getString("dimension") == state.getString("id") }.asReversed()
    var limit by remember { mutableIntStateOf(60) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val visible = timeline.takeLast(limit)
    val selected = visible.firstOrNull { it.key == selectedKey }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${stateKindLabel(InternalStateEngine.kind(state))} · ${if (state.getBoolean("private")) "私密已许可" else "公开"}", style = MaterialTheme.typography.labelLarge)
                    Text(state.getString("description"))
                    Text(state.getDouble("value").display(), style = MaterialTheme.typography.headlineMedium)
                    Text("基准 ${state.getDouble("baseline").number()} · 范围 ${state.getDouble("min").number()}–${state.getDouble("max").number()}", style = MaterialTheme.typography.bodySmall)
                    Text(if (state.getString("scope") == "global") "跨窗口持续" else "当前会话独立", style = MaterialTheme.typography.labelSmall)
                    if (state.has("object_ref")) Text("对象：${state.getString("object_ref")}")
                    if (state.has("reason")) Text("最近体验：${state.getString("reason")}")
                    if (state.optBoolean("unresolved")) Text("仍有未解决的部分")
                    if (state.optString("desire").isNotBlank()) Text("想要：${state.getString("desire")}")
                    if (state.optString("choice").isNotBlank()) Text("当前选择：${state.getString("choice")}")
                    Text("时间变化", style = MaterialTheme.typography.titleMedium)
                    StateTrend(visible, state, selectedKey, Modifier.fillMaxWidth().height(150.dp), { selectedKey = it.key })
                    if (visible.isEmpty()) Text("尚无可见变化，当前值 ${state.getDouble("value").number()}。", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("${visible.first().at.date()} — ${visible.last().at.date()}", style = MaterialTheme.typography.labelSmall)
                        Text("纵轴随可见数值缩放 · 点圆点查看事件 · 隐藏的历史不会补线", style = MaterialTheme.typography.labelSmall)
                        val position = visible.indexOfFirst { it.key == selectedKey }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = position != 0, onClick = { selectedKey = visible[if (position < 0) visible.lastIndex else position - 1].key }) { Text("上一变化") }
                            TextButton(enabled = position < visible.lastIndex, onClick = { selectedKey = visible[position + 1].key }) { Text("下一变化") }
                        }
                    }
                    if (selected != null) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { StateEventDetail(selected) }
                        }
                    }
                    StateFold("演化规则 · v${state.getInt("version")}") { StateRuleDetails(state) }
                    StateFold("规则与名称历史 · ${versions.size}") {
                        if (versions.isEmpty()) Text("暂无可见版本。")
                        versions.forEach { version ->
                            key(version.getInt("version")) {
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("v${version.getInt("version")} · ${stateOperationLabel(version.getString("operation"))} · ${version.getLong("at").date()}", style = MaterialTheme.typography.titleSmall)
                                    Text(version.getString("reason"))
                                    Text("经历依据：${version.getJSONArray("evidence")}", style = MaterialTheme.typography.bodySmall)
                                    StateFold("查看此版完整定义") {
                                        val definition = version.getJSONObject("after")
                                        Text(definition.getString("name"), style = MaterialTheme.typography.titleSmall)
                                        Text(definition.getString("description"))
                                        StateRuleDetails(definition, historical = true)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    Text("本状态事件 · ${timeline.size}", style = MaterialTheme.typography.titleMedium)
                    visible.asReversed().forEach { entry ->
                        key(entry.key) {
                            OutlinedCard(Modifier.fillMaxWidth().clickable { selectedKey = entry.key }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { StateEventDetail(entry) }
                            }
                        }
                    }
                    if (timeline.size > limit) TextButton(onClick = { limit += 60 }) { Text("加载更早的变化") }
                    Text("状态标识：${state.getString("id")} · 改名后历史仍保留", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StateFold(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded }
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun StateEventDetail(entry: StateTimelineEntry) {
    Text("${entry.at.date()} · ${stateSourceLabel(entry.source)}", style = MaterialTheme.typography.titleSmall)
    Text("${entry.before.number()} → ${entry.after.number()} · 规则 v${entry.version}")
    if (entry.intervalStart != null) Text("时间段：${entry.intervalStart.date()} — ${entry.at.date()}", style = MaterialTheme.typography.bodySmall)
    entry.timeReasons.distinct().forEach { Text("原因：$it") }
    entry.experience?.let { experience ->
        if (experience.has("reason")) Text(experience.getString("reason"))
        if (experience.optBoolean("unresolved")) Text("当时仍有未解决的部分")
        if (experience.optString("desire").isNotBlank()) Text("想要：${experience.getString("desire")}")
        if (experience.optString("choice").isNotBlank()) Text("选择：${experience.getString("choice")}")
        val conditions = experience.optJSONObject("time_conditions")
        if (conditions != null) listOf("increase", "decrease").filter { conditions.has(it) }.forEach { direction ->
            Text("${if (direction == "increase") "增加" else "减少"}的自定义条件：${if (conditions.getBoolean(direction)) "成立" else "结束"}")
        }
    }
    Text("事件：${entry.eventId}", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun StateRuleDetails(rule: JSONObject, historical: Boolean = false) {
    Text("分类：${stateKindLabel(InternalStateEngine.kind(rule))}")
    Text("范围 ${rule.getDouble("min").number()}–${rule.getDouble("max").number()} · 基准 ${rule.getDouble("baseline").number()}")
    Text(if (rule.getString("scope") == "global") "适用范围：同一助手跨窗口" else "适用范围：各会话独立")
    if (rule.has("object_ref")) Text("对象：${rule.getString("object_ref")}")
    Text(if (rule.getBoolean("private")) "私密状态 · 已允许本机展示" else "公开状态")
    if (rule.has("active") && !rule.getBoolean("active")) Text("此版本已停用")
    if (InternalStateTimeRules.isDefined(rule)) {
        val time = rule.getJSONObject("time_rules")
        listOf("increase", "decrease").forEach { direction ->
            val branch = time.getJSONObject(direction)
            val verb = if (direction == "increase") "涨" else "降"
            Text("${if (direction == "increase") "增加" else "减少"}：${stateConditionLabel(branch.getString("condition"))}", style = MaterialTheme.typography.titleSmall)
            Text("「${branch.getString("reason")}」时，每 ${branch.getDouble("interval_minutes").number()} 分钟$verb ${branch.getDouble("amount").number()}")
            if (branch.getDouble("interval_minutes") <= 0.0 || branch.getDouble("amount") == 0.0) Text("本条时间涨落已暂停", style = MaterialTheme.typography.bodySmall)
            val activity = rule.optJSONObject("time_activity")
            if (activity != null && activity.has(direction) && !activity.isNull(direction)) {
                Text("当前条件：${if (activity.getBoolean(direction)) "成立" else "未成立"}", style = MaterialTheme.typography.bodySmall)
            } else if (branch.getString("condition") == "custom") {
                Text("自定义条件由助手报告成立或结束，未展示确认状态。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("时间下降最低至 ${time.getDouble("floor").number()}；事件回报仍可在状态总范围内变化。")
        if (time.has("contact_window_minutes")) Text("未联系判定：距最近真实用户消息 ${time.getDouble("contact_window_minutes").number()} 分钟。无可确认消息时不据此计时。")
        Text("事件变化由助手回报。新时间规则生效后，旧触发和半衰期不再参与计算。", style = MaterialTheme.typography.bodySmall)
        if (rule.has("halfLifeHours") || rule.has("triggers") || rule.has("maxDelta")) StateFold("保留的旧参数 · 已停用") { StateLegacyRules(rule) }
    } else {
        Text(if (historical) "此版本尚未定义新的条件时间规则。" else "新时间规则未定义，原演化规则继续生效。", color = MaterialTheme.colorScheme.primary)
        Text("增加条件、间隔、变化量：未定义\n减少条件、间隔、变化量：未定义\n时间下降最低值：未定义")
        StateLegacyRules(rule)
    }
    Text(if (rule.getBoolean("participates")) "参与意向和行动判断" else "仅记录，不参与决策")
    InternalStateEngine.rows(rule, "effects").forEach { effect ->
        Text("${stateActionLabel(effect.getString("action"))} · 权重 ${effect.getDouble("weight").number()}：${effect.getString("cue")}" +
            if (effect.optString("target").isNotBlank()) "\n对象：${effect.getString("target")}" else "")
    }
}

@Composable
private fun StateLegacyRules(rule: JSONObject) {
    if (rule.has("maxDelta")) Text("旧客观事件单次上限：${rule.getDouble("maxDelta").number()}")
    if (rule.has("halfLifeHours")) {
        val rate = InternalStateEngine.rows(rule, "triggers").filter { it.getString("source") == "time" }.sumOf { it.getDouble("delta") }
        val half = rule.getDouble("halfLifeHours")
        Text("原半衰期 ${half.number()} 小时 · 原持续漂移每小时 ${rate.number()}")
        if (half == 0.0) Text(if (rate == 0.0) "原时间规则保持数值不变" else "原时间规则按经过时间线性变化")
        else Text("原时间规则每 ${half.number()} 小时，与平衡水平 ${(rule.getDouble("baseline") + rate * half / kotlin.math.ln(2.0)).number()} 的差距缩小一半")
    }
    InternalStateEngine.rows(rule, "triggers").filter { it.getString("source") != "time" }.forEach { trigger ->
        val source = trigger.getString("source")
        Text("${stateSourceLabel(source)}：${trigger.getDouble("delta").number()}" + if (trigger.optString("contains").isNotBlank()) "，内容含「${trigger.getString("contains")}」" else "")
        if (source in setOf("user_message", "assistant_reply", "wake", "assistant_action")) Text("此主观触发仅保留记录，变化改由助手回报。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StateTrend(entries: List<StateTimelineEntry>, state: JSONObject, selectedKey: String?, modifier: Modifier,
    select: (StateTimelineEntry) -> Unit, open: (() -> Unit)? = null) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surface
    val geometry = stateChartGeometry(entries, state.getDouble("value"), state.getLong("at"))
    fun position(at: Long, value: Double, width: Float, height: Float, padding: Float): Offset {
        val x = if (geometry.start == geometry.end) 0.5 else (at - geometry.start).toDouble() / (geometry.end - geometry.start).toDouble()
        val y = (value - geometry.low) / (geometry.high - geometry.low)
        return Offset(padding + ((width - padding * 2) * x).toFloat(), padding + ((height - padding * 2) * (1 - y)).toFloat())
    }
    val selectedIndex = entries.indexOfFirst { it.key == selectedKey }
    Canvas(modifier.semantics {
        role = Role.Image
        contentDescription = "${state.getString("name")}趋势，当前 ${state.getDouble("value").display()}，${entries.size} 个可见变化点"
        customActions = buildList {
            if (entries.isNotEmpty()) {
                add(CustomAccessibilityAction("上一变化") { select(entries[if (selectedIndex <= 0) entries.lastIndex else selectedIndex - 1]); true })
                add(CustomAccessibilityAction("下一变化") { select(entries[if (selectedIndex >= entries.lastIndex) 0 else selectedIndex + 1]); true })
            }
            if (open != null) add(CustomAccessibilityAction("查看状态详情") { open(); true })
        }
    }.pointerInput(entries, geometry, selectedKey) {
        detectTapGestures { tap ->
            val point = entries.minByOrNull {
                val location = position(it.at, it.after, size.width.toFloat(), size.height.toFloat(), 5.dp.toPx())
                (location - tap).getDistanceSquared()
            }
            if (point != null) select(point) else open?.invoke()
        }
    }) {
        val padding = 5.dp.toPx()
        listOf(0.25f, 0.75f).forEach { fraction -> drawLine(grid.copy(alpha = 0.45f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 0.6.dp.toPx()) }
        geometry.segments.forEach { segment ->
            drawLine(color, position(segment.fromAt, segment.fromValue, size.width, size.height, padding),
                position(segment.toAt, segment.toValue, size.width, size.height, padding), 1.7.dp.toPx(), cap = StrokeCap.Round)
        }
        entries.forEach { point ->
            val location = position(point.at, point.after, size.width, size.height, padding)
            if (point.key == selectedKey) {
                drawLine(grid, Offset(location.x, 0f), Offset(location.x, size.height), 1.dp.toPx())
                drawCircle(background, 5.dp.toPx(), location)
            }
            drawCircle(color, (if (point.key == selectedKey) 3.5.dp else 2.dp).toPx(), location)
        }
        if (entries.isEmpty()) drawCircle(color, 3.dp.toPx(), position(state.getLong("at"), state.getDouble("value"), size.width, size.height, padding))
    }
}

private fun Double.display() = String.format(java.util.Locale.getDefault(), "%.2f", this)
private fun Double.number() = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
private fun Long.date() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
private fun stateSourceLabel(source: String) = mapOf("user_message" to "用户新消息", "assistant_reply" to "助手回复",
    "assistant_action" to "自主选择", "tool_success" to "工具成功", "tool_failure" to "工具失败", "reading" to "阅读",
    "exploration" to "探索结果", "todo_progress" to "待办推进", "todo_failed" to "待办失败", "todo_completed" to "待办完成",
    "wake" to "主动唤醒", "time" to "时间涨落", "merge" to "状态合并", "state_report" to "主体状态回报").getValue(source)
private fun stateKindLabel(kind: String) = when (kind) {
    "drive" -> "驱力"
    "sentiment" -> "情感"
    "emotion" -> "情绪"
    "psychology" -> "心理"
    "unclassified" -> "待分类"
    else -> error("未知状态类别")
}
private fun stateConditionLabel(condition: String) = when (condition) {
    "always" -> "任何"
    "not_contacted" -> "未联系"
    "custom" -> "自定义"
    else -> error("未知时间条件")
}
private fun stateOperationLabel(operation: String) = mapOf("create" to "新增", "revise" to "修订", "deactivate" to "停用", "merge" to "合并").getValue(operation)
private fun stateActionLabel(action: String) = mapOf("contact" to "联系", "explore" to "探索", "todo" to "待办",
    "tool" to "工具", "reply" to "表达", "defer" to "暂缓", "drop" to "放下", "rest" to "休息").getValue(action)
