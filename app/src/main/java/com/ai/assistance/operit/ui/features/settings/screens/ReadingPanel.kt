package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.ReadingRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ReadingPanel(role: String, chat: String, revision: Long, work: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val reading = remember(role) { ReadingRoom(context, role) }
    var tab by remember { mutableStateOf("待读") }
    var items by remember { mutableStateOf(emptyList<JSONObject>()) }
    var sources by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var filter by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("rss") }; var hours by remember { mutableStateOf("24") }
    var add by remember { mutableStateOf(false) }
    LaunchedEffect(revision, tab) { withContext(Dispatchers.IO) { items = reading.entries(tab == "读过的"); sources = reading.sources() } }
    LaunchedEffect(filter, tab) { page = 0 }
    Text("阅读角", style = MaterialTheme.typography.titleLarge)
    Text("新发现先放在这里。来源、读到的内容和自己的随记分别保存，是否继续阅读或分享由助手决定。")
    Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("待读", "读过的", "关注来源", "自主探索").forEach { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) } }
    if (tab == "自主探索") { ExplorationPanel(role); return }
    if (tab == "关注来源") {
        Text("RSS 与话题按设定间隔标为待检查，在已开启的探索轮次里查阅；不会每来一条消息就唤醒或通知你。也可以手动刷新 RSS。")
        sources.forEach { item -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(item.getString("title"), style = MaterialTheme.typography.titleMedium)
            Text("${if (item.getString("kind") == "rss") "RSS" else "公开话题"} · 每${item.getInt("hours")}小时")
            Text(item.getString("address"), maxLines = 3)
            item.optString("parent").takeIf { it.isNotBlank() }?.let { p -> sources.firstOrNull { it.getString("id") == p }?.let { Text("延伸自：${it.getString("title")}", style = MaterialTheme.typography.bodySmall) } }
            item.optString("progress").takeIf { it.isNotBlank() }?.let { Text("进度：$it", style = MaterialTheme.typography.bodySmall, maxLines = 6) }
            RoomSwitch("关注", item.optBoolean("enabled")) { enabled -> work { reading.saveSource(item.getString("id"), item.getString("title"), item.getString("kind"), item.getString("address"), item.getInt("hours"), enabled) } }
            Row {
                if (item.getString("kind") == "rss") TextButton(enabled = item.optBoolean("enabled"), onClick = { work { reading.refresh(item.getString("id"), false, chat) } }) { Text("现在刷新") }
                TextButton(onClick = { sourceId = item.getString("id"); title = item.getString("title"); address = item.getString("address"); kind = item.getString("kind"); hours = item.getInt("hours").toString() }) { Text("编辑") }
            }
        } } }
        HorizontalDivider()
        Text(if (sourceId == null) "添加一处来源" else "修改来源")
        Row { FilterChip(kind == "rss", { kind = "rss" }, label = { Text("RSS / Atom") }); FilterChip(kind == "topic", { kind = "topic" }, label = { Text("持续关注的话题") }) }
        OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it }, label = { Text(if (kind == "rss") "订阅地址" else "公开搜索词，不填写私聊原文") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hours, { hours = it }, label = { Text("检查间隔，1–168小时") })
        Button(onClick = { work { reading.saveSource(sourceId, title, kind, address, hours.toInt(), true); sourceId = null; title = ""; address = "" } }) { Text("保存关注") }
        return
    }
    OutlinedTextField(filter, { filter = it }, label = { Text("查找标题或来源") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { add = !add }) { Text(if (add) "取消添加" else "放入一条阅读线索") }
    if (add) {
        var itemTitle by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var summary by remember { mutableStateOf("") }
        OutlinedTextField(itemTitle, { itemTitle = it }, label = { Text("标题") })
        OutlinedTextField(url, { url = it }, label = { Text("原文链接") })
        OutlinedTextField(summary, { summary = it }, label = { Text("来源摘要，可留空") })
        Button(onClick = { work { reading.add(itemTitle, url, summary); add = false } }) { Text("放入待读") }
    }
    val found = items.filter { it.optString("title").contains(filter, true) || it.optString("url").contains(filter, true) }
    val current = page.coerceAtMost((found.size - 1).coerceAtLeast(0) / 20)
    if (found.isEmpty()) Text(if (tab == "待读") "这里暂时没有待读线索。可以添加链接，或开启自主探索后让助手收集。" else "助手实际打开过的材料和随记会留在这里。")
    found.drop(current * 20).take(20).forEach { item -> OutlinedCard(onClick = { work { selected = reading.entry(item.getString("id")) } }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.getString("title"), style = MaterialTheme.typography.titleMedium)
            Text(Uri.parse(item.getString("url")).host.orEmpty(), style = MaterialTheme.typography.labelMedium)
            Text(when (item.optString("delivered")) { "summary" -> "助手读过来源摘要"; "partial" -> "助手读过可取得的部分正文"; else -> "待读 · 尚未标记助手已读" }, style = MaterialTheme.typography.bodySmall)
        }
    } }
    Row { TextButton(enabled = current > 0, onClick = { page = current - 1 }) { Text("上一页") }; Text("${found.size} 条", Modifier.padding(12.dp)); TextButton(enabled = (current + 1) * 20 < found.size, onClick = { page = current + 1 }) { Text("下一页") } }
    selected?.let { item -> key(item.getString("id")) {
        var note by remember { mutableStateOf(item.optString("user_note")) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.getString("title")) }, text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.getString("url"), style = MaterialTheme.typography.labelSmall)
                Text(if (item.has("body")) "可取得的部分正文" else "来源摘要", style = MaterialTheme.typography.titleMedium)
                Text(if (item.has("body")) item.getString("body") else item.optString("summary"))
                TextButton(onClick = { work { reading.fetch(item.getString("id"), false, chat); selected = reading.entry(item.getString("id")) } }) { Text("读取网页可用正文") }
                TextButton(onClick = { work { withContext(Dispatchers.Main) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.getString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }) { Text("到来源继续阅读") }
                HorizontalDivider()
                Text("助手随记", style = MaterialTheme.typography.titleMedium)
                Text(item.optString("note").ifBlank { "还没有留下随记" })
                OutlinedTextField(note, { note = it }, label = { Text("你的批注") }, minLines = 3)
                TextButton(onClick = { work { reading.note(item.getString("id"), note, false); selected = reading.entry(item.getString("id")) } }) { Text("保存批注") }
                if (item.optString("state") == "queued") TextButton(onClick = { work { reading.skip(item.getString("id")); selected = null } }) { Text("从待读移开") }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    } }
}
