package com.ai.assistance.operit.ui.features.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.companion.CompanionRuntime
import com.ai.assistance.operit.core.companion.CompanionStore
import com.ai.assistance.operit.core.companion.XcEmotionBridge
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.model.ActivePrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun CompanionSettingsButton() {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("状态延续、主动联系与资料库") }
    if (open) CompanionSettingsDialog { open = false }
}

@Composable
private fun CompanionSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CompanionStore(context) }
    val scope = rememberCoroutineScope()
    var emotion by remember { mutableStateOf(store.emotionEnabled) }
    var capabilities by remember { mutableStateOf(store.capabilitiesEnabled) }
    var proactive by remember { mutableStateOf(store.proactiveEnabled) }
    var silence by remember { mutableStateOf(store.silenceEnabled) }
    var seconds by remember { mutableStateOf(store.silenceSeconds.toString()) }
    var minutes by remember { mutableStateOf(store.proactiveMinutes.toString()) }
    var chatId by remember { mutableStateOf<String?>(null) }
    var roleId by remember { mutableStateOf<String?>(null) }
    var identity by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var section by remember { mutableIntStateOf(0) }
    var xcReceipt by remember { mutableStateOf(JSONObject()) }
    var xcConnections by remember { mutableStateOf<List<XcEmotionBridge.Connection>>(emptyList()) }
    var selectedXc by remember { mutableStateOf<String?>(null) }
    var roleLabel by remember { mutableStateOf("当前会话") }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var id by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("knowledge") }
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var memory by remember { mutableStateOf(JSONObject()) }
    var pinName by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(store.preferences.getString("target_chat", null)) }

    suspend fun refresh() {
        val selected = identity ?: return
        withContext(Dispatchers.IO) {
            Triple(XcEmotionBridge.receipt(context, selected), store.entries(selected), store.categories(selected))
        }.let { (state, items, folders) -> xcReceipt = state; entries = items; categories = folders }
        xcConnections = withContext(Dispatchers.IO) { XcEmotionBridge.connections(context) }
        if (selectedXc == null) selectedXc = xcReceipt.optString("plugin").takeIf { it.isNotBlank() }
        chatId?.let {
            memory = withContext(Dispatchers.IO) { store.read("summary:$it") }
            draft = memory.optString("draft")
        }
    }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = ""; action(); refresh() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val core = ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
            chatId = core.currentChatId.value
            val current = chatId ?: return@LaunchedEffect
            val known = withContext(Dispatchers.IO) { store.read("conversation:$current") }
            roleId = if (known.has("role_id")) known.optString("role_id").takeIf { it.isNotBlank() }
                else (ActivePromptManager.getInstance(context).getActivePrompt() as? ActivePrompt.CharacterCard)?.id
            identity = store.scope(current, roleId)
            roleLabel = roleId?.let { com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context).getCharacterCard(it)?.name }
                ?: "当前会话（未绑定角色卡）"
            refresh()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message.orEmpty() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { source = uri.toString(); kind = "file" }
    }

    LaunchedEffect(filter, categoryFilter, kindFilter) { page = 0 }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("状态与资料", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
                }
                Text(roleLabel, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("功能开关", "资料库", "保留记忆", "XC 情绪").forEachIndexed { index, label ->
                        TextButton(onClick = { section = index }) { Text(label) }
                    }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (identity != null) TextButton(enabled = !busy, onClick = { perform { } }) { Text("重新读取当前数据") }
                if (section == 0) {
                    Text("情绪自动接续、主动联系和能力感知可分别开关，方便交由你的引擎接管。")
                    CompanionToggle("自动接续 XC 情绪", emotion) {
                        emotion = it; store.preferences.edit().putBoolean("emotion_enabled", it).apply()
                    }
                    TextButton(onClick = { section = 3 }) { Text("查看情绪与 XC 连接") }
                    CompanionToggle("让 AI 感知软件能力和变化", capabilities) {
                        capabilities = it; store.preferences.edit().putBoolean("capabilities_enabled", it).apply()
                    }
                    Text("开启后额外提供权限、模型和工具能力的当前快照及变化提示。关闭后停止此项快照和专用查询，原有工具功能仍按各自设置运行。")
                    CompanionToggle("通话沉默时允许 AI 主动接话", silence) {
                        silence = it; store.preferences.edit().putBoolean("silence_enabled", it).apply()
                    }
                    OutlinedTextField(seconds, { seconds = it }, label = { Text("静默秒数（5–120）") }, singleLine = true)
                    CompanionToggle("软件主动联系", proactive) {
                        proactive = it; store.preferences.edit().putBoolean("proactive_enabled", it).apply()
                        com.ai.assistance.operit.api.chat.AIForegroundService.refreshBackgroundKeepAlive(context)
                    }
                    Text("主动联系可以发消息或来电，也可决定保持安静。运行时会观察空闲和设备事件，不需要另建定时工作流。需要软件后台服务运行，系统强制停止后无法继续。群聊暂不作为主动联系目标。")
                    OutlinedTextField(minutes, { minutes = it }, label = { Text("主动判断最小间隔，分钟（1–240）") }, singleLine = true)
                    Text(if (target == null) "尚未选择主动联系的会话" else "主动联系会话已设置")
                    TextButton(enabled = chatId != null && !busy, onClick = {
                        val current = checkNotNull(chatId)
                        val chat = ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN).chatHistories.value.firstOrNull { it.id == current }
                        if (!chat?.characterGroupId.isNullOrBlank()) error = "请选择单角色会话"
                        else {
                            CompanionRuntime.noteConversation(context, current, roleId)
                            target = current
                            store.preferences.edit().putString("target_chat", current).apply()
                        }
                    }) { Text("使用当前会话接收主动联系") }
                    TextButton(onClick = {
                        val s = seconds.toIntOrNull()
                        val m = minutes.toIntOrNull()
                        if (s == null || s !in 5..120 || m == null || m !in 1..240) error = "请填写范围内的整数"
                        else { store.preferences.edit().putInt("silence_seconds", s).putInt("proactive_minutes", m).apply(); error = "间隔已保存" }
                    }) { Text("保存间隔") }
                    Text("额外判断会使用配置的模型额度。没有接入心率数据源；不会依据沉默猜测健康状态。")
                    Text(store.preferences.getString("last_status", "尚无主动判断记录").orEmpty())
                } else if (identity == null) {
                    Text("请先打开一个聊天会话，再管理对应角色的资料与记忆。")
                } else if (section == 3) {
                    XcEmotionDashboard(xcReceipt, emotion)
                    Text("连接设置", style = MaterialTheme.typography.titleMedium)
                    Text("选择已在 MCP 管理中配置好的 XC。复用原有地址和授权；每个 XC 实例保存一套情绪，把多个角色绑定到同一实例会共享状态。")
                    xcConnections.forEach { connection ->
                        OutlinedCard(onClick = { selectedXc = connection.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text((if (selectedXc == connection.id) "✓ " else "") + connection.name)
                                Text(connection.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (xcConnections.isEmpty()) Text("没有启用的远程 MCP 连接。请先在 MCP 管理中配置 XC，再返回此页重新读取。")
                    Text("当前绑定：" + (xcConnections.firstOrNull { it.id == xcReceipt.optString("plugin") }?.name ?: "未绑定或连接不可用"))
                    Row {
                        TextButton(enabled = selectedXc != null && !busy, onClick = { perform {
                            XcEmotionBridge.checkConnection(context, checkNotNull(selectedXc))
                            error = "连接检查通过，未读取或签收 XC 状态。"
                        } }) { Text("检查连接") }
                        TextButton(enabled = selectedXc != null && !busy, onClick = { perform {
                            withContext(Dispatchers.IO) { XcEmotionBridge.bind(context, checkNotNull(identity), checkNotNull(selectedXc)) }
                            error = "已绑定。开启情绪接续后，下次对话自动同步。"
                        } }) { Text("绑定当前角色") }
                    }
                    TextButton(enabled = xcReceipt.optString("plugin").isNotBlank() && !busy, onClick = { perform {
                        withContext(Dispatchers.IO) { XcEmotionBridge.unbind(context, checkNotNull(identity)) }
                        selectedXc = null
                    } }) { Text("解除绑定") }
                    Text("页面显示最近同步的结果，不主动领取 XC 的交接信号，也不改写情绪数值。切换窗口或模型后，对话会继续读取同一角色绑定的 XC。")
                } else if (section == 1) {
                    Text("AI 可自行保存、分类、检索、移动和删除资料。能力笔记只是知识记录，不会自动运行代码。")
                    if (!editing) {
                    Button(enabled = !busy, onClick = {
                        id = null; title = ""; category = categoryFilter.orEmpty(); kind = "knowledge"; content = ""; source = null; editing = true
                    }) { Text("新建资料或分类") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryCount("全部", entries.size, Modifier.weight(1f))
                        LibraryCount("分类", categories.size, Modifier.weight(1f))
                        LibraryCount("文件", entries.count { it.optString("file").isNotBlank() }, Modifier.weight(1f))
                    }
                    OutlinedTextField(filter, { filter = it }, label = { Text("检索标题、分类或内容") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = categoryFilter == null, onClick = { categoryFilter = null }, label = { Text("全部分类") })
                        categories.forEach { folder ->
                            FilterChip(selected = categoryFilter == folder, onClick = { categoryFilter = folder },
                                label = { Text(folder + " · " + entries.count { it.optString("category") == folder }) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "全部类型", "knowledge" to "知识", "skill" to "能力", "file" to "文件").forEach { (value, label) ->
                            FilterChip(selected = kindFilter == value, onClick = { kindFilter = value }, label = { Text(label) })
                        }
                    }
                    val found = entries.filter { item ->
                        (categoryFilter == null || item.optString("category") == categoryFilter) &&
                        (kindFilter == null || item.optString("kind") == kindFilter) &&
                        listOf("title", "category", "content").any { item.optString(it).contains(filter, true) }
                    }.sortedByDescending { it.optLong("updated") }
                    val currentPage = page.coerceAtMost(((found.size - 1).coerceAtLeast(0)) / 20)
                    if (found.isEmpty()) Text("这里还没有资料，可以新建或让 AI 在聊天中整理保存。")
                    found.drop(currentPage * 20).take(20).forEach { item ->
                        OutlinedCard(onClick = {
                            id = item.getString("id"); title = item.getString("title"); category = item.getString("category")
                            kind = item.getString("kind"); content = item.optString("content"); source = null; editing = true
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.getString("title"), style = MaterialTheme.typography.titleMedium)
                                Text(item.getString("category") + " · " + when (item.getString("kind")) { "file" -> "文件"; "skill" -> "能力笔记"; else -> "知识" }, style = MaterialTheme.typography.labelMedium)
                                Text(item.optString("content").take(120), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        TextButton(enabled = currentPage > 0, onClick = { page = currentPage - 1 }) { Text("上一页") }
                        Text("共 ${found.size} 项 · 第 ${currentPage + 1} 页", modifier = Modifier.padding(top = 12.dp))
                        TextButton(enabled = (currentPage + 1) * 20 < found.size, onClick = { page = currentPage + 1 }) { Text("下一页") }
                    }
                    } else {
                    TextButton(enabled = !busy, onClick = { editing = false }) { Text("返回资料库") }
                    Text(if (id == null) "新建资料" else "资料详情", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(title, { title = it }, label = { Text("标题") })
                    OutlinedTextField(category, { category = it }, label = { Text("分类，可用 / 分级") })
                    Row {
                        listOf("knowledge" to "知识", "skill" to "能力", "file" to "文件").forEach { (value, label) ->
                            TextButton(onClick = { kind = value }) { Text(if (kind == value) "✓$label" else label) }
                        }
                    }
                    OutlinedTextField(content, { content = it }, label = { Text("正文或文件说明") }, minLines = 3)
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (source == null) "选择要保存的文件副本" else "已选文件，保存后复制") }
                    val storedFile = entries.firstOrNull { it.optString("id") == id }?.optString("file").orEmpty()
                    if (storedFile.isNotBlank()) TextButton(onClick = {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", java.io.File(storedFile))
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, "*/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (failure: Exception) { error = "无法打开文件：" + failure.message.orEmpty() }
                    }) { Text("打开保存的文件") }
                    Row {
                        TextButton(enabled = !busy, onClick = { perform {
                            withContext(Dispatchers.IO) { store.createCategory(checkNotNull(identity), category) }
                        } }) { Text("创建分类") }
                        TextButton(enabled = !busy, onClick = { perform {
                            withContext(Dispatchers.IO) { store.saveEntry(checkNotNull(identity), id, title, category, kind, content, source) }
                            id = null; title = ""; content = ""; source = null; editing = false
                            CompanionRuntime.publishEvent(context, "library", "用户更新了资料库，可自行决定是否需要联系用户。")
                        } }) { Text("保存资料") }
                    }
                    Row {
                        TextButton(onClick = { id = null; title = ""; content = ""; source = null; kind = "knowledge" }) { Text("新建") }
                        TextButton(enabled = id != null && !busy, onClick = { confirmDelete = id }) { Text("删除选中资料") }
                    }
                    }
                } else {
                    Text("保留片段不会交给压缩替换，每轮原样提供。合计上限16000字；过多会挤占模型上下文，请及时取消过期片段。")
                    val pins = memory.optJSONObject("pins") ?: JSONObject()
                    pins.keys().asSequence().toList().forEach { key ->
                        TextButton(onClick = { pinName = key; pinText = pins.getString(key) }) { Text(key) }
                    }
                    OutlinedTextField(pinName, { pinName = it }, label = { Text("片段名称") })
                    OutlinedTextField(pinText, { pinText = it }, label = { Text("原样保留内容") }, minLines = 3)
                    Row {
                        TextButton(enabled = !busy, onClick = { perform { withContext(Dispatchers.IO) {
                            store.setMemory(checkNotNull(chatId), "pin", pinName, pinText)
                        } } }) { Text("保留") }
                        TextButton(enabled = !busy, onClick = { perform { withContext(Dispatchers.IO) {
                            store.setMemory(checkNotNull(chatId), "unpin", pinName, "")
                        } } }) { Text("取消保留") }
                    }
                    OutlinedTextField(draft, { draft = it }, label = { Text("摘要草稿，也可由 AI 自行编写") }, minLines = 3)
                    TextButton(enabled = !busy, onClick = { perform { withContext(Dispatchers.IO) {
                        store.setMemory(checkNotNull(chatId), "draft", "", draft)
                    } } }) { Text("保存摘要草稿") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error)
                }
            }
        }
    }
    if (confirmDelete != null) AlertDialog(
        onDismissRequest = { confirmDelete = null },
        title = { Text("删除这份资料？") },
        text = { Text("会删除资料条目及其保存的文件副本，原始附件不受影响。") },
        confirmButton = { TextButton(onClick = {
            val deleteId = checkNotNull(confirmDelete); confirmDelete = null
            perform {
                withContext(Dispatchers.IO) { store.deleteEntry(checkNotNull(identity), deleteId) }
                id = null; title = ""; content = ""; source = null; editing = false
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
    )
}

@Composable
private fun LibraryCount(label: String, count: Int, modifier: Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CompanionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}
