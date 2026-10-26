package com.ai.assistance.operit.ui.features.settings.screens

import android.annotation.SuppressLint
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.experience.CollectionStore
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CollectionNavigation {
    val workspace = MutableStateFlow<Pair<String, String>?>(null)
    val pending = MutableStateFlow<Pair<String, Long>?>(null)
}

@Composable
internal fun CollectionEntry(role: String, onNavigate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("收藏与项目") }
    if (open) CollectionPanel(role, { open = false }, { open = false; onNavigate() })
}

@Composable
internal fun CollectionPanel(role: String, onDismiss: () -> Unit, onNavigate: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CollectionStore(context) }
    val scope = rememberCoroutineScope()
    val revision by CollectionStore.revision.collectAsState()
    var entries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var title by remember { mutableStateOf("") }; var tags by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }; var path by remember { mutableStateOf("") }
    var fileText by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var preview by remember { mutableStateOf(false) }
    var referenceQuery by remember { mutableStateOf("") }
    var choosingReference by remember { mutableStateOf(false) }
    var confirmWorkspace by remember { mutableStateOf(false) }
    var exportDocument by remember { mutableStateOf<JSONObject?>(null) }
    fun work(block: suspend () -> Unit) { if (!busy) scope.launch {
        busy = true; error = ""
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() } finally { busy = false }
    } }
    LaunchedEffect(role, revision) {
        try { entries = withContext(Dispatchers.IO) { store.list().filter { it.getString("role") == role } } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val item = selected
        if (uri != null && item != null) work {
            selected = withContext(Dispatchers.IO) {
                val text = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
                store.importFiles(item.getString("id"), JSONObject(text).getJSONObject("files"), item.getLong("version"))
            }
            path = ""; fileText = ""
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val item = exportDocument
        if (uri != null && item != null) work { withContext(Dispatchers.IO) {
            ZipOutputStream(requireNotNull(context.contentResolver.openOutputStream(uri))).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                entry("collection.json", item.toString(2))
                val files = item.getJSONObject("files")
                files.keys().forEach { name -> entry("files/$name", files.getString(name)) }
            }
        } }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp)) {
            Row { Text("收藏与项目", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val item = selected
                if (item == null) {
                    Text("长按聊天消息，选择“保存为收藏卡”。收藏副本可以独立编辑。")
                    entries.forEach { row -> OutlinedButton(onClick = {
                        selected = row; title = row.getString("title"); tags = row.getString("tags")
                        content = row.getString("content"); path = ""; fileText = ""
                    }) { Text("${row.getString("title")}\n${row.getString("tags")}") } }
                } else {
                    val id = item.getString("id")
                    TextButton(onClick = { selected = null }) { Text("返回列表") }
                    OutlinedTextField(title, { title = it }, label = { Text("标题") })
                    OutlinedTextField(tags, { tags = it }, label = { Text("标签") })
                    OutlinedTextField(content, { content = it }, label = { Text("卡片内容") }, modifier = Modifier.heightIn(max = 300.dp))
                    Button(enabled = !busy, onClick = { work { selected = withContext(Dispatchers.IO) {
                        store.edit(id, title, tags, content, item.getLong("version"))
                    } } }) { Text("保存卡片") }
                    TextButton(onClick = { work {
                        val chat = item.getString("chat"); val stamp = item.getLong("message")
                        val history = ChatHistoryManager.getInstance(context)
                        check(history.getChatMetadata(chat) != null) { "原会话已删除；收藏副本仍保留" }
                        check(history.loadChatMessagesFromTimestamp(chat, stamp).any { it.timestamp == stamp }) { "原消息已删除" }
                        CollectionNavigation.pending.value = chat to stamp
                        com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
                            .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN).switchChat(chat)
                        onNavigate()
                    } }) { Text("跳回原消息") }
                    val files = item.getJSONObject("files")
                    if (files.length() == 0) Button(enabled = !busy, onClick = { work {
                        selected = withContext(Dispatchers.IO) { store.promote(id) }
                    } }) { Text("代码块转成多文件项目") }
                    files.keys().forEach { filename -> TextButton(onClick = { path = filename; fileText = files.getString(filename) }) { Text(filename) } }
                    OutlinedTextField(path, { path = it }, label = { Text("文件路径，例如 index.html 或 tool.js") })
                    OutlinedTextField(fileText, { fileText = it }, label = { Text("文件内容") }, modifier = Modifier.heightIn(max = 350.dp))
                    TextButton(enabled = !busy && path.isNotBlank(), onClick = { work {
                        selected = withContext(Dispatchers.IO) { store.saveFile(id, path, fileText, item.getLong("version")) }
                    } }) { Text("保存文件") }
                    TextButton(enabled = !busy && path.isNotBlank() && files.has(path), onClick = { work {
                        selected = withContext(Dispatchers.IO) { store.addReference(id, id, path, item.getLong("version")) }
                    } }) { Text("将此文件固定为参考资料") }
                    HorizontalDivider()
                    Text("项目参考资料（保存引用时的副本，原文仍可追溯）")
                    OutlinedTextField(referenceQuery, { referenceQuery = it }, label = { Text("搜索资料标题或正文") })
                    TextButton(onClick = { choosingReference = !choosingReference }) { Text("从收藏添加参考资料") }
                    if (choosingReference) entries.filter { it.getString("id") != id }.forEach { source ->
                        TextButton(enabled = !busy, onClick = { work {
                            selected = withContext(Dispatchers.IO) { store.addReference(id, source.getString("id"), null, item.getLong("version")) }
                            choosingReference = false
                        } }) { Text("引用：" + source.getString("title")) }
                    }
                    val references = item.optJSONArray("references") ?: org.json.JSONArray()
                    for (i in 0 until references.length()) {
                        val ref = references.getJSONObject(i)
                        if (referenceQuery.isBlank() || (ref.getString("title") + ref.getString("content")).contains(referenceQuery, ignoreCase = true)) {
                            Text(ref.getString("title"), style = MaterialTheme.typography.titleSmall)
                            Text(ref.getString("content"), style = MaterialTheme.typography.bodySmall)
                            Text("来源：" + ref.getString("sourceId") + " · 版本 " + ref.getLong("sourceVersion"))
                            TextButton(onClick = { work {
                                val source = withContext(Dispatchers.IO) { store.read(ref.getString("sourceId")) }
                                check(source.getString("role") == role) { "来源不属于当前助手" }
                                selected = source; title = source.getString("title"); tags = source.getString("tags"); content = source.getString("content"); path = ""; fileText = ""
                            } }) { Text("打开来源收藏") }
                            TextButton(enabled = !busy && path.isNotBlank(), onClick = { work {
                                selected = withContext(Dispatchers.IO) { store.promoteReference(id, ref.getString("id"), path, item.getLong("version")) }
                                fileText = ref.getString("content")
                            } }) { Text("转成上方路径的新文件") }
                            TextButton(enabled = !busy, onClick = { work {
                                selected = withContext(Dispatchers.IO) { store.removeReference(id, ref.getString("id"), item.getLong("version")) }
                            } }) { Text("移除此引用") }
                        }
                    }
                    HorizontalDivider()
                    Text("工作区编辑：导出到独立目录，原项目保留；编辑后回来回收修改。回收文件替换会关闭脚本调用权限。")
                    TextButton(enabled = !busy && files.length() > 0, onClick = { confirmWorkspace = true }) { Text("导出并打开工作区") }
                    val workspace = item.optJSONObject("workspace")
                    if (workspace != null) {
                        Text(workspace.getString("path"), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { work {
                            selected = withContext(Dispatchers.IO) { store.pullWorkspace(id, item.getLong("version")) }
                            path = ""; fileText = ""
                        } }) { Text("回收工作区修改") }
                    }
                    if (confirmWorkspace) AlertDialog(onDismissRequest = { confirmWorkspace = false }, title = { Text("打开收藏工作区") },
                        text = { Text("会将原会话的工作区切换为此项目的新副本。原工作区目录不会删除。请先保存卡片和文件编辑。") },
                        confirmButton = { TextButton(onClick = { confirmWorkspace = false; work {
                            val chat = item.getString("chat")
                            val metadata = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chat)) { "原会话已删除，请在现有会话重新收藏后打开工作区" }
                            val owner = metadata.characterGroupId?.let { "group:$it" } ?: metadata.characterCardId
                            check(owner == role) { "原会话已更换助手，不能自动绑定" }
                            val exported = withContext(Dispatchers.IO) { store.exportWorkspace(id, item.getLong("version")) }
                            selected = exported
                            CollectionNavigation.workspace.value = chat to exported.getJSONObject("workspace").getString("path")
                            com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
                                .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN).switchChat(chat)
                            onNavigate()
                        } }) { Text("导出并切换") } }, dismissButton = { TextButton(onClick = { confirmWorkspace = false }) { Text("取消") } })
                    if (files.has("index.html")) TextButton(onClick = { preview = true }) { Text("打开项目预览") }
                    if (files.has("tool.js")) {
                        Text("tool.js 使用 input 参数与可写 state 对象，return 返回结果。成功后保存 state。启用后此助手可调用，脚本的工具操作仍遵守现有权限。")
                        Row { Checkbox(item.getBoolean("toolEnabled"), { enabled -> work { selected = withContext(Dispatchers.IO) { store.enableTool(id, enabled) } } }); Text("允许这位助手调用") }
                    }
                    Text("导入文件JSON会替换此项目文件集合并关闭脚本调用权限，请先导出保留旧版本。")
                    TextButton(enabled = !busy, onClick = { importFiles.launch(arrayOf("application/json", "text/plain")) }) { Text("导入项目文件JSON") }
                    TextButton(onClick = { exportDocument = item; export.launch("operit-project-$id.zip") }) { Text("导出卡片、项目和状态") }
                    TextButton(onClick = { work { withContext(Dispatchers.IO) { store.delete(id) }; selected = null } }) { Text("删除收藏副本") }
                    if (preview) ProjectPreview(store, id, onDismiss = { preview = false; work { selected = withContext(Dispatchers.IO) { store.read(id) } } })
                }
            }
        } }
    }
}

