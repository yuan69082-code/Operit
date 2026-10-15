package com.ai.assistance.operit.ui.main.layout

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.chat.components.RelationshipEditor
import com.ai.assistance.operit.ui.features.settings.screens.VioTownPage
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.main.screens.ScreenRouteRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 底部五个按钮。CONTENT 不是按钮，是原来那套路由页面（聊天、设置等）正在显示。 */
internal enum class VioTab { TOWN, CONTENT, LIFE, PRIVATE, MINE }

private data class BarItem(val tab: VioTab, val label: String, val icon: ImageVector)

private val barItems = listOf(
    BarItem(VioTab.TOWN, "小镇", Icons.Filled.Home),
    BarItem(VioTab.CONTENT, "聊天", Icons.Filled.ChatBubble),
    BarItem(VioTab.LIFE, "生活", Icons.Filled.WbSunny),
    BarItem(VioTab.PRIVATE, "私域", Icons.Filled.Lock),
    BarItem(VioTab.MINE, "我的", Icons.Filled.Person),
)

/**
 * Vio Life 外壳：打开先是小镇，底部一排导航。原来的页面整块放在 [content] 里不动，
 * 侧边栏也还留着当备用。
 * @param navItems 原侧边栏的入口，收进「我的」。
 * @param onOpenScreen 让原路由切到某个页面。
 */
@Composable
internal fun VioShell(
    currentScreen: Screen,
    navItems: List<NavItem>,
    onOpenScreen: (Screen) -> Unit,
    canGoBack: Boolean,
    content: @Composable (mineBack: (() -> Unit)?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(VioTab.TOWN) }
    var neighborsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val chatId by ChatHistoryManager.getInstance(context).currentChatIdFlow.collectAsState()
    var role by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chatId) {
        role = chatId?.let { ChatHistoryManager.getInstance(context).getChatMetadata(it)?.characterCardId }
            ?.takeUnless { it.startsWith("unassigned:") }
    }
    fun openChat() { onOpenScreen(ScreenRouteRegistry.defaultScreenForNavItem(NavItem.AiChat)); tab = VioTab.CONTENT }

    // 在小镇以外的按钮页按返回，先回小镇
    BackHandler(enabled = tab == VioTab.LIFE || tab == VioTab.PRIVATE || tab == VioTab.MINE) { tab = VioTab.TOWN }

    // 打字时把底栏让出来，免得顶住输入框
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val onChatRoute = currentScreen is Screen.AiChat
    // 从「我的」点进去的页面没有自己的返回栈，给它一个回「我的」的路
    val mineBack: (() -> Unit)? = if (tab == VioTab.CONTENT && !onChatRoute) ({ tab = VioTab.MINE }) else null
    BackHandler(enabled = mineBack != null && !canGoBack) { tab = VioTab.MINE }
    val selected = when {
        tab != VioTab.CONTENT -> tab
        onChatRoute -> VioTab.CONTENT
        else -> VioTab.MINE
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 原内容一直挂着，切走也不重建聊天页
            content(mineBack)
            when (tab) {
                VioTab.TOWN -> VioTownPage(
                    role = role, chat = chatId.orEmpty(),
                    onGate = ::openChat,
                    onOuterPlace = { key -> if (key == "neighbors") neighborsOpen = true else openChat() },
                )
                VioTab.LIFE -> LifePage()
                VioTab.PRIVATE -> PrivatePage()
                VioTab.MINE -> MinePage(navItems) { screen -> onOpenScreen(screen); tab = VioTab.CONTENT }
                VioTab.CONTENT -> Unit
            }
        }
        // 聊天页用窄的底栏，只留图标，把位置让给消息
        val compact = tab == VioTab.CONTENT && onChatRoute
        if (!imeOpen || tab != VioTab.CONTENT) NavigationBar(
            modifier = if (compact) Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(52.dp) else Modifier,
            windowInsets = if (compact) WindowInsets(0) else NavigationBarDefaults.windowInsets,
        ) {
            barItems.forEach { item ->
                NavigationBarItem(
                    selected = selected == item.tab,
                    onClick = { if (item.tab == VioTab.CONTENT) openChat() else tab = item.tab },
                    icon = { Icon(item.icon, contentDescription = if (compact) item.label else null) },
                    label = if (compact) null else ({ Text(item.label) }),
                    alwaysShowLabel = !compact,
                )
            }
        }
    }
    if (neighborsOpen) AlertDialog(
        onDismissRequest = { neighborsOpen = false }, title = { Text("邻居") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { RelationshipEditor(chatId.orEmpty()) } },
        confirmButton = { TextButton(onClick = { neighborsOpen = false }) { Text("关闭") } },
    )
}

@Composable
private fun PageFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
private fun EntryCard(title: String, hint: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 生活：第一版只放天气、闹钟、提醒，手表数据以后再接。 */
@Composable
private fun LifePage() {
    val context = LocalContext.current
    var weather by remember { mutableStateOf("正在看天…") }
    LaunchedEffect(Unit) { weather = fetchWeather() ?: "天气没取到，等会儿再看看" }
    PageFrame("生活") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("天气", style = MaterialTheme.typography.titleMedium)
                Text(weather)
            }
        }
        EntryCard("闹钟", "打开手机自带的闹钟") { launch(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        EntryCard("提醒", "打开日历，新建一条提醒") {
            launch(context, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI))
        }
        Text("手表的心率、睡眠还没接进来，下一版再放。", style = MaterialTheme.typography.bodySmall)
    }
}

/** 私域：先占位。 */
@Composable
private fun PrivatePage() {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF1B1A2E)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("这里还没开放", color = Color(0xFFFFE3B0), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 我的：原侧边栏的入口收在这里，聊天已经有自己的按钮，不重复放。 */
@Composable
private fun MinePage(navItems: List<NavItem>, onOpen: (Screen) -> Unit) {
    PageFrame("我的") {
        navItems.filter { it != NavItem.AiChat }.forEach { item ->
            val title = stringResource(item.titleResId)
            Card(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                onOpen(ScreenRouteRegistry.defaultScreenForNavItem(item))
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun launch(context: Context, intent: Intent) {
    try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { }
}

/** wttr.in 按网络位置给一行天气，失败就返回 null。 */
private suspend fun fetchWeather(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("https://wttr.in/?format=%l:+%C+%t+体感%f+湿度%h&lang=zh").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 6000
        try { conn.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() && it.length < 200 } }
        finally { conn.disconnect() }
    }.getOrNull()
}
