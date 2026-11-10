package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal val innerFamilyNames = linkedMapOf("personality" to "人格", "self_knowledge" to "自我认识与局限",
    "value" to "价值", "stance" to "立场", "preference" to "偏好", "relation" to "关系认识",
    "drive" to "驱力", "need" to "需要", "desire" to "愿望", "emotion" to "当前情绪",
    "sentiment" to "持续情感", "feeling" to "感受")

/** All sections query the subject archive. Opening this view never creates a model request,
 * marks a candidate as read, changes attention, or adopts a state. */
@Composable
internal fun InternalStatePanel(role: String) {
    val sections = listOf("当前", "人格与自我", "偏好", "关系", "牵挂与事件", "相互影响", "打算与行动", "休息")
    var section by remember(role) { mutableStateOf("当前") }
    var editor by remember(role) { mutableStateOf(false) }
    Text("内部状态", style = MaterialTheme.typography.headlineSmall)
    Text("这里与聊天、书房和自主活动使用同一份状态。想法可以改变，曾经的经历仍有出处。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sections.forEach { label -> FilterChip(section == label, { section = label }, label = { Text(label) }) }
    }
    OutlinedButton(onClick = { editor = true }) { Text("已有内容承接 · 候选全文与修订") }
    key(role, section) {
        when (section) {
            "当前" -> {
                InternalStateValuesPanel(role)
                InnerFamilyPanel(role, listOf("drive", "need", "desire", "emotion", "sentiment", "feeling"))
            }
            "人格与自我" -> {
                Text("已采用的内容作为持续依据。初始设置仍保留；新的理解先成为候选，后续实际读取后才可采用。")
                InitialIdentityReference(role)
                InnerFamilyPanel(role, listOf("personality", "self_knowledge", "value", "stance"))
            }
            "偏好" -> {
                Text("长期喜欢、短暂兴趣、情境选择和配合他人分别记录；预览不会改写完整内容。")
                InnerFamilyPanel(role, listOf("preference"))
            }
            "关系" -> {
                Text("这是这位助手对不同对象的认识与感受，不代表对方也作出了相同判断。")
                InnerFamilyPanel(role, listOf("relation"))
            }
            "牵挂与事件" -> {
                InnerCollectionTabs(role, listOf("episode" to "不同的事情", "focus" to "重点安排", "thought" to "念头与关注"))
            }
            "相互影响" -> {
                Text("显示实际采用的理解与关联。联合结束不删除成员，成员之后的变化仍以各自记录为准。")
                PrivateRows(role, "union")
            }
            "打算与行动" -> {
                Text("想做、决定、实际执行和后来怎样理解分别保留。成功回执不等于愿望已满足。能力的构建、运行和调整可在操作详情中查看。")
                InnerCollectionTabs(role, listOf("decision" to "打算与选择", "action" to "实际操作与能力", "feedback" to "结果后的理解"))
            }
            "休息" -> {
                Text("休息由主体自己的选择承接。锁屏、断网和程序恢复不自动算作入睡或醒来。")
                PrivateRows(role, "rest")
                Text("梦醒随记", style = MaterialTheme.typography.titleLarge)
                Text("只呈现明确留给园园看的醒后记录；旧的私密梦境全文不会在这里自动公开。")
                ActivityNotesPanel(role, wakingOnly = true)
            }
        }
    }
    if (editor) InnerCandidateDialog(role, close = { editor = false })
}

@Composable
private fun InitialIdentityReference(role: String) {
    val context = LocalContext.current
    val card by CharacterCardManager.getInstance(context).getCharacterCardFlow(role).collectAsState(initial = null)
    PrivateFold("初始设置 · 仅供参考") {
        Text("原设置仍保存在角色卡中。运行契约保留；这里不会把它伪装成今天形成的人格。", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(card?.characterSetting.orEmpty().ifBlank { "没有初始人设文本。" }) }
    }
}

@Composable
private fun InnerFamilyPanel(role: String, families: List<String>) {
    var family by remember(role, families) { mutableStateOf(families.first()) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        families.forEach { item -> FilterChip(item == family, { family = item }, label = { Text(innerFamilyNames.getValue(item)) }) }
    }
    PrivateRows(role, "inner_family", family)
}

@Composable
private fun InnerCollectionTabs(role: String, tabs: List<Pair<String, String>>) {
    var selected by remember(role, tabs) { mutableStateOf(tabs.first().first) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { (id, title) -> FilterChip(selected == id, { selected = id }, label = { Text(title) }) }
    }
    PrivateRows(role, selected)
}

