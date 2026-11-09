package com.ai.assistance.operit.ui.features.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.StringReader

@Composable
internal fun InnerCandidateDialog(role: String, close: () -> Unit) {
    val context = LocalContext.current
    val store = remember { InternalStateStore(context) }
    val scope = rememberCoroutineScope()
    var window by remember(role) { mutableStateOf(JSONObject()) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf("待处理候选") }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var editingAdopted by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(false) }
    var sourceMode by remember { mutableStateOf("已有确定内容") }
    var original by remember { mutableStateOf("") }
    var sourceLabel by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    suspend fun chat() = withContext(Dispatchers.IO) {
        requireNotNull(AssistantWindowRouter.chat(context, role)) { "请先建立该助手的私聊窗口" }
    }
    suspend fun importText(reader: java.io.Reader) {
        val target = chat()
        val raw = if (sourceMode == "已有确定内容") null else original.also { require(it.isNotBlank()) { "请填写实际原文" } }
        val label = if (raw == null) null else sourceLabel.also { require(it.isNotBlank()) { "请填写实际来源" } }
        val count = InnerContentImport.read(reader, store, role, target, raw, label) { done ->
            withContext(Dispatchers.Main) { message = "已保存 $done 项候选；中断后可重新导入同一内容续接。" }
        }
        withContext(Dispatchers.Main) {
            message = "已处理 $count 项。重复导入复用原提交，等待后续正常轮次读取与采用。"
            refresh++
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = ""
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "文件无法打开" }.bufferedReader().use { importText(it) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = "导入停在未完成项：${failure.message}。此前成功提交保留，可修正后续接。" }
            finally { busy = false; refresh++ }
        }
    }
    LaunchedEffect(role, refresh) {
        try { window = store.establishedStatus(role) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "承接状态读取失败" }
    }
    LaunchedEffect(role) { while (true) { delay(30_000); refresh++ } }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.95f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已有内容与候选", style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !busy, onClick = close) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("完整内容保存；预览与模型摘要不覆盖原文。提交不是采用，修改版需在后续正常轮次重新读到。")
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (window.optString("status")) {
                                "not_opened" -> {
                                    Text("48小时承接窗口尚未开启。由你首次开启时计时，之后不会因换窗、重启或更新重置。")
                                    FilledTonalButton(enabled = !busy, onClick = { scope.launch {
                                        busy = true; error = ""
                                        try { store.openEstablishedWindow(role); refresh++ }
                                        catch (cancel: CancellationException) { throw cancel }
                                        catch (failure: Exception) { error = failure.message ?: "开启失败" }
                                        finally { busy = false }
                                    } }) { Text("为这位助手开启48小时窗口") }
                                }
                                "open", "closed" -> {
                                    Text(if (window.getString("status") == "open") "已有内容承接中" else "已有内容新增/改写窗口已截止")
                                    Text("开始：${privateTime(window.getLong("opened_at"))}\n截止：${privateTime(window.getLong("closes_at"))}")
                                    val minutes = window.optLong("remaining_ms") / 60_000
                                    Text("剩余 ${minutes / 60} 小时 ${minutes % 60} 分钟 · 已提交 ${window.optLong("submitted")} 次")
                                    Text("截止前未改动的候选仍可采用。以后新的理解和修改使用正常来源，不能借旧内容标记绕过截止。", style = MaterialTheme.typography.bodySmall)
                                }
                                else -> Text("正在读取窗口状态…")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("待处理候选", "已采用内容", "导入与填写").forEach { label -> FilterChip(page == label, { page = label }, label = { Text(label) }) }
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when (page) {
                        "待处理候选" -> PrivateRows(role, "inner_pending", revision = refresh, actions = { row ->
                            Row {
                                TextButton(enabled = !busy, onClick = { editing = row; editingAdopted = false; form = true }) { Text("修改全文") }
                                TextButton(enabled = !busy, onClick = { scope.launch {
                                    busy = true; error = ""
                                    try { store.withdrawCandidate(role, chat(), row.getString("id"), InnerStructure.hash(row)); refresh++ }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (failure: Exception) { error = failure.message ?: "撤回失败" }
                                    finally { busy = false }
                                } }) { Text("撤回") }
                            }
                        })
                        "已采用内容" -> {
                            Text("采用版本保留原文。调整时创建新候选，旧版本和变化依据保留。")
                            PrivateRows(role, "inner", revision = refresh, actions = { row ->
                                TextButton(enabled = !busy, onClick = { editing = row; editingAdopted = true; form = true }) { Text("提出修改候选") }
                            })
                        }
                        else -> {
                            FilledTonalButton(enabled = !busy, onClick = { editing = null; editingAdopted = false; form = true }) { Text("直接填写完整内容") }
                            Text("文件和粘贴支持 JSON 对象、JSON 数组或逐行 JSON。每项是完整 record；可附 provenance.original / source / formed_at。按项保存，没有总条数上限。普通文字请用直接填写，全文作为一条内容保存。", style = MaterialTheme.typography.bodySmall)
                            SourceChoice(sourceMode, { sourceMode = it }, original, { original = it }, sourceLabel, { sourceLabel = it }, allowNew = false)
                            OutlinedButton(enabled = !busy, onClick = { filePicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("导入文件") }
                            OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth(), label = { Text("粘贴完整内容对象") }, minLines = 5)
                            FilledTonalButton(enabled = !busy && paste.isNotBlank(), onClick = { scope.launch {
                                busy = true; error = ""
                                try { importText(StringReader(paste)) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) { error = "提交停在未完成项：${failure.message}。此前成功项保留。" }
                                finally { busy = false; refresh++ }
                            } }) { Text("分项提交候选") }
                            PrivateFold("文件格式示例（不会自动提交）") {
                                Text("{\"id\":\"a-stable-id\",\"family\":\"personality\",\"kind\":\"表达方式\",\"description\":\"在这里保留完整描述、条件与例外\",\"status\":\"active\"}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (form) InnerCandidateForm(role, editing, editingAdopted, close = { form = false }, saved = { message = "完整候选已保存，等待后续正常读取与采用。"; refresh++; form = false })
}

@Composable
private fun SourceChoice(mode: String, change: (String) -> Unit, original: String, setOriginal: (String) -> Unit,
                         source: String, setSource: (String) -> Unit, allowNew: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("已有确定内容", "附原文与来源") + if (allowNew) listOf("提出新的变化") else emptyList()).forEach { value ->
            FilterChip(mode == value, { change(value) }, label = { Text(value) })
        }
    }
    when (mode) {
        "已有确定内容" -> Text("标记为“已有确定内容导入/写入”，不声称找到旧原文。形成时间未知可留空；仍需后续采用。", style = MaterialTheme.typography.bodySmall)
        "提出新的变化" -> Text("以本次用户提交为真实来源。不是旧内容补录，也不会自动成为主体已采用的认识。", style = MaterialTheme.typography.bodySmall)
        else -> {
            OutlinedTextField(original, setOriginal, Modifier.fillMaxWidth(), label = { Text("实际原文") }, minLines = 4)
            OutlinedTextField(source, setSource, Modifier.fillMaxWidth(), label = { Text("原文出处或来源说明") })
        }
    }
}

@Composable
private fun InnerCandidateForm(role: String, previous: JSONObject?, adopted: Boolean, close: () -> Unit, saved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val old = previous?.optJSONObject("content") ?: previous?.optJSONObject("record")
    var family by remember { mutableStateOf(old?.optString("family") ?: "personality") }
    var kind by remember { mutableStateOf(old?.optString("kind").orEmpty()) }
    var description by remember { mutableStateOf(old?.optString("description").orEmpty()) }
    var objectRef by remember { mutableStateOf(old?.optString("object_ref").orEmpty()) }
    var situation by remember { mutableStateOf(old?.optString("context").orEmpty()) }
    var exceptions by remember { mutableStateOf(old?.optString("exceptions").orEmpty()) }
    var sourceMode by remember { mutableStateOf(if (previous != null) "提出新的变化" else "已有确定内容") }
    var original by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var specific by remember { mutableStateOf(JSONObject((old ?: JSONObject()).toString()).apply {
        listOf("id", "family", "kind", "description", "object_ref", "context", "exceptions", "status").forEach(::remove)
    }.toString(2)) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val names by CharacterCardManager.getInstance(context).characterNamesFlow.collectAsState(initial = emptyMap())
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.94f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (adopted) "从已采用版本提出修改" else "完整候选", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        innerFamilyNames.forEach { (id, title) -> FilterChip(family == id, { family = id; specific = "{}" }, label = { Text(title) }, enabled = old == null) }
                    }
                    OutlinedTextField(kind, { kind = it }, Modifier.fillMaxWidth(), label = { Text("名称或细分方向") })
                    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("完整内容（保留限定、程度、矛盾与例外）") }, minLines = 7)
                    OutlinedTextField(situation, { situation = it }, Modifier.fillMaxWidth(), label = { Text("适用情境，可留空") })
                    OutlinedTextField(exceptions, { exceptions = it }, Modifier.fillMaxWidth(), label = { Text("例外与保留，可留空") })
                    if (family == "relation") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(objectRef == "user", { objectRef = "user" }, label = { Text("园园") })
                            names.filterKeys { it != role }.forEach { (id, name) -> FilterChip(objectRef == id, { objectRef = id }, label = { Text(name) }) }
                        }
                    } else OutlinedTextField(objectRef, { objectRef = it }, Modifier.fillMaxWidth(), label = { Text("对象（可留空）") })
                    InnerSpecificFields(role, family, specific) { specific = it }
                    PrivateFold("保留的关联与完整类别字段") {
                        Text("此处保留原有经历关联和特殊规则；修改会与其他字段一同校验，不会自动删掉未展示的内容。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(specific, { specific = it }, Modifier.fillMaxWidth(), label = { Text("完整字段") }, minLines = 3)
                    }
                    SourceChoice(sourceMode, { sourceMode = it }, original, { original = it }, source, { source = it }, allowNew = true)
                    if (previous != null) Text("此次修改生成新的候选版本；旧版仍在历史中，旧版的已读资格不会移给新版。")
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !busy, onClick = close) { Text("取消") }
                    FilledTonalButton(enabled = !busy, onClick = { scope.launch {
                        busy = true; error = ""
                        try {
                            val row = InnerContentImport.record(family, kind, description, objectRef, situation, exceptions,
                                JSONObject(specific), old?.getString("id"))
                            val raw = when (sourceMode) { "已有确定内容" -> null; "提出新的变化" -> row.toString(); else -> original }
                            val label = if (sourceMode == "提出新的变化") "用户在私域提出的完整内容或修订" else source
                            val digest = InnerStructure.hash(JSONObject().put("record", row).put("raw", raw ?: JSONObject.NULL)
                                .put("label", label).put("previous", previous?.let(InnerStructure::hash) ?: ""))
                            val chat = withContext(Dispatchers.IO) { requireNotNull(AssistantWindowRouter.chat(context, role)) { "请先建立这位助手的私聊窗口" } }
                            InternalStateStore(context).submitEstablishedContent(role, chat, "form:$digest", row, raw, label.takeIf { raw != null },
                                replaces = if (adopted) null else previous?.getString("id"), previousHash = if (adopted) null else previous?.let(InnerStructure::hash),
                                expectedTargetVersion = if (adopted) previous?.getInt("version") else previous?.optInt("expected_version"))
                            saved()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: "候选未保存，请检查内容" }
                        finally { busy = false }
                    } }) { Text(if (busy) "正在保存…" else "保存完整候选") }
                }
            }
        }
    }
}

