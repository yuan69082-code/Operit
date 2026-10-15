package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.companion.CompanionStore
import com.ai.assistance.operit.core.companion.EchoThemes
import com.ai.assistance.operit.core.companion.ThoughtPool
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Vio Life 小镇：首页画面。一栋房子对应一类功能，点房子进对应页面。
 * 位置按底图 vio_town（2:3）上的比例写，底图换了只改这张表。
 */
internal data class TownPlace(
    val key: String, val name: String, val hint: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
)

internal val townPlaces = listOf(
    TownPlace("tower", "瞭望塔", "手机感知 · 软件感知", 0.10f, 0.02f, 0.29f, 0.22f),
    TownPlace("hall", "镇公所", "主动联系 · 各种开关", 0.37f, 0.02f, 0.63f, 0.22f),
    TownPlace("workshop", "工坊", "工具 · 插件", 0.71f, 0.10f, 0.99f, 0.33f),
    TownPlace("neighbors", "邻居", "关系网，大家住一栋", 0.00f, 0.23f, 0.28f, 0.46f),
    TownPlace("home", "我们家", "日记 · 打算 · 纪念日", 0.30f, 0.25f, 0.59f, 0.48f),
    TownPlace("library", "图书馆", "阅读角", 0.60f, 0.25f, 0.88f, 0.46f),
    TownPlace("board", "公告栏", "群聊", 0.70f, 0.455f, 0.87f, 0.54f),
    TownPlace("ledger", "账房", "郑园园专用", 0.00f, 0.47f, 0.29f, 0.70f),
    TownPlace("pond", "念头池", "沉着的念头", 0.34f, 0.52f, 0.67f, 0.70f),
    TownPlace("games", "桌游馆", "群里开局", 0.78f, 0.51f, 1.00f, 0.69f),
    TownPlace("well", "回音井", "旧日回声", 0.58f, 0.65f, 0.75f, 0.75f),
    TownPlace("gate", "镇口", "回到聊天", 0.32f, 0.87f, 0.68f, 1.00f),
)

/**
 * 小镇弹窗版：聊天页顶上的「小镇」按钮还在用它。
 * @param onOuterPlace 关系网、群聊这类由聊天页自己处理的地方，交回调用方打开。
 */
@Composable
fun VioTownDialog(role: String?, chat: String, onOuterPlace: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        VioTownPage(role, chat, onGate = onDismiss, onOuterPlace = { onDismiss(); onOuterPlace(it) }, gateLabel = "回到聊天")
    }
}

/**
 * 小镇首页本体，不带弹窗外壳。底部导航的「小镇」直接放这一页。
 * @param onGate 点镇口或右上角按钮时调用。
 */
@Composable
fun VioTownPage(
    role: String?, chat: String, onGate: () -> Unit, onOuterPlace: (String) -> Unit,
    modifier: Modifier = Modifier, gateLabel: String? = null,
) {
    var place by remember { mutableStateOf<String?>(null) }
    var showNames by remember { mutableStateOf(true) }
    Surface(modifier.fillMaxSize(), color = Color(0xFF1B1A2E)) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Vio Life", color = Color(0xFFFFE3B0), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showNames = !showNames }) { Text(if (showNames) "藏起牌子" else "挂上牌子", color = Color(0xFFFFE3B0)) }
                if (gateLabel != null) TextButton(onClick = onGate) { Text(gateLabel, color = Color(0xFFFFE3B0)) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                // 底图按 2:3 等比放进可用区域，热区跟着同一个框走
                val ratio = 2f / 3f
                val w = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
                val h = w / ratio
                Box(Modifier.size(w, h)) {
                    Image(painterResource(R.drawable.vio_town), contentDescription = "Vio Life 小镇",
                        contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    townPlaces.forEach { p ->
                        Box(
                            Modifier
                                .offset(w * p.left, h * p.top)
                                .size(w * (p.right - p.left), h * (p.bottom - p.top))
                                .clickable(role = Role.Button) {
                                    when (p.key) {
                                        "gate" -> onGate()
                                        "neighbors", "board", "games" -> onOuterPlace(p.key)
                                        else -> place = p.key
                                    }
                                }
                                .semantics { contentDescription = "${p.name}：${p.hint}" },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (showNames) Text(p.name, color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 2.dp)
                                    .background(Color(0xAA2A2140), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    // 两个小人在最上层走，不拦点击
                    VioWalkers()
                }
            }
        }
    }
    when (val key = place) {
        null -> Unit
        "home", "library" -> if (role.isNullOrBlank()) TownNote("这里还没人住", "先去聊天里和一位助手单独聊过，再回来进门。") { place = null }
            else AssistantRoomDialog(role, chat, initialTab = if (key == "library") "阅读角" else "房间") { place = null }
        "tower", "hall" -> CompanionSettingsDialog { place = null }
        "pond" -> TownPond(role) { place = null }
        "ledger" -> TownLedger(chat) { place = null }
        "well" -> TownWell(role) { place = null }
        "workshop" -> TownNote("工坊", "还在装修。工具箱、插件和工作流先在「我的」里找。") { place = null }
    }
}
private val clock = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)

@Composable
private fun TownNote(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

@Composable
private fun TownSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("离开") } })
}

/** 念头池：沉着的在水底，到时间的浮上来。只看不改，念头去留由助手自己定。 */
@Composable
private fun TownPond(role: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val items = remember(role) { if (role.isNullOrBlank()) emptyList() else ThoughtPool.all(context, role) }
    val sunk = items.filter { it.optString("state") == "sink" }
    val up = ThoughtPool.surfaced(items, now)
    val down = sunk.filter { it !in up }.sortedBy { it.optLong("surface_at") }
    TownSheet("念头池", onDismiss) {
        if (sunk.isEmpty()) Text("池子现在是空的，水面很平。")
        if (up.isNotEmpty()) Text("浮上来的", style = MaterialTheme.typography.labelLarge)
        up.forEach { Text("· " + it.optString("text")) }
        if (down.isNotEmpty()) Text("还沉在底下的（${down.size}个）", style = MaterialTheme.typography.labelLarge)
        down.forEach { Text("${at(it.optLong("surface_at"))} 浮上来", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
    }
}

/** 账房：硬账本里记的数，不靠上下文去数。 */
@Composable
private fun TownLedger(chat: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ledger = remember(chat) { CompanionStore(context).read("ledger:$chat") }
    TownSheet("账房 · 郑园园专用", onDismiss) {
        val userAt = ledger.optLong("last_user_at")
        Text("她上一次发消息：" + if (userAt > 0) at(userAt) else "没有记录")
        Text("那之后我主动找她、她没回的次数：${ledger.optInt("unanswered")}")
        ledger.optLong("last_contact_at").takeIf { it > 0 }?.let { Text("我上一次主动开口：${at(it)}") }
        val open = ledger.optString("open").trim()
        Text("还没了结的事：" + open.ifBlank { "没有" })
    }
}

/** 回音井：主题和挂在下面的词。 */
@Composable
private fun TownWell(role: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val themes = remember(role) { if (role.isNullOrBlank()) emptyList() else EchoThemes.themes(context, role) }
    TownSheet("回音井", onDismiss) {
        if (themes.isEmpty()) Text("井里还没有回声。聊到的主题被记下以后，这里会亮起来。")
        themes.forEach { Text(it.name, style = MaterialTheme.typography.titleSmall); Text(it.words.joinToString("、"), style = MaterialTheme.typography.bodySmall) }
    }
}
