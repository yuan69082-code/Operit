package com.ai.assistance.operit.ui.features.settings.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.core.tasks.TaskBoard
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class AssistantSpacePage(val title: String) {
    OVERVIEW("助手空间"), CONTACT("主动联系"), PLANS("私人打算"), EMOTION("情绪与状态"),
    ACTIVITY("唤醒与记录"), PHONE("手机感知"), SOFTWARE("软件感知"), EXPLORATION("自主探索")
}

@Composable
fun CompanionSettingsButton() {
    var open by remember { mutableStateOf(false) }
    SpaceLinkCard("助手空间", "房间、书房、自主活动与感知权限", Icons.Default.AutoAwesome, onClick = { open = true })
    if (open) CompanionSettingsDialog { open = false }
}

@Composable
internal fun CompanionSettingsDialog(
    initialPage: AssistantSpacePage = AssistantSpacePage.OVERVIEW,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(initialPage) }
    var role by remember { mutableStateOf<String?>(null) }
    var roomTab by remember { mutableStateOf<String?>(null) }
    var chat by remember(role) { mutableStateOf<String?>(null) }
    var roleName by remember(role) { mutableStateOf("") }
    var overviewRevision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            AssistantWindowRouter.initialize(context)
            role = (ActivePromptManager.getInstance(context).getActivePrompt() as? ActivePrompt.CharacterCard)?.id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message.orEmpty() }
    }
    LaunchedEffect(role) {
        chat = null
        try { role?.let {
            roleName = CharacterCardManager.getInstance(context).getCharacterCard(it).name
            chat = AssistantWindowRouter.chat(context, it)
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message.orEmpty() }
    }
    fun back() { if (page == AssistantSpacePage.OVERVIEW) onDismiss() else page = AssistantSpacePage.OVERVIEW }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                SpaceHeader(page.title, { back() }, onDismiss)
                if (page == AssistantSpacePage.OVERVIEW) {
                    AssistantTargetPicker(role, true, showExplanation = false) { role = it; error = "" }
                }
                if (page in setOf(AssistantSpacePage.PLANS, AssistantSpacePage.EMOTION, AssistantSpacePage.ACTIVITY, AssistantSpacePage.EXPLORATION)) {
                    Text(roleName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                key(role, page, overviewRevision) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val selected = role
                        when (page) {
                            AssistantSpacePage.OVERVIEW -> AssistantSpaceOverview(selected,
                                navigate = { page = it }, room = { roomTab = it })
                            AssistantSpacePage.CONTACT -> CompanionContactPanel()
                            AssistantSpacePage.PHONE -> PhoneAwarenessPanel()
                            AssistantSpacePage.SOFTWARE -> SoftwareAwarenessPanel()
                            else -> if (selected == null) {
                                Text("先在助手空间选择一位助手，再查看他的资料与活动。")
                            } else when (page) {
                                AssistantSpacePage.PLANS -> CompanionTodoPanel(CompanionStore(context), "role:$selected")
                                AssistantSpacePage.EMOTION -> CompanionEmotionPanel(selected)
                                AssistantSpacePage.ACTIVITY -> CompanionActivityPanel(CompanionStore(context), selected)
                                AssistantSpacePage.EXPLORATION -> ExplorationPanel(selected)
                                else -> Unit
                            }
                        }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    val selectedRole = role
    val selectedTab = roomTab
    if (selectedRole != null && selectedTab != null) {
        AssistantRoomDialog(selectedRole, chat.orEmpty(), selectedTab) { roomTab = null; overviewRevision++ }
    }
}

@Composable
private fun AssistantSpaceOverview(role: String?, navigate: (AssistantSpacePage) -> Unit, room: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { CompanionStore(context) }
    var counts by remember(role) { mutableStateOf<List<Int>?>(null) }
    var error by remember(role) { mutableStateOf("") }
    LaunchedEffect(role) {
        if (role != null) try {
            counts = withContext(Dispatchers.IO) { listOf(
                AssistantRoom(context, role).diaries().size,
                ReadingRoom(context, role).entries().size,
                store.entries("role:$role").size,
                TaskBoard(context, "role:$role", false).list().count { it.optString("status") !in setOf("done", "archived") }
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "概览读取失败：${failure.message}" }
    }
    SpaceSectionTitle("留下来的生活", "按助手分别保存，打开卡片继续查看。")
    SpaceLinkCard("房间", "日记、纪念日和房间布置", Icons.Default.Home,
        counts?.let { "${it[0]} 篇日记" }, enabled = role != null, onClick = { room("房间") })
    SpaceLinkCard("书房", "书架、收藏资料与文件，共用一个入口", Icons.Default.MenuBook,
        counts?.let { "${it[1]} 条待读 · ${it[2]} 份资料" }, enabled = role != null, onClick = { room("书房") })
    SpaceLinkCard("私人打算", "想继续的事、进展和下一步", Icons.Default.Checklist,
        counts?.let { "${it[3]} 件进行中的事" }, enabled = role != null, onClick = { navigate(AssistantSpacePage.PLANS) })
    SpaceLinkCard("情绪与状态", "查看当前助手的 XC 记录与连接", Icons.Default.FavoriteBorder,
        enabled = role != null, onClick = { navigate(AssistantSpacePage.EMOTION) })
    if (role == null) Text("选择助手后，可打开房间、书房与私人打算。", style = MaterialTheme.typography.bodySmall)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    SpaceSectionTitle("自主活动", "联系、探索和唤醒记录各自管理。")
    SpaceLinkCard("主动联系", "消息、来电与通话中的主动接话", Icons.Default.ChatBubbleOutline,
        if (store.proactiveEnabled) "软件主动联系已开启" else "软件主动联系已关闭",
        onClick = { navigate(AssistantSpacePage.CONTACT) })
    SpaceLinkCard("自主探索", "阅读、旧日记与想继续了解的方向", Icons.Default.Explore,
        enabled = role != null, onClick = { navigate(AssistantSpacePage.EXPLORATION) })
    SpaceLinkCard("唤醒与记录", "下次安排和实际发生的活动", Icons.Default.History,
        enabled = role != null, onClick = { navigate(AssistantSpacePage.ACTIVITY) })
    SpaceSectionTitle("感知权限", "每项能力单独选择观察助手与授权范围。")
    SpaceLinkCard("手机感知", "解锁、应用停留与已授权的内容", Icons.Default.PhoneAndroid,
        onClick = { navigate(AssistantSpacePage.PHONE) })
    SpaceLinkCard("软件感知", "软件内的变更、访问与事件记录", Icons.Default.Visibility,
        onClick = { navigate(AssistantSpacePage.SOFTWARE) })
}

@Composable
private fun CompanionContactPanel() {
    val context = LocalContext.current
    val store = remember { CompanionStore(context) }
    var proactive by remember { mutableStateOf(store.proactiveEnabled) }
    var silence by remember { mutableStateOf(store.silenceEnabled) }
    var seconds by remember { mutableStateOf(store.silenceSeconds.toString()) }
    var minutes by remember { mutableStateOf(store.proactiveMinutes.toString()) }
    var target by remember { mutableStateOf(store.proactiveRole) }
    var feedback by remember { mutableStateOf("") }
    SpaceSectionTitle("主动联系", "由助手决定发消息、来电或保持安静。")
    RoomSwitch("开启软件主动联系", proactive) {
        proactive = it
        store.preferences.edit().putBoolean("proactive_enabled", it).apply()
        com.ai.assistance.operit.api.chat.AIForegroundService.refreshBackgroundKeepAlive(context)
    }
    AssistantTargetPicker(target, true) { target = it; store.preferences.edit().putString("target_role", it).apply() }
    OutlinedTextField(minutes, { minutes = it }, label = { Text("首次等待，分钟（1–240）") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("首次唤醒后由助手安排下次时间。需要软件后台服务运行；系统强制停止后无法继续。", style = MaterialTheme.typography.bodySmall)
    SpaceSectionTitle("通话里的留白")
    RoomSwitch("沉默时允许主动接话", silence) { silence = it; store.preferences.edit().putBoolean("silence_enabled", it).apply() }
    OutlinedTextField(seconds, { seconds = it }, label = { Text("静默秒数（5–120）") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val s = seconds.toIntOrNull(); val m = minutes.toIntOrNull()
        if (s == null || s !in 5..120 || m == null || m !in 1..240) feedback = "请填写范围内的整数"
        else {
            store.preferences.edit().putInt("silence_seconds", s).putInt("proactive_minutes", m).apply()
            feedback = "间隔已保存"
        }
    }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("保存间隔") }
    if (feedback.isNotBlank()) Text(feedback)
    Text("额外判断会使用模型额度。手机事件唤醒在“手机感知”单独设置；群聊不作为主动联系目标。", style = MaterialTheme.typography.bodySmall)
    Text(store.preferences.getString("last_status", "尚无主动判断记录").orEmpty(), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CompanionEmotionPanel(role: String) {
    val context = LocalContext.current
    val store = remember { CompanionStore(context) }
    val scope = rememberCoroutineScope()
    var emotion by remember { mutableStateOf(store.emotionEnabled) }
    var receipt by remember(role) { mutableStateOf(JSONObject()) }
    var connections by remember { mutableStateOf<List<XcEmotionBridge.Connection>>(emptyList()) }
    var selected by remember(role) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) { XcEmotionBridge.receipt(context, "role:$role") to XcEmotionBridge.connections(context) }
        receipt = result.first; connections = result.second
        selected = receipt.optString("plugin").takeIf { it.isNotBlank() }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { feedback = ""; action(); refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message.orEmpty() }
            finally { busy = false }
        }
    }
    LaunchedEffect(role) { perform {} }
    RoomSwitch("自动接续 XC 情绪", emotion) { emotion = it; store.preferences.edit().putBoolean("emotion_enabled", it).apply() }
    Text("此开关作用于软件的 XC 自动接续；下面的绑定属于当前助手。", style = MaterialTheme.typography.bodySmall)
    XcEmotionDashboard(receipt, emotion)
    SpaceSectionTitle("连接", "复用 MCP 管理中的地址与授权。多个助手绑定同一 XC 实例会共享状态。")
    connections.forEach { connection ->
        OutlinedCard(onClick = { selected = connection.id }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp)) {
                RadioButton(selected == connection.id, onClick = null)
                Column(Modifier.padding(start = 8.dp)) { Text(connection.name); Text(connection.address, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (connections.isEmpty()) Text("没有启用的远程 MCP 连接，请配置后刷新。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(enabled = selected != null && !busy, onClick = {
            val connection = checkNotNull(selected)
            perform { XcEmotionBridge.checkConnection(context, connection); feedback = "连接检查通过" }
        }) { Text("检查连接") }
        Button(enabled = selected != null && !busy, onClick = {
            val connection = checkNotNull(selected)
            perform { withContext(Dispatchers.IO) { XcEmotionBridge.bind(context, "role:$role", connection) }; feedback = "已绑定" }
        }) { Text("绑定助手") }
    }
    Row {
        IconButton(enabled = !busy, onClick = { perform {} }) { Icon(Icons.Default.Refresh, "刷新连接与状态") }
        TextButton(enabled = receipt.optString("plugin").isNotBlank() && !busy, onClick = {
            perform { withContext(Dispatchers.IO) { XcEmotionBridge.unbind(context, "role:$role") } }
        }) { Text("解除绑定") }
    }
    Text("页面只显示最近同步结果，不主动领取交接信号或改写情绪。", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (feedback.isNotBlank()) Text(feedback)
}