@Composable
private fun InnerSpecificFields(role: String, family: String, raw: String, update: (String) -> Unit) {
    val value = try { JSONObject(raw) } catch (_: Exception) { return }
    fun set(name: String, entry: Any) { update(JSONObject(raw).put(name, entry).toString(2)) }
    @Composable fun field(name: String, title: String) {
        OutlinedTextField(value.optString(name), { set(name, it) }, Modifier.fillMaxWidth(), label = { Text(title) }, minLines = 2)
    }
    @Composable fun choices(name: String, title: String, options: List<Pair<String, String>>) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach { (id, label) -> FilterChip(value.optString(name) == id, { set(name, id) }, label = { Text(label) }) }
        }
    }
    when (family) {
        "preference" -> {
            field("aspect", "偏好方面，例如语言方式、爱好、话题")
            choices("orientation", "态度", listOf("like" to "喜欢", "dislike" to "不喜欢", "neutral" to "无感", "uncertain" to "尚不确定"))
            choices("horizon", "持续性", listOf("long_term" to "长期", "short_term" to "短期兴趣", "contextual" to "情境性"))
            choices("ownership", "这是…", listOf("self" to "自己的偏好", "accommodation" to "配合他人的行为"))
        }
        "relation" -> { field("label", "关系称谓"); field("understanding", "对这个对象的认识"); field("feeling", "对这个对象的感受") }
        "drive" -> field("direction", "驱力的方向")
        "need" -> choices("level", "需要层次", listOf("broad" to "广义需要", "specific" to "细分需要"))
        "emotion", "sentiment", "feeling" -> {
            field("current_state", "当前体验")
            Row { Checkbox(value.optBoolean("unresolved"), { set("unresolved", it) }); Text("仍有未解决的部分") }
            LaunchedEffect(family) { if (!value.has("unresolved")) set("unresolved", false) }
        }
    }
    if (family == "desire" || family == "need" && value.optString("level") == "specific") {
        var pick by remember { mutableStateOf(false) }
        Text("所属的需要或驱力：${value.optString("parent_ref").ifBlank { "尚未选择" }}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { pick = true }) { Text("选择已采用的上层需要或驱力") }
        if (pick) Dialog(onDismissRequest = { pick = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { pick = false }) { Text("关闭") }
                    var sourceFamily by remember { mutableStateOf("need") }
                    Row { listOf("need", "drive").forEach { key -> FilterChip(sourceFamily == key, { sourceFamily = key }, label = { Text(innerFamilyNames.getValue(key)) }) } }
                    PrivateRows(role, "inner_family", sourceFamily, actions = { row -> TextButton(onClick = { set("parent_ref", row.getString("id")); pick = false }) { Text("选择这一项") } })
                }
            }
        }
    }
}
