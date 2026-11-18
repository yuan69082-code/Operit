package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.PrivateTime
import com.ai.assistance.operit.core.companion.CompanionStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PrivateTimePanel(role: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember(role) { mutableStateOf<JSONObject?>(null) }
    var essence by remember(role) { mutableStateOf<JSONObject?>(null) }
    var start by remember(role) { mutableStateOf("") }
    var end by remember(role) { mutableStateOf("") }
    var zone by remember(role) { mutableStateOf(ZoneId.systemDefault().id) }
    var enabled by remember(role) { mutableStateOf(false) }
    var memory by remember(role) { mutableStateOf("") }
    var memoryPicker by remember(role) { mutableStateOf(false) }
    var memoryNote by remember(role) { mutableStateOf(false) }
    var fatiguePicker by remember(role) { mutableStateOf(false) }
    var fatigueLabel by remember(role) { mutableStateOf("") }
    var sleepTime by remember(role) { mutableStateOf("") }
    var fatigue by remember(role) { mutableStateOf("") }
    var threshold by remember(role) { mutableStateOf("80") }
    var busy by remember(role) { mutableStateOf(false) }
    var error by remember(role) { mutableStateOf("") }
    var history by remember(role) { mutableStateOf<JSONObject?>(null) }
    var offset by remember(role) { mutableStateOf(0) }
    suspend fun load() {
        val data = withContext(Dispatchers.IO) { PrivateTime(context).status(role, menu = false) }
        snapshot = data
        data.optJSONObject("configuration")?.let {
            start = it.getString("start"); end = it.getString("end"); zone = it.getString("zone")
            enabled = it.getBoolean("enabled"); memory = it.optString("memory_location")
            sleepTime = it.optString("sleep_time"); fatigue = it.optString("fatigue_episode")
            threshold = it.optDouble("fatigue_threshold", 80.0).toString()
        }
        fatigueLabel = if (fatigue.isBlank()) "尚未选择" else withContext(Dispatchers.IO) {
            CompanionStore(context).activityRead(role, "episode", fatigue)?.let(::privateFatigueChoice)?.let { it.title + " · " + it.detail }
                ?: "原选择记录暂不可用，可重新选择或关闭疲劳提醒"
        }
        essence = withContext(Dispatchers.IO) { PrivateTime(context).essence(role) }
    }
    fun run(block: suspend () -> Unit) { scope.launch {
        busy = true
        try { block(); error = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取失败" }
        finally { busy = false }
    } }
    LaunchedEffect(role) { try { load() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message.orEmpty() } }
    Text("自由时间", style = MaterialTheme.typography.titleLarge)
    Text("先由助手定义或共同设置自己的时段。提前一小时提供活动菜单，由他安排时间表；可更改、拒绝、留空档。尚未定义时不会自动排夜班。")
    Row { Switch(enabled, { enabled = it }); Text("启用已选择的自由时间与睡觉提醒", Modifier.padding(12.dp)) }
    OutlinedTextField(start, { start = it }, label = { Text("开始 HH:mm") }, enabled = !busy)
    OutlinedTextField(end, { end = it }, label = { Text("结束 HH:mm，可跨午夜") }, enabled = !busy)
    OutlinedTextField(zone, { zone = it }, label = { Text("时区，例如 Asia/Shanghai") }, enabled = !busy)
    Text("记忆来源", style = MaterialTheme.typography.titleMedium)
    Text(memory.ifBlank { "尚未选择。点击下方按钮，选择你已经连接的 ob 或其他记忆服务。" })
    Row {
        TextButton(enabled = !busy, onClick = { memoryPicker = true }) { Text("选择记忆来源") }
        TextButton(enabled = !busy, onClick = { memoryNote = !memoryNote }) { Text(if (memoryNote) "收起说明" else "补充或修改说明") }
    }
    if (memoryNote) OutlinedTextField(memory, { memory = it }, label = { Text("资料范围说明（可选）") },
        supportingText = { Text("可写“整理沈知屿自己的记忆”。通常不需要文件路径，也不要填写密码。已有说明会保留。") }, enabled = !busy)
    OutlinedTextField(sleepTime, { sleepTime = it }, label = { Text("可选：每日询问睡觉时间 HH:mm") }, enabled = !busy)
    Text("按疲劳感受询问睡觉（可选）", style = MaterialTheme.typography.titleMedium)
    Text(fatigueLabel)
    Row {
        TextButton(enabled = !busy, onClick = { fatiguePicker = true }) { Text("选择疲劳感受") }
        if (fatigue.isNotBlank()) TextButton(enabled = !busy, onClick = { fatigue = ""; fatigueLabel = "尚未选择" }) { Text("关闭疲劳提醒") }
    }
    Text("选已有记录中代表疲劳的感受，不用填写编号。没有记录可以先不选，定时提醒仍可使用；达到提醒强度也由他决定睡不睡。", style = MaterialTheme.typography.bodySmall)
    if (fatigue.isNotBlank()) OutlinedTextField(threshold, { threshold = it }, label = { Text("达到多大强度时询问睡觉") }, enabled = !busy)
    if (memoryPicker) PrivateTimeChoiceDialog("选择记忆来源", "选择这位助手已获准使用的记忆服务或工具包。这里沿用已有连接，不需要另找文件位置。",
        load = { privateMemoryChoices(context, role) to false }, onChoose = { memory = it.value; memoryPicker = false }, onDismiss = { memoryPicker = false })
    if (fatiguePicker) PrivateTimeChoiceDialog("选择代表疲劳的感受", "按内容选择他已经记录的疲劳感受。这里只绑定提醒，不会修改这些感受。", paged = true,
        load = { page -> withContext(Dispatchers.IO) { privateFatigueChoices(context, role, page) } },
        onChoose = { fatigue = it.value; fatigueLabel = it.title + " · " + it.detail; fatiguePicker = false }, onDismiss = { fatiguePicker = false })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && snapshot != null, onClick = { run {
            val a = JSONObject().put("expected_version", snapshot?.optJSONObject("configuration")?.optInt("version") ?: 0)
                .put("enabled", enabled).put("start", start).put("end", end).put("zone", zone)
                .put("memory_location", memory).put("sleep_time", sleepTime).put("fatigue_episode", fatigue)
                .put("fatigue_threshold", threshold.toDouble())
            withContext(Dispatchers.IO) { PrivateTime(context).configure(role, a, manual = true) }; load()
        } }) { Text("保存设置") }
        TextButton(enabled = !busy, onClick = { run { load() } }) { Text("刷新") }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    Text("已保存的安排与进度", style = MaterialTheme.typography.titleMedium)
    val p = snapshot?.optJSONObject("plan")
    if (p == null) Text("当前时段尚无计划。提前一小时提供菜单，主体选择后才会保存预约。")
    else {
        Text("${p.getString("date")} · ${p.getString("zone")}")
        val items = p.getJSONArray("items")
        val format = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of(p.getString("zone")))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${format.format(Instant.ofEpochMilli(item.getLong("start")))} ～ ${format.format(Instant.ofEpochMilli(item.getLong("end")))}")
                Text(item.getString("goal")); Text("${item.getString("activity")} · ${item.getString("status")}")
                Text(item.optString("progress", "尚无进度回执"))
                if (item.has("review_results")) Text(item.getJSONArray("review_results").toString(2))
            } }
        }
    }
    Text("精华角", style = MaterialTheme.typography.titleMedium)
    Text("明确确认的当前精华参与后续上下文，使用原有预算内384 token的份额；长草稿、完整来源和历史按需读取。人格等仍使用原候选采用入口。")
    Text(essence?.optString("confirmed")?.ifBlank { "尚无已确认精华" } ?: "读取中")
    TextButton(enabled = !busy, onClick = { run {
        history = withContext(Dispatchers.IO) { PrivateTime(context).history(role, offset) }
    } }) { Text(if (offset == 0) "查看历史安排" else "读取下一页历史") }
    history?.let { h ->
        Text(h.getJSONArray("items").toString(2))
        if (!h.isNull("next_offset")) TextButton(onClick = { offset = h.getInt("next_offset"); run {
            history = withContext(Dispatchers.IO) { PrivateTime(context).history(role, offset) }
        } }) { Text("下一页") }
    }
}
