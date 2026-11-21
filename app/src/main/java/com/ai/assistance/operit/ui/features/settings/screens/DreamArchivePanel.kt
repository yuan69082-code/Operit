package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.DreamArchive
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun DreamArchivePanel(role: String) {
    val context = LocalContext.current
    var offset by remember(role) { mutableIntStateOf(0) }
    var refresh by remember(role) { mutableIntStateOf(0) }
    var page by remember(role) { mutableStateOf<JSONObject?>(null) }
    var selected by remember(role) { mutableStateOf<JSONObject?>(null) }
    var error by remember(role) { mutableStateOf("") }
    LaunchedEffect(role, offset, refresh) {
        page = null
        error = ""
        try { page = withContext(Dispatchers.IO) { DreamArchive(context).page(role, offset) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取完整梦境失败" }
    }
    PrivateFold("完整梦境") {
        Text("这里可以查看已保存的梦。主体醒来时会自然想起片段或全文。")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        val items = page?.getJSONArray("items")
        if (items?.length() == 0) Text("还没有已保存的梦境。")
        for (index in 0 until (items?.length() ?: 0)) {
            val row = requireNotNull(items).getJSONObject(index)
            TextButton(onClick = { selected = row }) {
                Text("${DateFormat.getDateTimeInstance().format(Date(row.getLong("at")))} · ${row.getJSONObject("content").getString("title")}")
            }
        }
        Row {
            TextButton(enabled = offset > 0, onClick = { offset -= 8 }) { Text("上一页") }
            TextButton(enabled = page?.isNull("next_offset") == false, onClick = { offset += 8 }) { Text("下一页") }
            TextButton(onClick = { refresh++ }) { Text("刷新") }
        }
    }
    selected?.let { row ->
        var record by remember(role, row) { mutableStateOf<JSONObject?>(null) }
        var detailError by remember(role, row) { mutableStateOf("") }
        LaunchedEffect(role, row) {
            try { record = withContext(Dispatchers.IO) {
                requireNotNull(DreamArchive(context).detail(role, row.getString("kind"), row.getString("id"))) { "找不到这条梦境" }
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { detailError = failure.message ?: "读取梦境失败" }
        }
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(row.getJSONObject("content").getString("title")) },
            text = {
                val segments = record?.getJSONArray("segments")
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (detailError.isNotBlank()) item { Text(detailError, color = MaterialTheme.colorScheme.error) }
                    else if (segments == null) item { Text("正在读取…") }
                    else if (segments.length() == 0) item { Text("这次梦没有完成的片段。") }
                    else for (index in 0 until segments.length()) {
                        val segment = segments.getJSONObject(index)
                        val label = when (segment.getString("actor")) {
                            "subject" -> "主体"
                            "environment" -> "梦中场景"
                            else -> "梦境"
                        }
                        item { Text(label, style = MaterialTheme.typography.labelMedium) }
                        for (part in segment.getString("text").chunked(2500))
                            item { SelectionContainer { Text(part) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}
