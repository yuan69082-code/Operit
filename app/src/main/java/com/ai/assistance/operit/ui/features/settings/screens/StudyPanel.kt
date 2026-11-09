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
    val savedRevision by com.ai.assistance.operit.core.companion.AssistantRoom.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var category by rememberSaveable(role, section) { mutableStateOf("daily") }
    var books by remember(role) { mutableStateOf(emptyList<JSONObject>()) }
    var selectedId by rememberSaveable(role, section) { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var starterShelf by remember { mutableStateOf(false) }
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
    LaunchedEffect(role, revision, savedRevision, reload) {
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
        if (section == "reading") TextButton(onClick = { starterShelf = true }, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp)) { Text("选本准备好的书 · 离线导入") }
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
    if (starterShelf) AlertDialog(onDismissRequest = { if (!busy) starterShelf = false }, title = { Text("准备好的共读书包") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已随应用下载，无需寻找文件。点击加入当前角色的日常书架；重复点击会打开已有书籍。中文为古文原版，神话英文书保留原文。")
            StudyImport.starters.forEach { starter ->
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { run {
                    val added = StudyImport.starter(context, role, starter)
                    withContext(Dispatchers.Main) { category = "daily"; selectedId = added.getString("id"); starterShelf = false }
                } }) { Column { Text(starter.title); Text(starter.description, style = MaterialTheme.typography.bodySmall) } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { starterShelf = false }) { Text("关闭") } })
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

private data class StudyAnchor(val version: String?, val start: Int = 0, val end: Int = 0, val quote: String = "", val replyTo: String? = null)

@Composable
private fun StudyBook(store: StudyStore, book: JSONObject, name: String, busy: Boolean, error: String,
                      run: (suspend () -> Unit) -> Unit, dismiss: () -> Unit) {
    val id = book.getString("id")
    val chapters = studyObjects(book.getJSONArray("chapters"))
    val notes = studyObjects(book.getJSONArray("notes"))
    var screen by rememberSaveable(id) { mutableStateOf("contents") }
    var chapterId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var oldVersion by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var paged by rememberSaveable(id) { mutableStateOf(false) }
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<StudyAnchor?>(null) }
    var jumpSequence by remember { mutableIntStateOf(0) }
    val brief = chapters.firstOrNull { it.getString("id") == chapterId }
    val version = oldVersion ?: brief?.getString("version")
    var chapter by remember { mutableStateOf<JSONObject?>(null) }
    var loadingError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var newChapter by remember { mutableStateOf(false) }
    var annotation by remember { mutableStateOf<StudyAnchor?>(null) }
    val body = chapter?.getString("text").orEmpty()
    val pages = remember(body) { com.ai.assistance.operit.core.companion.StudyReaderPolicy.pages(body) }
    val safePage = page.coerceIn(0, pages.lastIndex)
    val span = pages[safePage]
    fun openChapter(index: Int) {
        chapterId = chapters[index].getString("id"); oldVersion = null
        target = null; page = 0; screen = "chapter"; jumpSequence++
    }
    fun reply(note: JSONObject) {
        annotation = StudyAnchor(if (note.isNull("version")) null else note.getString("version"),
            note.getInt("start"), note.getInt("end"), note.getString("quote"), note.getString("id"))
    }
    fun locate(note: JSONObject) {
        if (note.isNull("version")) return
        val noteVersion = note.getString("version")
        val owner = chapters.firstOrNull { item -> item.getString("version") == noteVersion ||
            (0 until item.getJSONArray("history").length()).any { item.getJSONArray("history").getString(it) == noteVersion } }
        if (owner == null) { loadingError = "找不到这条批注对应的原稿"; return }
        chapterId = owner.getString("id")
        oldVersion = if (owner.getString("version") == noteVersion) null else noteVersion
        target = StudyAnchor(noteVersion, note.getInt("start"), note.getInt("end"), note.getString("quote"))
        page = 0; screen = "chapter"; jumpSequence++
    }
    LaunchedEffect(id, version) {
        chapter = null; loadingError = ""
        if (version != null) try { chapter = withContext(Dispatchers.IO) { store.chapter(id, version) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLogger.e("StudyBook", "章节读取失败", failure); loadingError = failure.message.orEmpty() }
    }
    LaunchedEffect(chapter, target, paged, jumpSequence) {
        if (paged && target != null && chapter != null) page = com.ai.assistance.operit.core.companion.StudyReaderPolicy.pageAt(pages, target!!.start)
    }
    val index = chapters.indexOfFirst { it.getString("id") == chapterId }
    ReadingBookDialog(book.getString("title"), "${if (book.getString("section") == "reading") "共读" else "写作"} · ${if (book.getString("category") == "sex") "Sex" else "日常"}", dismiss,
        navigationKey = "$screen:$version:$paged:$safePage:$jumpSequence",
        footer = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                HorizontalDivider()
                if (screen == "chapter" && paged && chapter != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = safePage > 0, onClick = { target = null; page = safePage - 1 }) { Text("上一页") }
                    Text("${safePage + 1} / ${pages.size} 页", Modifier.padding(top = 12.dp))
                    TextButton(enabled = safePage < pages.lastIndex, onClick = { target = null; page = safePage + 1 }) { Text("下一页") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (screen == "chapter") TextButton(enabled = index > 0, onClick = { openChapter(index - 1) }) { Text("上一章") }
                    TextButton(onClick = { screen = "contents"; target = null }) { Text("目录") }
                    TextButton(onClick = { screen = "notes"; target = null }) { Text("批注与回复") }
                    if (screen == "chapter") TextButton(enabled = index >= 0 && index < chapters.lastIndex, onClick = { openChapter(index + 1) }) { Text("下一章") }
                }
            }
        }) { size ->
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (loadingError.isNotBlank()) Text(loadingError, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (screen) {
            "contents" -> {
                Text("目录 · ${chapters.size} 章", style = MaterialTheme.typography.titleLarge)
                if (book.optInt("unread") > 0) TextButton(onClick = { screen = "notes" }) { Text("查看 ${book.optInt("unread")} 条更新与留言") }
                chapters.forEachIndexed { i, item ->
                    OutlinedButton(onClick = { openChapter(i) }, modifier = Modifier.fillMaxWidth()) { Text("${i + 1}. ${item.getString("title")}" + if (!item.optBoolean("published")) " · 草稿" else "") }
                }
                if (chapters.isEmpty()) Text("还没有章节，可以开始写第一篇。")
                if (book.getString("section") == "writing") Button(enabled = !busy, onClick = { newChapter = true; editing = true }) { Text("写新章节／短篇") }
                TextButton(enabled = !busy, onClick = { annotation = StudyAnchor(null) }) { Text("写读后感") }
                TextButton(enabled = !busy, onClick = { run { store.userEvent(id, "用户邀请你继续共读或写作") } }) { Text("请 $name 来读／写") }
                val failures = studyObjects(book.getJSONArray("events")).filter { it.optString("state") == "failed" }
                if (failures.isNotEmpty()) {
                    Text("助手尚未处理完：${failures.last().optString("error")}", color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !busy, onClick = { run { store.retry(id) } }) { Text("重新提醒助手") }
                }
                val pending = studyObjects(book.getJSONArray("events")).count { it.optString("state") == "pending" }
                if (pending > 0) Text("$pending 条消息等待助手处理")
            }
            "notes" -> {
                Text("批注、回复与读后感 · ${notes.size} 条", style = MaterialTheme.typography.titleMedium)
                if (book.optInt("unread") > 0) TextButton(enabled = !busy, onClick = { run { store.acknowledge(id) } }) { Text("标记已读") }
                if (notes.isEmpty()) Text("还没有留言，读到有意思的地方可以划线。")
                notes.sortedByDescending { it.optLong("at") }.forEach { note ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        val parent = notes.firstOrNull { it.getString("id") == note.optString("reply_to") }
                        if (parent != null) Text("回复：${parent.optString("text").take(160)}", style = MaterialTheme.typography.labelMedium)
                        if (note.optString("quote").isNotBlank()) Text("原文：${note.getString("quote").take(250)}", style = MaterialTheme.typography.bodySmall)
                        StudyNote(note, name, ::reply)
                        if (!note.isNull("version")) TextButton(onClick = { locate(note) }) { Text("回到原文这一段") }
                        else Text("全书读后感", style = MaterialTheme.typography.labelSmall)
                    } }
                }
            }
            "chapter" -> {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(!paged, { paged = false; page = 0 }, label = { Text("上下滚动") })
                    FilterChip(paged, { paged = true; page = 0 }, label = { Text("分页阅读") })
                }
                if (brief != null && brief.getJSONArray("history").length() > 0) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(oldVersion == null, { oldVersion = null; page = 0; target = null }, label = { Text("当前稿") })
                    val history = brief.getJSONArray("history")
                    for (i in 0 until history.length()) FilterChip(oldVersion == history.getString(i), { oldVersion = history.getString(i); page = 0; target = null }, label = { Text("旧稿 ${i + 1}") })
                }
                chapter?.let { record ->
                    Text(record.getString("title"), style = MaterialTheme.typography.titleLarge)
                    Text(when (record.getString("author")) { "source" -> "导入原文"; "user" -> "你写的"; else -> "$name 写的" }, style = MaterialTheme.typography.labelSmall)
                    Text("长按选中文字可划线留言", style = MaterialTheme.typography.labelSmall)
                    key(version, safePage, paged, jumpSequence) {
                        StudyAnnotatedText(if (paged) body.substring(span.start, span.end) else body, record.getString("version"), notes, size, name,
                            baseOffset = if (paged) span.start else 0, focusStart = target?.start, focusEnd = target?.end, reply = ::reply) { start, end, quote ->
                            annotation = StudyAnchor(record.getString("version"), start, end, quote)
                        }
                    }
                    if (!paged || safePage == pages.lastIndex) StudyPhotos(record.getJSONArray("photos"))
                    Row {
                        TextButton(enabled = !busy, onClick = { run { store.userEvent(id, "用户读完本章", record.getString("version")) } }) { Text("读完，告诉他") }
                        if (book.getString("section") == "writing" && oldVersion == null) TextButton(enabled = !busy, onClick = { newChapter = false; editing = true }) { Text("编辑本章") }
                    }
                }
                if (chapter == null && loadingError.isEmpty()) CircularProgressIndicator()
            }
        }
    }
    if (editing) StudyChapterEditor(store, id, if (newChapter) null else chapter, busy, error, run) { editing = false; oldVersion = null }
    annotation?.let { anchor -> key(anchor) {
        StudyNoteEditor(store, id, anchor.version, anchor.start, anchor.end, anchor.quote, busy, error, run, replyTo = anchor.replyTo) { annotation = null }
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