internal class PreviewBridge(private val store: CollectionStore, private val id: String, private val onError: (String) -> Unit) {
    @JavascriptInterface fun save(value: String) { try { store.savePreview(id, value) }
        catch (e: Exception) { onError(e.message.orEmpty()) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ProjectPreview(store: CollectionStore, id: String, onDismiss: () -> Unit) {
    val item = remember(id) { store.read(id) }
    val files = item.getJSONObject("files")
    val host = "$id.operit.invalid"
    var error by remember { mutableStateOf("") }
    var view by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { view?.removeJavascriptInterface("OperitPreview"); view?.destroy() } }
    fun closePreview() {
        val current = view
        if (current == null) onDismiss() else current.evaluateJavascript("window.__operitPersistPreview && window.__operitPersistPreview()") { onDismiss() }
    }
    Dialog(onDismissRequest = ::closePreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            Row { Text("项目预览", Modifier.weight(1f)); TextButton(onClick = ::closePreview) { Text("关闭") } }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context -> WebView(context).apply {
                view = this
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                addJavascriptInterface(PreviewBridge(store, id) { message -> scope.launch { error = message } }, "OperitPreview")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != host
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val name = request.url.path.orEmpty().removePrefix("/")
                        if (request.url.host != host || !files.has(name)) return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        val mime = when (name.substringAfterLast('.')) { "html" -> "text/html"; "css" -> "text/css"; "js" -> "application/javascript"; "json" -> "application/json"; "svg" -> "image/svg+xml"; else -> "text/plain" }
                        var text = files.getString(name)
                        if (name == "index.html") {
                            // Restore before project code runs. Every project has a distinct web origin.
                            val state = JSONObject.quote(item.getJSONObject("previewState").toString()).replace("<", "\\u003c")
                            text = "<script>(function(){const s=JSON.parse($state);for(const [k,v] of Object.entries(s.local||{}))localStorage.setItem(k,v);for(const [k,v] of Object.entries(s.session||{}))sessionStorage.setItem(k,v);window.addEventListener('DOMContentLoaded',()=>{const forms=Array.from(document.querySelectorAll('input,textarea,select'));forms.forEach((e,i)=>{const f=(s.forms||[])[i];if(f){e.value=f.value;e.checked=f.checked;}});const save=()=>{try{OperitPreview.save(JSON.stringify({local:{...localStorage},session:{...sessionStorage},forms:forms.map(e=>({value:e.value,checked:e.checked})),text:document.body.innerText.slice(0,20000),url:location.href}));}catch(e){console.error(e);}};window.__operitPersistPreview=save;document.addEventListener('input',save);document.addEventListener('change',save);setInterval(save,2000);save();});})();</script>" + text
                        }
                        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(text.toByteArray()))
                    }
                }
                loadUrl("https://$host/index.html")
            } })
        } }
    }
}
