package com.ai.assistance.operit.ui.main.layout

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.settings.screens.CompanionSettingsDialog
import com.ai.assistance.operit.ui.features.settings.screens.AssistantTargetPicker
import com.ai.assistance.operit.ui.features.settings.screens.InternalStatePanel
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.ui.features.settings.screens.SpaceLinkCard
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.main.screens.ScreenRouteRegistry
import com.ai.assistance.operit.ui.theme.LocalAppThemeScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal enum class VioTab { HOME, CHAT, SPACE, TOOLS, LIFE, PRIVATE, MINE, DETAIL }
private data class BarItem(val tab: VioTab, val label: String, val icon: ImageVector)
private val barItems = listOf(
    BarItem(VioTab.HOME, "首页", Icons.Default.Home),
    BarItem(VioTab.CHAT, "聊天", Icons.Default.ChatBubbleOutline),
    BarItem(VioTab.SPACE, "空间", Icons.Default.AutoAwesome),
    BarItem(VioTab.TOOLS, "工具", Icons.Default.GridView),
    BarItem(VioTab.LIFE, "生活", Icons.Default.WbSunny),
    BarItem(VioTab.PRIVATE, "私域", Icons.Default.Lock),
    BarItem(VioTab.MINE, "我的", Icons.Default.PersonOutline),
)
private val toolItems = setOf(NavItem.Packages, NavItem.Toolbox, NavItem.ShizukuCommands, NavItem.Workflow, NavItem.Terminal, NavItem.ToolPermissions)

/** All host destinations and plugin routes remain real routes; root pages share one appearance. */
@Composable
internal fun VioShell(
    currentScreen: Screen,
    navItems: List<NavItem>,
    pluginEntries: List<NavigationEntrySpec>,
    onOpenScreen: (Screen) -> Unit,
    onOpenPlugin: (NavigationEntrySpec) -> Unit,
    isNetworkAvailable: Boolean,
    networkType: String,
    canGoBack: Boolean,
    content: @Composable (shellBack: (() -> Unit)?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(if (currentScreen is Screen.AiChat) VioTab.HOME else VioTab.DETAIL) }
    var origin by rememberSaveable { mutableStateOf(VioTab.MINE) }
    val chatNavigation = requireNotNull(LocalChatNavigation.current)
    LaunchedEffect(chatNavigation.externalRequest) {
        if (chatNavigation.externalRequest > 0) tab = VioTab.CHAT
    }
    val themeScope = LocalAppThemeScope.current
    val focus = LocalFocusManager.current
    fun root(destination: VioTab) {
        focus.clearFocus()
        if (destination == VioTab.CHAT) {
            chatNavigation.showDirectory()
            onOpenScreen(ScreenRouteRegistry.defaultScreenForNavItem(NavItem.AiChat))
        }
        tab = destination
    }
    fun openDestination(item: NavItem) {
        origin = tab
        tab = VioTab.DETAIL
        onOpenScreen(ScreenRouteRegistry.defaultScreenForNavItem(item))
    }
    var previousScreen by remember { mutableStateOf(currentScreen) }
    LaunchedEffect(currentScreen) {
        // Routes opened by tools, history or settings must not remain hidden under a root page.
        if (currentScreen != previousScreen) {
            if (currentScreen is Screen.AiChat) {
                if (tab == VioTab.DETAIL) tab = VioTab.CHAT
            } else if (tab != VioTab.DETAIL) {
                origin = tab; tab = VioTab.DETAIL
            }
            previousScreen = currentScreen
        }
    }
    val conversation = tab == VioTab.CHAT && currentScreen is Screen.AiChat && !chatNavigation.directoryVisible
    SideEffect { themeScope?.conversationVisible = conversation }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(tab, conversation) {
        if (tab != VioTab.DETAIL) com.ai.assistance.operit.core.awareness.SoftwareAwareness.record(
            appContext, "page", "打开软件页面", "navigation",
            org.json.JSONObject().put("route", if (conversation) "conversation" else tab.name.lowercase()))
    }
    val back: () -> Unit = {
        focus.clearFocus()
        if (conversation) chatNavigation.showDirectory() else tab = origin
    }
    BackHandler(enabled = conversation) { back() }
    BackHandler(enabled = tab != VioTab.HOME && tab != VioTab.DETAIL && !conversation) { root(VioTab.HOME) }
    BackHandler(enabled = tab == VioTab.DETAIL && !canGoBack) { back() }

    val routeVisible = tab == VioTab.CHAT || tab == VioTab.DETAIL
    CompositionLocalProvider(LocalRouteSurfaceVisible provides routeVisible) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Keep the route/service mounted while browsing root pages.
                Box(if (routeVisible) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics {}.layout { measurable, constraints ->
                    val measured = measurable.measure(constraints)
                    layout(measured.width, measured.height) { /* Kept in composition, never placed or hit-tested. */ }
                }) {
                    content(back)
                }
                when (tab) {
                    VioTab.HOME -> HomePage(
                        onChat = { root(VioTab.CHAT) }, onSpace = { root(VioTab.SPACE) },
                        onTools = { root(VioTab.TOOLS) }, onLife = { root(VioTab.LIFE) },
                    )
                    VioTab.SPACE -> CompanionSettingsDialog(embedded = true) { root(VioTab.HOME) }
                    VioTab.TOOLS -> DestinationPage("工具", "把需要的能力放在手边",
                        (navItems.filter { it in toolItems } + listOf(NavItem.Terminal, NavItem.ToolPermissions)).distinct(), pluginEntries, ::openDestination,
                        { entry ->
                            if (entry.action == null) { origin = VioTab.TOOLS; tab = VioTab.DETAIL }
                            onOpenPlugin(entry)
                        })
                    VioTab.MINE -> DestinationPage("我的", "偏好、模型与软件管理",
                        (navItems.filter { it != NavItem.AiChat && it !in toolItems && it != NavItem.MemoryBase } + listOf(NavItem.ChatHistorySettings, NavItem.TokenConfig)).distinct(),
                        emptyList(), ::openDestination, onOpenPlugin,
                        network = if (isNetworkAvailable) networkType else "网络未连接")
                    VioTab.LIFE -> LifePage { openDestination(NavItem.MemoryBase) }
                    VioTab.PRIVATE -> PrivatePage()
                    VioTab.CHAT, VioTab.DETAIL -> Unit
                }
            }
            if (!conversation) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                val selected = if (tab == VioTab.DETAIL) origin else tab
                barItems.forEach { item ->
                    NavigationBarItem(selected = selected == item.tab, onClick = { root(item.tab) },
                        icon = { Icon(item.icon, null) }, label = { Text(item.label) })
                }
            }
        }
    }
}

