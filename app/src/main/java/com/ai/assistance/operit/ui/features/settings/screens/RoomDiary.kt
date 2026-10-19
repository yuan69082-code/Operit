package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.companion.AssistantRoom
import com.ai.assistance.operit.core.companion.CompanionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RoomDiary(room: AssistantRoom, chat: String, revision: Long, work: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) { entries = withContext(Dispatchers.IO) { room.diaries() } }
    LaunchedEffect(filter) { page = 0 }
    Text("留下当时想记住的", style = MaterialTheme.typography.titleLarge)
    Text("日记属于这位助手。助手原作、你的修改和旧稿各有记录；没有写下的日子留白。")
    OutlinedTextField(filter, { filter = it }, label = { Text("按日期、标题或片段查找") }, modifier = Modifier.fillMaxWidth())
    Row { TextButton(onClick = { filter = LocalDate.now().toString() }) { Text("今天") }
        TextButton(onClick = { filter = LocalDate.now().minusDays(1).toString() }) { Text("昨天") }
        TextButton(onClick = { filter = "" }) { Text("全部") }
        Button(onClick = { selected = null; editing = true }) { Text("写一篇") } }
    val found = entries.filter { item -> listOf("date", "title", "preview").any { item.optString(it).contains(filter, true) } }
    val current = page.coerceAtMost((found.size - 1).coerceAtLeast(0) / 20)
    if (found.isEmpty()) Text("这段时间还没有记录。")
    found.drop(current * 20).take(20).forEach { item ->
        OutlinedCard(onClick = { work { selected = room.diary(item.getString("id")); editing = true } }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.getString("date"), color = MaterialTheme.colorScheme.primary)
                Text(item.getString("title"), style = MaterialTheme.typography.titleMedium)
                Text(item.optString("preview"), maxLines = 4)
                Text(if (item.optString("author") == "user") "你写下的记录" else "助手的日记", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Row { TextButton(enabled = current > 0, onClick = { page = current - 1 }) { Text("上一页") }
        Text("${found.size} 篇", Modifier.padding(12.dp)); TextButton(enabled = (current + 1) * 20 < found.size, onClick = { page = current + 1 }) { Text("下一页") } }
    if (editing) DiaryEditor(room, chat, selected, work, { editing = false }, { editing = false })
}

@Composable
internal fun DiaryEditor(room: AssistantRoom, chat: String, selected: JSONObject?, work: (suspend () -> Unit) -> Unit, dismiss: () -> Unit, onSaved: (JSONObject) -> Unit) {
    val context = LocalContext.current
    key(room.role, selected?.optString("id")) {
        var date by remember { mutableStateOf(selected?.getString("date") ?: LocalDate.now().toString()) }
        var title by remember { mutableStateOf(selected?.getString("title").orEmpty()) }
        var text by remember { mutableStateOf(selected?.getString("text").orEmpty()) }
        var old by remember { mutableStateOf<JSONObject?>(null) }
        var version by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("日记", style = MaterialTheme.typography.headlineSmall); TextButton(onClick = { dismiss() }) { Text("关闭") } }
                selected?.let { entry ->
                    Text("原作者：${if (entry.getString("author") == "user") "你" else "这位助手"} · 第${entry.getLong("version")}版")
                    Text("更新于 ${DateFormat.getDateTimeInstance().format(Date(entry.getLong("updated")))} · ${if (entry.getString("edited_by") == "user") "你编辑" else "助手编辑"}")
                    Text("来源会话：${entry.getString("source_chat")}", style = MaterialTheme.typography.labelSmall)
                    if (entry.getLong("version") > 1) {
                        OutlinedTextField(version, { version = it }, label = { Text("回看旧稿版本（1–${entry.getLong("version") - 1}）") })
                        TextButton(onClick = { work {
                            val number = version.toLong(); require(number in 1 until entry.getLong("version")) { "请输入已有版本号" }
                            old = CompanionStore(context).read("diary:${room.role}:${entry.getString("id")}:v$number")
                        } }) { Text("查看原稿") }
                    }
                }
                OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("正文") }, minLines = 12, modifier = Modifier.fillMaxWidth())
                Button(onClick = { work { val saved = room.saveDiary(selected?.getString("id"), date, title, text, chat, false, selected?.getLong("version")); withContext(Dispatchers.Main) { onSaved(saved) } } }) { Text("保存，保留旧稿") }
                old?.let { Text("第${it.optLong("version")}版 · ${it.optString("title")}", style = MaterialTheme.typography.titleMedium); Text(it.optString("text")) }
            } }
        }
    }
}
