package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.ai.assistance.operit.core.companion.ActivityNotes
import com.ai.assistance.operit.core.companion.PrivateStateView
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun privateTime(at: Long): String = if (at > 0) DateFormat.getDateTimeInstance().format(Date(at)) else "时间未留存"
internal fun jsonRows(rows: JSONArray?) = if (rows == null) emptyList() else (0 until rows.length()).map { rows.getJSONObject(it) }

/** User-visible notes only. Old private companion_note and complete private dreams are not projected. */
@Composable
internal fun ActivityNotesPanel(role: String, wakingOnly: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var offset by remember(role, wakingOnly) { mutableIntStateOf(0) }
    var revision by remember(role, wakingOnly) { mutableIntStateOf(0) }
    var rows by remember(role, wakingOnly) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var next by remember(role, wakingOnly) { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(role, wakingOnly, offset, revision) {
        busy = true; error = null
        try {
            val page = withContext(Dispatchers.IO) { PrivateStateView.project(ActivityNotes.page(context, role, if (wakingOnly) "waking_note" else "activity", null, offset)) }
            rows = jsonRows(page.getJSONArray("items"))
            next = page.opt("next_offset").let { if (it is Number) it.toInt() else null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLogger.e("ActivityNotesPanel", "随记读取失败", failure); error = "随记暂时未能读取，原记录没有因此删除。" }
        finally { busy = false }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (wakingOnly) "梦醒随记" else "这段时间", style = MaterialTheme.typography.titleLarge)
        TextButton(enabled = !busy, onClick = { revision++ }) { Text("刷新") }
    }
    Text(if (wakingOnly) "这里是醒来时实际留下、愿意让你看到的话。一个画面、一点感觉，或没有梦，都可以。" else
        "留下的想法和实际做过的事。没有发消息，也可以有自己的随记。", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!busy && error == null && rows.isEmpty()) Text(if (wakingOnly) "尚未留存可见的梦醒随记。旧梦和旧私密随记没有自动公开。" else "这一页没有活动记录。", style = MaterialTheme.typography.bodyMedium)
    if (error == null) rows.forEach { row -> key(row.getString("id"), row.getInt("version")) {
        if (wakingOnly) OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(privateTime(row.optLong("at")), style = MaterialTheme.typography.labelMedium)
                if (row.optBoolean("withdrawn")) Text("这段随记在生成修订时已撤回。原文保留在变更记录中。")
                else SelectionContainer { Text(row.getJSONObject("content").getString("text")) }
                Text("梦中的情节不是现实经历；查看随记也不代表它已在聊天中分享。", style = MaterialTheme.typography.bodySmall)
            }
        } else ActivityNoteCard(role, row)
    } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = !busy && offset > 0, onClick = { offset = (offset - 8).coerceAtLeast(0) }) { Text("上一页") }
        TextButton(enabled = !busy && next != null, onClick = { next?.let { offset = it } }) { Text("下一页") }
    }
}

@Composable
private fun ActivityNoteCard(role: String, head: JSONObject) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    var parts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var next by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, offset) {
        if (!expanded) return@LaunchedEffect
        busy = true
        try {
            val page = withContext(Dispatchers.IO) { PrivateStateView.project(ActivityNotes.page(context, role, "activity_part", head.getString("id"), offset)) }
            parts = jsonRows(page.getJSONArray("items")).asReversed()
            next = page.opt("next_offset").let { if (it is Number) it.toInt() else null }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLogger.e("ActivityNoteCard", "活动片段读取失败", failure); error = "片段读取失败，请重新展开重试。" }
        finally { busy = false }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(privateTime(head.optLong("at")), style = MaterialTheme.typography.labelMedium)
            val status = when (head.optString("status")) {
                "quiet" -> "安静 · 未发消息"
                "message_saved" -> "聊天消息已保存"
                "not_started", "received" -> "已接收 · 尚无开始记录"
                "interrupted" -> "本次活动中断"
                "cancelled" -> "本次活动已取消"
                "failed" -> "本次活动未正常完成"
                "finished" -> if (head.optInt("message_count") == 0) "安静 · 未发消息" else "本次活动已结束"
                "started" -> "已开始 · 尚无结束记录"
                else -> "结束情况未留存"
            }
            if (!expanded) {
                if (head.optString("note_preview").isNotBlank()) Text(head.getString("note_preview"), maxLines = 5, overflow = TextOverflow.Ellipsis)
                else Text("未留存可见随记。展开可查看实际留下的片段与操作。", style = MaterialTheme.typography.bodyMedium)
            }
            Text(status + if (head.optInt("message_count") > 0) " · 已保存 ${head.getInt("message_count")} 条聊天表达，已读情况未知" else "", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开当时的记录") }
            if (expanded) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                parts.forEach { row ->
                    if (row.optBoolean("withdrawn")) Text("一段输出已由生成修订撤回", style = MaterialTheme.typography.labelSmall)
                    else {
                        val c = row.getJSONObject("content")
                        when (row.getString("kind")) {
                            "note", "waking" -> SelectionContainer { Text(c.getString("text")) }
                            "private_choice" -> Text("这一次，他明确选择不公开随记。", style = MaterialTheme.typography.bodySmall)
                            "report_failure" -> Text("本次内部状态回报未能采用，原状态保留；已保存的表达和操作不会重做。", style = MaterialTheme.typography.bodySmall)
                            "message" -> {
                                Text("已保存的聊天表达 · ${privateTime(c.getLong("timestamp"))}", style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(c.getString("text")) }
                            }
                            "schedule" -> {
                                Text(if (c.optBoolean("paused")) "已暂停后续安排" else "已保存的安排 · ${privateTime(c.optLong("at"))}",
                                    style = MaterialTheme.typography.labelMedium)
                                if (!c.optBoolean("paused")) Text(c.optString("purpose"), style = MaterialTheme.typography.bodyMedium)
                            }
                            "operation" -> {
                                val outcome = when (c.optString("status")) { "started" -> "开始尝试"; "completed" -> "已完成"; "failed" -> "未成功"; "cancelled" -> "已取消"; "partial" -> "部分完成"; else -> "结果尚未确认" }
                                Text("${c.getString("tool")} · $outcome", style = MaterialTheme.typography.bodySmall)
                                var technical by remember(row.getString("id")) { mutableStateOf(false) }
                                TextButton(onClick = { technical = !technical }) { Text(if (technical) "收起回执" else "实际回执") }
                                if (technical) SelectionContainer { Text(c.toString(2), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = offset > 0 && !busy, onClick = { offset = (offset - 8).coerceAtLeast(0) }) { Text("较新片段") }
                    TextButton(enabled = next != null && !busy, onClick = { next?.let { offset = it } }) { Text("更早片段") }
                }
            }
        }
    }
}