@Composable
internal fun PrivateRows(role: String, kind: String, id: String? = null, history: Boolean = false,
                         actions: (@Composable (JSONObject) -> Unit)? = null, revision: Int = 0) {
    val context = LocalContext.current
    var offset by remember(role, kind, id) { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember(role, kind, id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(role, kind, id) { mutableStateOf("") }
    var loading by remember(role, kind, id) { mutableStateOf(true) }
    LaunchedEffect(role, kind, id, offset, refresh, revision) {
        loading = true; error = ""
        try {
            page = withContext(Dispatchers.IO) {
                val chat = AssistantWindowRouter.chat(context, role).orEmpty()
                check(chat.isNotBlank()) { "请先为这位助手建立一个私聊窗口" }
                PrivateStateView.project(InternalStateStore(context).inspectCategory(role, chat, kind, id, offset))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "读取失败"; page = null }
        finally { loading = false }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (loading) "正在读取…" else page?.optLong("count", -1)?.takeIf { it >= 0 }?.let { "共 $it 条" }.orEmpty(), style = MaterialTheme.typography.labelMedium)
        TextButton(enabled = !loading, onClick = { refresh++ }) { Text("刷新") }
    }
    if (error.isNotEmpty()) Text("读取未完成：$error", color = MaterialTheme.colorScheme.error)
    val rows = page?.let { jsonRows(it.optJSONArray("items") ?: it.optJSONArray("records")) }.orEmpty()
    if (!loading && error.isEmpty() && rows.isEmpty()) Text("这里尚无已保存的记录。未加载的历史不会被视为遗忘或放下。", style = MaterialTheme.typography.bodySmall)
    rows.forEachIndexed { index, raw ->
        val row = raw.optJSONObject("value") ?: raw
        key(row.optString("id"), row.optLong("version"), index) { PrivateRecordCard(role, kind, row, history, actions) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = offset > 0 && !loading, onClick = { offset = (offset - 8).coerceAtLeast(0) }) { Text("上一页") }
        TextButton(enabled = page?.let { it.has("next_offset") && !it.isNull("next_offset") } == true && !loading,
            onClick = { offset = page!!.getInt("next_offset") }) { Text("下一页") }
    }
}

@Composable
private fun PrivateRecordCard(role: String, kind: String, row: JSONObject, history: Boolean,
                              actions: (@Composable (JSONObject) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val content = row.optJSONObject("content") ?: row.optJSONObject("record") ?: row.optJSONObject("request") ?: row
    val title = content.optString("kind").ifBlank { content.optString("title").ifBlank {
        content.optString("tool").ifBlank { innerFamilyNames[content.optString("family")] ?: privateValue(content.optString("state")).ifBlank { "一条记录" } }
    } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val preview = listOf("description", "understanding", "meaning", "text", "reason").firstNotNullOfOrNull {
                content.optString(it).takeIf(String::isNotBlank)
            }.orEmpty()
            if (preview.isNotBlank()) Text(preview, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(privateOrigin(row) + row.optLong("adopted_at", row.optLong("created_at", row.optLong("at"))).takeIf { it > 0 }?.let { " · ${privateTime(it)}" }.orEmpty(),
                style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { open = true }) { Text("查看全文与来源") }
            actions?.invoke(row)
        }
    }
    if (open) PrivateRecordDialog(role, kind, row, history) { open = false }
}

@Composable
internal fun PrivateRecordDialog(role: String, kind: String, row: JSONObject, history: Boolean = false, close: () -> Unit) {
    val context = LocalContext.current
    var full by remember(role, kind, row) { mutableStateOf(row) }
    var loadError by remember(role, kind, row) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(role, kind, row, retry) {
        if (kind != "action" || history) return@LaunchedEffect
        loadError = ""
        try {
            full = withContext(Dispatchers.IO) {
                // List heads deliberately omit large inputs/results. Detail reads the
                // durable receipt by ID, never expands a preview into invented content.
                PrivateStateView.project(requireNotNull(InternalStateStore(context).inspectCategory(role,
                    AssistantWindowRouter.chat(context, role).orEmpty(), "action", row.getString("id"), 0).optJSONObject("item")))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { loadError = failure.message ?: "完整回执读取失败" }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("完整记录", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = close) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (loadError.isNotBlank()) {
                        Text("完整回执尚未读取：$loadError", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                    Text(privateOrigin(full))
                    PrivateContent(full.optJSONObject("content") ?: full.optJSONObject("record") ?: full)
                    full.optJSONObject("request")?.let { PrivateFold("实际操作请求") { PrivateContent(it) } }
                    full.optJSONObject("declaration")?.let { PrivateFold("当时明确形成的操作打算") { PrivateContent(it) } }
                    full.optJSONObject("receipt")?.let { PrivateFold("实际回执 · 不代表愿望满足") { PrivateContent(it) } }
                    if (kind == "union") {
                        Text("联合记录的是当时成员版本；查看当前成员不会将旧联合投影回去。", style = MaterialTheme.typography.bodySmall)
                        jsonRows(full.optJSONObject("content")?.optJSONArray("members")).forEach { member ->
                            PrivateMember(role, member)
                        }
                    }
                    PrivateSources(role, full)
                    val target = row.optString("id")
                    if (!history && target.isNotBlank()) {
                        val rowKind = when (kind) { "inner_family", "inner" -> "inner_record"; else -> kind }
                        PrivateFold("变化历史 · 按需读取") {
                            PrivateRows(role, if (kind == "action") "action_versions" else "category_versions",
                                if (kind == "action") target else "$rowKind:$target", history = true)
                        }
                        if (kind in setOf("inner", "inner_family")) {
                            PrivateFold("关联经历") { PrivateRows(role, "experience_link", target) }
                            if (row.optJSONObject("content")?.optString("family") in setOf("drive", "need", "desire", "emotion", "sentiment", "feeling")) {
                                PrivateFold("这个类别里的不同事情") { PrivateRows(role, "episode_category", target) }
                            }
                        }
                    }
                    PrivateFold("版本与完整技术信息") { SelectionContainer { Text(full.toString(2), style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
    }
}

@Composable
private fun PrivateMember(role: String, member: JSONObject) {
    val context = LocalContext.current
    var current by remember(role, member.toString()) { mutableStateOf<JSONObject?>(null) }
    var requested by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(requested) {
        if (requested == 0) return@LaunchedEffect
        try {
            current = withContext(Dispatchers.IO) { InternalStateStore(context).inspectCategory(role,
                AssistantWindowRouter.chat(context, role).orEmpty(), member.getString("kind"), member.getString("id"), 0).optJSONObject("item")?.let(PrivateStateView::project) }
            error = if (current == null) "成员未能读取，关联仍保留。" else ""
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "成员读取失败" }
    }
    TextButton(onClick = { requested++ }) { Text("查看成员 ${member.getString("id")} · 关联时 v${member.getInt("version")}") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    current?.let { PrivateContent(it.optJSONObject("content") ?: it) }
}

@Composable
internal fun PrivateFold(title: String, body: @Composable ColumnScope.() -> Unit) {
    var open by remember(title) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + title) }
            if (open) body()
        }
    }
}

private val readableFields = linkedMapOf("kind" to "类别", "description" to "完整内容", "direction" to "方向", "level" to "需要层次",
    "object_ref" to "对象", "objects" to "相关对象", "goals" to "目标", "context" to "适用情境", "exceptions" to "例外与保留",
    "orientation" to "态度", "horizon" to "持续性", "ownership" to "自己的偏好或配合", "aspect" to "偏好方面",
    "label" to "关系称谓", "understanding" to "当前理解", "feeling" to "感受", "current_state" to "当前体验", "unresolved" to "仍有未解决部分",
    "intensity" to "当前强度", "strength" to "强度", "importance" to "重要性", "long_term_impact" to "长期意义",
    "attention" to "当前关注", "status" to "状态", "basis" to "理解来源", "meaning" to "共同形成的理解", "reason" to "当时的说明",
    "state" to "当前选择", "capacity" to "重点位置", "events" to "重点次序", "text" to "当时原文", "choice" to "选择",
    "members" to "独立成员", "effects" to "实际采用的影响", "related" to "事情之间的联系", "interpretation" to "对结果的理解",
    "account" to "变化说明", "decision" to "决定", "next" to "后续想法", "error" to "实际限制", "execution_status" to "执行情况",
    "capability" to "软件能力", "target" to "作用对象", "stage" to "构建或运行阶段", "version" to "版本",
    "tool" to "实际能力", "parameters" to "实际输入", "data" to "实际输出", "success" to "技术操作成功",
    "from" to "作用来自", "to" to "作用到", "reality" to "材料性质", "available" to "当前可读取", "visibility_notice" to "可见范围")

@Composable
private fun PrivateContent(value: JSONObject) {
    readableFields.forEach { (field, label) ->
        if (value.has(field) && !value.isNull(field)) {
            val item = value.get(field)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (item) {
                is JSONObject -> if (field in setOf("parameters", "data")) SelectionContainer { Text(item.toString(2)) } else PrivateContent(item)
                is JSONArray -> for (i in 0 until item.length()) {
                    val entry = item.get(i)
                    if (entry is JSONObject) {
                        PrivateContent(entry)
                        if (entry.has("ref")) Text("成员 ${entry.getString("ref")} · v${entry.optInt("version")}")
                    } else SelectionContainer { Text("${i + 1}. ${privateValue(entry.toString())}") }
                }
                else -> SelectionContainer { Text(privateValue(item.toString()).ifBlank { "未填写" }) }
            }
        }
    }
}

@Composable
private fun PrivateSources(role: String, row: JSONObject) {
    val context = LocalContext.current
    val content = row.optJSONObject("content") ?: row
    val bindings = row.optJSONObject("bindings") ?: row.optJSONObject("evidence") ?: content.optJSONObject("source_bindings")
        ?: row.optJSONObject("source_bindings")
    val evidence = row.optJSONArray("evidence") ?: JSONArray()
    val ids = ((0 until evidence.length()).map { index ->
        val value = evidence.get(index)
        if (value is JSONObject) value.optString("id") else value.toString()
    } +
        (bindings?.keys()?.asSequence()?.toList() ?: emptyList())).filter(String::isNotBlank).distinct()
    PrivateFold("来源与采用过程") {
        Text(privateOrigin(row))
        row.optString("reason").takeIf(String::isNotBlank)?.let { Text(it) }
        if (bindings != null) bindings.keys().forEach { key ->
            val binding = bindings.optJSONObject(key) ?: return@forEach
            if (binding.optString("source") == EstablishedContent.ORIGIN) {
                Text("已有确定内容导入/写入 · 仅代表声明承接，未声称核实旧聊天。")
                Text("实际提交：${privateTime(binding.getLong("submitted_at"))}")
                Text(if (!binding.has("formed_at") || binding.isNull("formed_at")) "历史形成时间未知" else "声明形成时间：${privateTime(binding.getLong("formed_at"))}")
            }
        }
        ids.forEach { id ->
            var source by remember(id) { mutableStateOf<JSONObject?>(null) }
            var request by remember(id) { mutableStateOf(false) }
            var error by remember(id) { mutableStateOf("") }
            LaunchedEffect(request) {
                if (!request) return@LaunchedEffect
                try { source = withContext(Dispatchers.IO) { InternalStateStore(context).inspectCategory(role,
                    AssistantWindowRouter.chat(context, role).orEmpty(), "source", id, 0).optJSONObject("item")?.let(PrivateStateView::project) }
                    if (source == null) error = "此引用不是聊天事件，完整来源快照在技术信息中保留。"
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "来源读取失败" }
            }
            TextButton(onClick = { request = true }) { Text("查阅来源 $id") }
            source?.let { original -> SelectionContainer { Text(original.optString("visibility_notice").ifBlank {
                original.optString("inner_source").ifBlank { original.optString("detail") }
            }) } }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
        }
        if (ids.isEmpty() && bindings == null) Text("这条记录没有额外原文依据；不补造经历。")
    }
}

internal fun privateOrigin(row: JSONObject): String = when (row.optString("origin")) {
    EstablishedContent.ORIGIN -> "来自已有确定内容导入/写入"
    "initial_configuration" -> "来自初始设置"
    "user_proposal" -> "来自用户提供的信息"
    "subject_development" -> "主体形成的变化"
    "model_suggestion" -> "待理解的建议"
    else -> when (row.optString("source")) { "subject_adoption" -> "已采用"; "user_edit" -> "用户修改"; else -> "实际保存的记录" }
} + if (row.has("adopted_at")) " · 已采用 v${row.optInt("version")}" else if (row.has("candidate_version")) " · 候选 v${row.optInt("candidate_version")}" else ""

internal fun privateValue(raw: String): String = mapOf("like" to "喜欢", "dislike" to "不喜欢", "neutral" to "无感", "uncertain" to "尚不确定",
    "long_term" to "长期", "short_term" to "短期", "contextual" to "视情境而定", "self" to "自己的偏好", "accommodation" to "为了配合别人",
    "foreground" to "当前关注", "background" to "留在背景", "aside" to "暂放", "unresolved" to "未解决", "processing" to "仍在处理", "resolved" to "已解决，经历仍保留",
    "active" to "持续", "retired" to "已结束，历史保留", "resting" to "正在休息", "awake" to "醒着", "broad" to "广义需要", "specific" to "细分需要",
    "recollection" to "重新想起旧事", "reinterpretation" to "形成了新的理解", "input" to "本次输入", "result" to "实际结果",
    "considering" to "在考虑", "chosen" to "已决定", "deferred" to "暂缓", "ended" to "已结束", "sink" to "先沉着", "now" to "准备表达", "drop" to "主动放下",
    "completed" to "实际完成", "failed" to "失败", "cancelled" to "取消", "unknown" to "结果未知", "started" to "已发起", "true" to "是", "false" to "否")[raw] ?: raw
