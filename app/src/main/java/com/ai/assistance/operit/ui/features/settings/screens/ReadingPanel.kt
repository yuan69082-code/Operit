package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.companion.AssistantRoom
import com.ai.assistance.operit.core.companion.ReadingRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ReadingPanel(role: String, chat: String, name: String, revision: Long, initialSection: String = "书架", work: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val reading = remember(role) { ReadingRoom(context, role) }
    val room = remember(role) { AssistantRoom(context, role) }
    var tab by remember { mutableStateOf(initialSection) }
    var queued by remember { mutableStateOf(emptyList<JSONObject>()) }
    var history by remember { mutableStateOf(emptyList<JSONObject>()) }
    var diaries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var sources by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var diary by remember { mutableStateOf<JSONObject?>(null) }
    var editingDiary by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("rss") }; var hours by remember { mutableStateOf("24") }
    var add by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var archive by remember(role) { mutableStateOf(false) }
    var queueOffset by remember(role) { mutableIntStateOf(0) }
    var historyOffset by remember(role) { mutableIntStateOf(0) }
    var sourceOffset by remember(role) { mutableIntStateOf(0) }
    var retainedOffset by remember(role) { mutableIntStateOf(0) }
    var pages by remember(role) { mutableStateOf(emptyMap<String, JSONObject>()) }
    var retained by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(filter) { queueOffset = 0; historyOffset = 0; sourceOffset = 0; retainedOffset = 0 }
    LaunchedEffect(role, revision, reload, queueOffset, historyOffset, sourceOffset, retainedOffset, filter) {
        loading = true
        try {
            val records = withContext(Dispatchers.IO) {
                archive = reading.usesArchive
                if (archive) {
                    pages = mapOf("queue" to reading.entryPage("queue", queueOffset, filter), "history" to reading.entryPage("history", historyOffset, filter),
                        "sources" to reading.sourcePage(sourceOffset, filter), "retained" to reading.entryPage("retained", retainedOffset, filter))
                    fun items(key: String) = pages.getValue(key).getJSONArray("items").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
                    retained = items("retained")
                    listOf(items("queue"), items("history"), room.diaries(), items("sources"))
                } else listOf(reading.entries(), reading.entries(true), room.diaries(), reading.sources())
            }
            queued = records[0]; history = records[1]; diaries = records[2]; sources = records[3]
            loadError = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message.orEmpty() }
        finally { loading = false }
    }
    ReadingTheme {
      Column(Modifier.fillMaxWidth().background(BookPaper), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp)) {
            Text("${name}的书房", Modifier.weight(1f).padding(top = 8.dp), fontFamily = FontFamily.Serif, fontSize = 27.sp, color = BookInk)
            IconButton(onClick = { searching = !searching; if (!searching) filter = "" }) { Icon(Icons.Default.Search, "搜索整个书房") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "书架操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("放入阅读线索") }, onClick = { menu = false; tab = "书架"; add = true })
                    DropdownMenuItem(text = { Text("写日记") }, onClick = { menu = false; diary = null; editingDiary = true })
                }
            }
        }
        Text("想读的、读过的、收藏的，和留下的话", Modifier.padding(horizontal = 18.dp), fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("书架", "共读", "写作", "资料", "关注来源").forEach { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (loadError.isNotBlank()) {
            Text(loadError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 18.dp))
            TextButton(onClick = { reload++ }) { Text("重新载入") }
        }
        if (searching) OutlinedTextField(filter, { filter = it }, label = { Text("搜索书名、来源、日记标题和资料") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        when (if (searching) "搜索" else tab) {
          "共读" -> StudyPanel(role, name, "reading", revision)
          "写作" -> StudyPanel(role, name, "writing", revision)
          "资料" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { LibraryPanel(role, revision = revision) }
          "关注来源" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              ReadingSources(reading, chat, sources, sourceId, { sourceId = it }, title, { title = it }, address, { address = it }, kind, { kind = it }, hours, { hours = it }, work)
              if (archive) ReadingPager(pages["sources"], sourceOffset) { sourceOffset = it }
          }
          else -> {
            if (add) {
                var itemTitle by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var summary by remember { mutableStateOf("") }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(itemTitle, { itemTitle = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("原文链接") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(summary, { summary = it }, label = { Text("来源摘要，可留空") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { work { reading.add(itemTitle, url, summary); withContext(Dispatchers.Main) { add = false } } }) { Text("放入待读") }
                    TextButton(onClick = { add = false }) { Text("取消添加") }
                }
            }
            fun matching(items: List<JSONObject>) = if (archive && items !== diaries) items else items.filter { item -> listOf("title", "url", "date", "preview", "summary", "body", "note", "user_note").any { item.optString(it).contains(filter, true) } }
            BookshelfLayer("日记", matching(diaries), if (filter.isBlank()) "还没有写下日记。想记的时候，这里会留一本书。" else "没有找到相符的日记", diary = true) { item ->
                work { val record = room.diary(item.getString("id")); withContext(Dispatchers.Main) { diary = record } }
            }
            BookshelfLayer("待读", matching(queued), if (filter.isBlank()) "想读的线索放在这一层。可以添加链接，或让助手在探索时收集。" else "没有找到相符的待读") { item ->
                work { val record = reading.entry(item.getString("id")); withContext(Dispatchers.Main) { selected = record } }
            }
            if (archive) ReadingPager(pages["queue"], queueOffset) { queueOffset = it }
            BookshelfLayer("已读", matching(history), if (filter.isBlank()) "读过的材料与保存过批注的书，会留在这一层。" else "没有找到相符的已读") { item ->
                work { val record = reading.entry(item.getString("id")); withContext(Dispatchers.Main) { selected = record } }
            }
            if (archive) {
                ReadingPager(pages["history"], historyOffset) { historyOffset = it }
                BookshelfLayer("已移出待读的线索", retained, "没有到期或跳过的线索；原文会保留，不自动标成已读。") { item ->
                    work { val record = reading.entry(item.getString("id")); withContext(Dispatchers.Main) { selected = record } }
                }
                ReadingPager(pages["retained"], retainedOffset) { retainedOffset = it }
            }
            if (searching) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryPanel(role, query = filter, revision = revision)
            }
            Text("点书脊打开 · 长书名也可以搜索", Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
    selected?.let { item -> key(role, item.getString("id")) {
        val level = if (item.has("body")) "可取得的部分正文" else "来源摘要"
        val seen = when (item.optString("delivered")) { "summary" -> "助手读过摘要"; "partial" -> "助手读过部分正文"; else -> "尚未标记助手已读" }
        ReadingBookDialog(item.getString("title"), "$level · $seen\n${item.getString("url")}", { selected = null }) { size ->
            ReadingArticle(item, name, size,
                fetch = { work { reading.fetch(item.getString("id"), false, chat); val record = reading.entry(item.getString("id")); withContext(Dispatchers.Main) { selected = record } } },
                source = { work { withContext(Dispatchers.Main) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.getString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } },
                saveNote = { note -> work { val record = reading.note(item.getString("id"), note, false); withContext(Dispatchers.Main) { selected = record } } },
                skip = { work { reading.skip(item.getString("id")); withContext(Dispatchers.Main) { selected = null } } })
            if (archive) ReadingVersions(reading, item.getString("id"), "entry")
        }
    } }
    if (editingDiary) DiaryEditor(room, chat, diary, work, { editingDiary = false }, { saved -> diary = saved; editingDiary = false })
    else diary?.let { item -> key(role, item.getString("id")) {
        ReadingBookDialog(item.getString("title"), "${item.getString("date")} · ${if (item.getString("author") == "user") "你写下的记录" else name + "的日记"}", { diary = null }) { size ->
            BookParagraph(item.getString("text"), size)
            Text(if (item.getString("author") == "user") "你" else name, fontFamily = ReadingHandwriting, fontSize = 26.sp)
            HorizontalDivider()
            Text("第${item.getLong("version")}版 · ${bookDate(item.getLong("updated"))}", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { editingDiary = true }) { Text("编辑日记 / 查看旧稿") }
        }
    } }
}

@Composable
private fun ReadingSources(reading: ReadingRoom, chat: String, sources: List<JSONObject>, sourceId: String?, setId: (String?) -> Unit,
    title: String, setTitle: (String) -> Unit, address: String, setAddress: (String) -> Unit,
    kind: String, setKind: (String) -> Unit, hours: String, setHours: (String) -> Unit, work: (suspend () -> Unit) -> Unit) {
        Text("RSS 与话题按设定间隔标为待检查，在已开启的探索轮次里查阅；不会每来一条消息就唤醒或通知你。也可以手动刷新 RSS。")
        sources.forEach { item -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(item.getString("title"), style = MaterialTheme.typography.titleMedium)
            Text("${if (item.getString("kind") == "rss") "RSS" else "公开话题"} · 每${item.getInt("hours")}小时")
            Text(item.getString("address"), maxLines = 3)
            item.optString("parent").takeIf { it.isNotBlank() }?.let { p -> sources.firstOrNull { it.getString("id") == p }?.let { Text("延伸自：${it.getString("title")}", style = MaterialTheme.typography.bodySmall) } }
            item.optString("progress").takeIf { it.isNotBlank() }?.let { Text("进度：$it", style = MaterialTheme.typography.bodySmall, maxLines = 6) }
            ReadingVersions(reading, item.getString("id"), "source")
            RoomSwitch("关注", item.optBoolean("enabled")) { enabled -> work { reading.saveSource(item.getString("id"), item.getString("title"), item.getString("kind"), item.getString("address"), item.getInt("hours"), enabled) } }
            Row {
                if (item.getString("kind") == "rss") TextButton(enabled = item.optBoolean("enabled"), onClick = { work { reading.refresh(item.getString("id"), false, chat) } }) { Text("现在刷新") }
                TextButton(onClick = { setId(item.getString("id")); setTitle(item.getString("title")); setAddress(item.getString("address")); setKind(item.getString("kind")); setHours(item.getInt("hours").toString()) }) { Text("编辑") }
            }
        } } }
        HorizontalDivider()
        Text(if (sourceId == null) "添加一处来源" else "修改来源")
        Row { FilterChip(kind == "rss", { setKind("rss") }, label = { Text("RSS / Atom") }); FilterChip(kind == "topic", { setKind("topic") }, label = { Text("持续关注的话题") }) }
        OutlinedTextField(title, setTitle, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, setAddress, label = { Text(if (kind == "rss") "订阅地址" else "公开搜索词，不填写私聊原文") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hours, setHours, label = { Text("检查间隔，1–168小时") })
        Button(onClick = { work { reading.saveSource(sourceId, title, kind, address, hours.toInt(), true); withContext(Dispatchers.Main) { setId(null); setTitle(""); setAddress("") } } }) { Text("保存关注") }

}

@Composable
private fun ReadingPager(page: JSONObject?, offset: Int, change: (Int) -> Unit) {
    if (page == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("共 ${page.getLong("total")} 条 · 第 ${offset / 20 + 1} 页", Modifier.weight(1f))
        TextButton(enabled = offset > 0, onClick = { change(maxOf(0, offset - 20)) }) { Text("上一页") }
        TextButton(enabled = !page.isNull("next_offset"), onClick = { change(page.getInt("next_offset")) }) { Text("下一页") }
    }
}

@Composable
private fun ReadingVersions(reading: ReadingRoom, id: String, kind: String) {
    var expanded by remember(id) { mutableStateOf(false) }
    var offset by remember(id) { mutableIntStateOf(0) }
    var page by remember(id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, expanded, offset) {
        if (expanded) try { page = withContext(Dispatchers.IO) { reading.versions(id, kind, offset) }; error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起变化记录" else "查看进度与随记的旧版本") }
    if (expanded) {
        error?.let { Text("读取失败：$it", color = MaterialTheme.colorScheme.error) }
        page?.let { data ->
            data.optString("notice").takeIf { it.isNotBlank() }?.let { Text(it) }
            val rows = data.getJSONArray("history")
            for (i in 0 until rows.length()) {
                val version = rows.getJSONObject(i); val record = version.getJSONObject("record")
                Text("第 ${version.getLong("revision")} 版 · ${bookDate(version.getLong("recorded_at"))}" + if (version.optBoolean("legacy_import")) " · 旧内容承接，非历史形成时间" else "")
                listOf("progress" to "学习进度", "note" to "助手随记", "user_note" to "你的批注").forEach { (field, label) ->
                    if (record.has(field)) Text("$label：${record.optString(field)}")
                }
            }
            Row {
                TextButton(enabled = offset > 0, onClick = { offset = maxOf(0, offset - 5) }) { Text("较新版本") }
                TextButton(enabled = !data.isNull("next_history_offset"), onClick = { offset = data.getInt("next_history_offset") }) { Text("更早版本") }
            }
        }
    }
}
