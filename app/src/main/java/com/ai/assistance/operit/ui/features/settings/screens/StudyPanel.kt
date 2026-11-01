package com.ai.assistance.operit.ui.features.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.companion.StudyImport
import com.ai.assistance.operit.core.companion.StudyStore
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StudyPanel(role: String, name: String, section: String, revision: Long) {
    val context = LocalContext.current
    val store = remember(role) { StudyStore(context, role) }
    val scope = rememberCoroutineScope()
    var category by rememberSaveable(role, section) { mutableStateOf("daily") }
    var books by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var selectedId by rememberSaveable(role, section) { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val run: (suspend () -> Unit) -> Unit = { action ->
        if (!busy) {
            busy = true; error = ""
            scope.launch { try { withContext(Dispatchers.IO) { action() }
                    com.ai.assistance.operit.api.chat.AIForegroundService.refreshBackgroundKeepAlive(context)
                    reload++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppLogger.e("StudyPanel", "书房操作失败", failure); error = failure.message.orEmpty() }
                finally { busy = false } }
        }
    }
    LaunchedEffect(role, revision, reload) {
        try { books = withContext(Dispatchers.IO) { store.books() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLogger.e("StudyPanel", "书架读取失败", failure); error = failure.message.orEmpty() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(category == "daily", { category = "daily" }, label = { Text("日常") })
            FilterChip(category == "sex", { category = "sex" }, label = { Text("Sex") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { create = true }, enabled = !busy) { Text(if (section == "reading") "导入共读" else "新建作品") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        val items = books.filter { it.getString("section") == section && it.getString("category") == category }
        val unread = items.sumOf { it.optInt("unread") }
        if (unread > 0) Text("有 $unread 条新的作品更新或留言", Modifier.padding(horizontal = 16.dp))
        BookshelfLayer(if (section == "reading") "一起读" else "一起写", items, if (section == "reading") "把下载的小说、文章放上书架，一起读，一起留下话。" else "长篇可以慢慢添章节，短篇也有自己的位置。") {
            selectedId = it.getString("id")
        }
        items.filter { it.optInt("unread") > 0 }.forEach { item ->
            TextButton(onClick = { selectedId = item.getString("id") }) { Text("${item.getString("title")} · ${item.optInt("unread")} 条未读") }
        }
    }
    if (create) StudyCreateDialog(store, role, section, category, busy, error, run, { create = false }) { id -> create = false; selectedId = id }
    val selected = books.firstOrNull { it.getString("id") == selectedId }
    if (selected != null) key(role, selected.getString("id")) {
        StudyBook(store, selected, name, busy, error, run) { selectedId = null }
    }
}

@Composable
private fun StudyCreateDialog(store: StudyStore, role: String, section: String, category: String, busy: Boolean, error: String,
                              run: (suspend () -> Unit) -> Unit, dismiss: () -> Unit, created: (String) -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("txt") }
    var encoding by rememberSaveable { mutableStateOf("UTF-8") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) run {
        val book = StudyImport.file(context, role, title, category, uri, format == "epub", encoding)
        store.userEvent(book.getString("id"), "用户导入资料并邀请共读")
        withContext(Dispatchers.Main) { created(book.getString("id")) }
    } }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (section == "reading") "放入共读书架" else "新建作品") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("书名／文章标题") }, enabled = !busy)
            Text(if (category == "sex") "分类：Sex" else "分类：日常")
            if (section == "reading") {
                Row { FilterChip(format == "txt", { format = "txt" }, label = { Text("TXT") }); FilterChip(format == "epub", { format = "epub" }, label = { Text("EPUB") }) }
                if (format == "txt") Row {
                    listOf("UTF-8", "GB18030").forEach { value -> FilterChip(encoding == value, { encoding = value }, label = { Text(value) }) }
                }
                Text("TXT 可选择编码，也支持带编码标记的 UTF-16；EPUB 按目录导入文字。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = title.isNotBlank() && !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("选择已下载的文件") }
                OutlinedTextField(url, { url = it }, label = { Text("或粘贴公开文章链接") }, enabled = !busy)
                Text("网页只导入当前页面；多章小说请导入完整文件。", style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } },
        confirmButton = { Button(enabled = title.isNotBlank() && !busy && (section == "writing" || url.isNotBlank()), onClick = { run {
            val book = if (section == "reading") StudyImport.url(context, role, title, category, url)
                else store.create(title, section, category, "user")
            if (section == "reading") store.userEvent(book.getString("id"), "用户导入资料并邀请共读")
            withContext(Dispatchers.Main) { created(book.getString("id")) }
        } }) { Text(if (section == "reading") "导入链接并共读" else "创建") } })
}

private data class StudyAnchor(val version: String?, val start: Int = 0, val end: Int = 0, val quote: String = "")

