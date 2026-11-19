package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.AutonomousJournal
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun AutonomousJournalPanel(role: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(role) { mutableStateOf(false) }
    var offset by remember(role) { mutableIntStateOf(0) }
    var rows by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var next by remember(role) { mutableStateOf<Int?>(null) }
    var error by remember(role) { mutableStateOf("") }
    var notice by remember(role) { mutableStateOf("") }
    var exportBusy by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起运行日志" else "查看运行日志") }
    if (!expanded) return
    LaunchedEffect(role, offset) {
        while (isActive) {
            try {
                val page = withContext(Dispatchers.IO) { AutonomousJournal(context).page(role, offset) }
                val items = page.getJSONArray("items")
                rows = (0 until items.length()).map(items::getJSONObject)
                next = if (page.isNull("next_offset")) null else page.getInt("next_offset")
                error = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("AutonomousJournalPanel", "读取失败", failure); error = failure.message.orEmpty() }
            if (offset > 0) break
            delay(3000)
        }
    }
    Text("记录每次活动、执行步骤、重试和跳过原因；重启后保留。详细工具回执仍在原活动卡片中。", style = MaterialTheme.typography.bodySmall)
    if (rows.isEmpty()) Text("这一页尚无运行记录；更新前缺失的日志无法补造。")
    for (row in rows) key(row.getString("id")) {
        var detail by remember { mutableStateOf(false) }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.getString("source") + " · 第${row.getInt("attempt")}次尝试")
            Text(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(row.getLong("at"))))
            Text(row.optString("detail"))
            TextButton(onClick = { detail = !detail }) { Text(if (detail) "收起详情" else "步骤与错误详情") }
            if (detail) SelectionContainer { Column {
                Text("活动：${row.getString("activity")}")
                val steps = row.optJSONArray("steps")
                if (steps != null) for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    Text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(step.getLong("at"))) + " " + step.getString("detail"))
                }
                if (row.has("error")) Text(row.getString("error"), color = MaterialTheme.colorScheme.error)
                if (row.has("stack")) Text(row.getString("stack"), style = MaterialTheme.typography.bodySmall)
            } }
        } }
    }
    Row {
        TextButton(enabled = offset > 0, onClick = { offset = (offset - 8).coerceAtLeast(0) }) { Text("上一页") }
        TextButton(enabled = next != null, onClick = { next?.let { offset = it } }) { Text("下一页") }
        TextButton(enabled = !exportBusy, onClick = {
            exportBusy = true
            scope.launch {
                try { notice = com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatExportHelper.exportLogs(context).message }
                finally { exportBusy = false }
            }
        }) { Text("导出诊断日志") }
    }
    if (error.isNotBlank()) Text(error)
    if (notice.isNotBlank()) Text(notice)
}
