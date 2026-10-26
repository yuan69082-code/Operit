package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.ai.assistance.operit.core.companion.CompanionRuntime
import com.ai.assistance.operit.core.companion.CompanionStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** The bookroom uses the existing library records. Opening it never copies or rekeys data. */
@Composable
internal fun LibraryPanel(role: String, query: String? = null, revision: Long = 0) {
    val context = LocalContext.current
    val store = remember { CompanionStore(context) }
    val scope = rememberCoroutineScope()
    var entries by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var categories by remember(role) { mutableStateOf(emptyList<String>()) }
    var search by remember(role) { mutableStateOf("") }
    var folder by remember(role) { mutableStateOf<String?>(null) }
    var type by remember(role) { mutableStateOf<String?>(null) }
    var page by remember(role) { mutableIntStateOf(0) }
    var loaded by remember(role) { mutableStateOf(false) }
    var error by remember(role) { mutableStateOf("") }
    var busy by remember(role) { mutableStateOf(false) }
    var editing by remember(role) { mutableStateOf(false) }
    var id by remember(role) { mutableStateOf<String?>(null) }
    var title by remember(role) { mutableStateOf("") }
    var category by remember(role) { mutableStateOf("") }
    var kind by remember(role) { mutableStateOf("knowledge") }
    var content by remember(role) { mutableStateOf("") }
    var source by remember(role) { mutableStateOf<String?>(null) }
    var dirty by remember(role) { mutableStateOf(false) }
    var discard by remember(role) { mutableStateOf(false) }
    var delete by remember(role) { mutableStateOf(false) }
    val identity = "role:$role"
    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) { store.entries(identity) to store.categories(identity) }
        entries = result.first; categories = result.second; loaded = true
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                error = ""
                action()
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(role, revision) { perform {} }
    LaunchedEffect(query, search, folder, type) { page = 0 }
    fun edit(item: JSONObject?) {
        id = item?.getString("id")
        title = item?.getString("title").orEmpty()
        category = if (item == null) folder.orEmpty() else item.getString("category")
        kind = if (item == null) "knowledge" else item.getString("kind")
        content = item?.optString("content").orEmpty()
        source = null; dirty = false; editing = true; error = ""
    }
    fun close() { if (!busy) { if (dirty) discard = true else editing = false } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { source = uri.toString(); kind = "file"; dirty = true }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column { Text("收藏资料", style = MaterialTheme.typography.titleMedium)
            Text(if (loaded) "${entries.size} 份资料 · ${categories.size} 个分类" else "正在读取", style = MaterialTheme.typography.bodySmall) }
        Row {
            IconButton(enabled = !busy, onClick = { perform {} }) { Icon(Icons.Default.Refresh, "刷新资料") }
            FilledTonalIconButton(enabled = !busy, onClick = { edit(null) }) { Icon(Icons.Default.Add, "新建资料") }
        }
    }
    if (query == null) {
        OutlinedTextField(search, { search = it }, label = { Text("搜索资料") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(folder == null, { folder = null }, label = { Text("全部分类") })
            categories.forEach { item -> FilterChip(folder == item, { folder = item }, label = { Text(item) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "全部类型", "knowledge" to "知识", "skill" to "能力笔记", "file" to "文件").forEach { (value, label) ->
                FilterChip(type == value, { type = value }, label = { Text(label) })
            }
        }
    }
    // Global bookroom search must not inherit filters from the library tab.
    val text = query ?: search
    val found = entries.filter { item ->
        (query != null || folder == null || item.optString("category") == folder) &&
        (query != null || type == null || item.optString("kind") == type) &&
        listOf("title", "category", "content", "tags").any { item.optString(it).contains(text, true) }
    }.sortedByDescending { it.optLong("updated") }
    val currentPage = page.coerceAtMost((found.size - 1).coerceAtLeast(0) / 20)
    if (loaded && found.isEmpty()) Text(if (text.isBlank()) "还没有收藏资料。可以手动添加，也可以由助手自己整理。" else "没有相符的资料。")
    found.drop(currentPage * 20).take(20).forEach { item ->
        val icon = when (item.getString("kind")) { "file" -> Icons.Default.Description; "skill" -> Icons.Default.Build; else -> Icons.Default.BookmarkBorder }
        SpaceLinkCard(item.getString("title"), item.getString("category") + " · " + item.optString("content").take(100),
            icon, onClick = { edit(item) })
    }
    if (found.size > 20) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(enabled = currentPage > 0, onClick = { page = currentPage - 1 }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "上一页") }
        Text("第 ${currentPage + 1} 页 · 共 ${found.size} 项", Modifier.padding(top = 12.dp))
        IconButton(enabled = (currentPage + 1) * 20 < found.size, onClick = { page = currentPage + 1 }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "下一页") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!editing && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (editing) Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                SpaceHeader(if (id == null) "新建资料" else "资料详情", { close() }, enabled = !busy)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title, { title = it; dirty = true }, label = { Text("标题") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(category, { category = it; dirty = true }, label = { Text("分类，可用 / 分级") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("knowledge" to "知识", "skill" to "能力笔记", "file" to "文件").forEach { (value, label) ->
                            FilterChip(kind == value, { kind = value; dirty = true }, enabled = !busy, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(content, { content = it; dirty = true }, label = { Text("正文或文件说明") }, minLines = 5,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(if (source == null) "导入文件副本" else "已选择文件，待保存")
                    }
                    val savedFile = entries.firstOrNull { it.getString("id") == id }?.optString("file").orEmpty()
                    if (savedFile.isNotBlank()) OutlinedButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(savedFile))
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (failure: Exception) { error = "无法打开文件：${failure.message}" }
                    }) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("打开已保存文件") }
                    Text("资料供阅读和检索；保存能力笔记不会执行代码。", style = MaterialTheme.typography.bodySmall)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(enabled = id != null && !busy, onClick = { delete = true }) { Icon(Icons.Default.DeleteOutline, "删除资料") }
                    OutlinedButton(enabled = category.isNotBlank() && !busy, onClick = { perform {
                        withContext(Dispatchers.IO) { store.createCategory(identity, category) }
                    } }) { Text("创建分类") }
                    Button(enabled = !busy, onClick = {
                        // Capture the editor values before suspension; role is fixed for this editor.
                        val saveId = id; val saveTitle = title; val saveCategory = category; val saveKind = kind; val saveContent = content; val saveSource = source
                        perform {
                            withContext(Dispatchers.IO) { store.saveEntry(identity, saveId, saveTitle, saveCategory, saveKind, saveContent, saveSource) }
                            dirty = false; editing = false; source = null
                            CompanionRuntime.publishEvent(context, "library", "用户更新了书房资料，可自行决定是否需要联系用户。")
                        }
                    }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("保存") }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { discard = false; editing = false; dirty = false }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这份资料？") },
        text = { Text("会删除资料及其保存的文件副本，原始附件不受影响。") },
        confirmButton = { TextButton(onClick = {
            val deleteId = checkNotNull(id); delete = false
            perform { withContext(Dispatchers.IO) { store.deleteEntry(identity, deleteId) }; editing = false; dirty = false }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}