@Composable
private fun StudyBook(store: StudyStore, book: JSONObject, name: String, busy: Boolean, error: String,
                      run: (suspend () -> Unit) -> Unit, dismiss: () -> Unit) {
    val id = book.getString("id")
    val chapters = studyObjects(book.getJSONArray("chapters"))
    var chapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var oldVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val brief = chapters.firstOrNull { it.getString("id") == chapterId } ?: chapters.firstOrNull()
    val version = oldVersion ?: brief?.getString("version")
    var chapter by remember { mutableStateOf<JSONObject?>(null) }
    var loadingError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var newChapter by remember { mutableStateOf(false) }
    var annotation by remember { mutableStateOf<StudyAnchor?>(null) }
    val notes = studyObjects(book.getJSONArray("notes"))
    LaunchedEffect(id, version) {
        chapter = null
        if (version != null) try { chapter = withContext(Dispatchers.IO) { store.chapter(id, version) }; loadingError = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLogger.e("StudyBook", "章节读取失败", failure); loadingError = failure.message.orEmpty() }
    }
    ReadingBookDialog(book.getString("title"), "${if (book.getString("section") == "reading") "共读" else "写作"} · ${if (book.getString("category") == "sex") "Sex" else "日常"}", dismiss) { size ->
        if (book.optInt("unread") > 0) TextButton(enabled = !busy, onClick = { run { store.acknowledge(id) } }) { Text("已看过新留言（${book.optInt("unread")}）") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (loadingError.isNotBlank()) Text(loadingError, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val failures = studyObjects(book.getJSONArray("events")).filter { it.optString("state") == "failed" }
        if (failures.isNotEmpty()) {
            Text("助手尚未处理完：${failures.last().optString("error")}", color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !busy, onClick = { run { store.retry(id) } }) { Text("重新提醒助手") }
        }
        val pending = studyObjects(book.getJSONArray("events")).count { it.optString("state") == "pending" }
        if (pending > 0) Text("$pending 条书房消息等待助手空闲后处理", style = MaterialTheme.typography.labelSmall)
        book.optJSONObject("ai_read")?.let { progress -> Text("助手阅读进度：本次已读至第 ${progress.optInt("offset")} 字", style = MaterialTheme.typography.labelSmall) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chapters.forEach { item -> FilterChip(brief?.getString("id") == item.getString("id"), { chapterId = item.getString("id"); oldVersion = null }, label = { Text(item.getString("title")) }) }
        }
        if (brief != null && brief.getJSONArray("history").length() > 0) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(oldVersion == null, { oldVersion = null }, label = { Text("当前稿") })
                val history = brief.getJSONArray("history")
                for (i in 0 until history.length()) FilterChip(oldVersion == history.getString(i), { oldVersion = history.getString(i) }, label = { Text("旧稿 ${i + 1}") })
            }
        }
        chapter?.let { record ->
            Text(record.getString("title"), style = MaterialTheme.typography.titleLarge)
            Text(when (record.getString("author")) { "source" -> "导入原文"; "user" -> "你写的"; else -> "$name 写的" } + if (!record.optBoolean("published")) " · 草稿" else "", style = MaterialTheme.typography.labelSmall)
            Text("长按选择原文，即可划线留言", style = MaterialTheme.typography.labelSmall)
            StudyAnnotatedText(record.getString("text"), record.getString("version"), notes, size, name) { start, end, quote ->
                annotation = StudyAnchor(record.getString("version"), start, end, quote)
            }
            StudyPhotos(record.getJSONArray("photos"))
            Row {
                TextButton(enabled = !busy, onClick = { run { store.userEvent(id, "用户读完本章", record.getString("version")) } }) { Text("读完本章，告诉他") }
                if (book.getString("section") == "writing" && oldVersion == null) TextButton(enabled = !busy, onClick = { newChapter = false; editing = true }) { Text("编辑这一章") }
            }
        }
        if (book.getString("section") == "writing") Button(enabled = !busy, onClick = { newChapter = true; editing = true }) { Text("写新章节／短篇") }
        Row {
            TextButton(enabled = !busy, onClick = { annotation = StudyAnchor(null) }) { Text("写读后感") }
            TextButton(enabled = !busy, onClick = { run { store.userEvent(id, "用户邀请你继续共读或写作", version) } }) { Text("请 $name 来读／写") }
        }
        HorizontalDivider()
        Text("读后感", style = MaterialTheme.typography.titleMedium)
        notes.filter { it.isNull("version") }.forEach { StudyNote(it, name) }
    }
    if (editing) StudyChapterEditor(store, id, if (newChapter) null else chapter, busy, error, run) { editing = false; oldVersion = null }
    annotation?.let { anchor -> key(anchor) {
        StudyNoteEditor(store, id, anchor.version, anchor.start, anchor.end, anchor.quote, busy, error, run) { annotation = null }
    } }
}

@Composable
private fun StudyChapterEditor(store: StudyStore, book: String, previous: JSONObject?, busy: Boolean, error: String,
                                run: (suspend () -> Unit) -> Unit, dismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(previous?.getString("title").orEmpty()) }
    var text by rememberSaveable { mutableStateOf(previous?.getString("text").orEmpty()) }
    var photos by remember { mutableStateOf(previous?.getJSONArray("photos")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) run {
        val path = store.addPhoto(uri); withContext(Dispatchers.Main) { photos = photos + path }
    } }
    fun save(publish: Boolean) { run {
        store.write(book, previous?.getString("id"), previous?.getString("version"), title, text, JSONArray(photos), "user", publish)
        withContext(Dispatchers.Main) { dismiss() }
    } }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReadingTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (previous == null) "写新章节／短篇" else "编辑章节，旧稿与批注会保留", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(title, { title = it }, enabled = !busy, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text("故事正文") }, minLines = 10, modifier = Modifier.fillMaxWidth())
                Text("每章最多50000字，长篇可以继续加章。", style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = !busy && photos.size < 12, onClick = { picker.launch("image/*") }) { Text("放照片") }
                StudyPhotos(JSONArray(photos))
                if (photos.isNotEmpty()) TextButton(onClick = { photos = photos.dropLast(1) }, enabled = !busy) { Text("移除最后一张照片") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val valid = !busy && title.isNotBlank() && (text.isNotBlank() || photos.isNotEmpty()) && text.length <= 50000
                Row { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") }
                    TextButton(onClick = { save(false) }, enabled = valid) { Text("保存草稿") }
                    Button(onClick = { save(true) }, enabled = valid) { Text("写完发布并提醒他") } }
            }
        } }
    }
}