@Composable
private fun PageFrame(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = if (com.ai.assistance.operit.ui.theme.LocalThemePreferenceSnapshot.current.useBackgroundImage) Color.Transparent else MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HomePage(onChat: () -> Unit, onSpace: () -> Unit, onTools: () -> Unit, onLife: () -> Unit) {
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", java.util.Locale.CHINA))
    PageFrame("此刻", date) {
        Card(shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)))
                .padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                Text("今天，从这里开始。", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("找一位朋友聊聊，或去看看留下来的生活。", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Button(onClick = onChat) { Icon(Icons.Default.ChatBubbleOutline, null); Spacer(Modifier.width(8.dp)); Text("打开聊天") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("日常入口", style = MaterialTheme.typography.titleMedium)
        SpaceLinkCard("助手空间", "房间、书房、私人打算与自主活动", Icons.Default.AutoAwesome, onClick = onSpace)
        SpaceLinkCard("工具与创作", "插件、工作流和手机能力", Icons.Default.Widgets, onClick = onTools)
        SpaceLinkCard("生活", "天气、日历、闹钟与记忆", Icons.Default.WbSunny, onClick = onLife)
    }
}

@Composable
private fun DestinationPage(
    title: String, subtitle: String, items: List<NavItem>, plugins: List<NavigationEntrySpec>,
    onOpen: (NavItem) -> Unit, onPlugin: (NavigationEntrySpec) -> Unit, network: String? = null,
) {
    PageFrame(title, subtitle) {
        if (network != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.secondary)
            Text(network, style = MaterialTheme.typography.labelLarge)
        }
        items.forEach { item ->
            SpaceLinkCard(stringResource(item.titleResId), destinationHint(item), item.icon, onClick = { onOpen(item) })
        }
        if (plugins.isNotEmpty()) {
            Text("已安装扩展", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            plugins.forEach { entry -> SpaceLinkCard(entry.title, entry.description.orEmpty(), entry.icon, onClick = { onPlugin(entry) }) }
        }
    }
}

private fun destinationHint(item: NavItem): String = when (item) {
    NavItem.AssistantConfig -> "助手、群组、模型与对话配置"
    NavItem.Packages -> "插件、沙盒包、Skill 与 MCP"
    NavItem.Toolbox -> "可直接使用的工具与扩展"
    NavItem.ShizukuCommands -> "手机控制与执行权限"
    NavItem.Workflow -> "编排与运行自动化流程"
    NavItem.Terminal -> "终端会话与命令环境"
    NavItem.ToolPermissions -> "工具的授权与使用范围"
    NavItem.ChatHistorySettings -> "聊天记录的保存、整理与导出"
    NavItem.TokenConfig -> "用量与额度配置"
    NavItem.MemoryBase -> "查看和整理已有记忆"
    NavItem.Settings -> "主题与外观、语音、数据和软件偏好"
    NavItem.Help -> "使用指南与常见问题"
    NavItem.About -> "版本信息与更新"
    else -> "打开管理页面"
}

@Composable
private fun PrivatePage() {
    val context = LocalContext.current
    var role by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { role = (ActivePromptManager.getInstance(context).getActivePrompt() as? ActivePrompt.CharacterCard)?.id }
    PageFrame("私域", "只展开他愿意向你开放的部分") {
        AssistantTargetPicker(role, true) { role = it }
        val selected = role
        if (selected == null) Text("先选择一位助手。")
        else key(selected) { InternalStatePanel(selected) }
    }
}

@Composable
private fun LifePage(onMemory: () -> Unit) {
    val context = LocalContext.current
    PageFrame("生活", "安排今天，也留住走过的日子") {
        WeatherCard()
        SpaceLinkCard("闹钟", "查看手机闹钟", Icons.Default.Alarm, onClick = { launch(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)) })
        SpaceLinkCard("日历与提醒", "在日历中记录下一件事", Icons.Default.Event,
            onClick = { launch(context, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)) })
        SpaceLinkCard("记忆库", "已有记忆、分类与检索", Icons.Default.History, onClick = onMemory)
    }
}
private fun launch(context: Context, intent: Intent) {
    try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { Toast.makeText(context, "手机上没有可处理此操作的应用", Toast.LENGTH_LONG).show() }
}
