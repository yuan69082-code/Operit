package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.core.tasks.TaskBoard
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun AssistantRoomDialog(role: String, chat: String, initialTab: String = "房间", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val room = remember(role) { AssistantRoom(context, role) }
    val scope = rememberCoroutineScope()
    val revision by AssistantRoom.revision.collectAsState()
    var data by remember(role) { mutableStateOf(JSONObject()) }
    var name by remember(role) { mutableStateOf("") }
    // Keep previously published tab names usable by existing shortcuts.
    var tab by remember(role) { mutableStateOf(when (initialTab) {
        "阅读角", "资料库", "书房" -> "书房"
        "外观" -> "房间"
        "设置" -> "布置"
        else -> initialTab
    }) }
    var bookSection by remember(role) { mutableStateOf(if (initialTab == "资料库") "资料" else "书架") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var advanced by remember(role) { mutableStateOf(initialTab == "外观") }
    LaunchedEffect(role, revision) { try {
        data = withContext(Dispatchers.IO) { room.read() }
        name = CharacterCardManager.getInstance(context).getCharacterCard(role).name
    } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message.orEmpty() } }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { error = ""; withContext(Dispatchers.IO) { action() }; data = room.read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { busy = false } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = if (tab == "书房") 0.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SpaceHeader(if (tab == "书房") "$name · 书房" else "$name · 房间",
                        { if (tab == "书房") tab = "房间" else onDismiss() }, onDismiss, enabled = !busy)
                }
                if (tab != "书房") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("房间", "日记", "书房", "布置").forEach { label ->
                        FilterChip(selected = label == tab, onClick = { tab = label; error = "" }, label = { Text(label) }, leadingIcon = { Icon(when (label) { "房间" -> Icons.Default.Home; "日记" -> Icons.Default.EditNote; "书房" -> Icons.Default.MenuBook; else -> Icons.Default.Tune }, null, Modifier.size(18.dp)) })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                key(role, tab) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = if (tab == "书房") 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            "房间" -> RoomHome(room, data, chat, revision, {
                                when (it) {
                                    "外观" -> advanced = true
                                    "阅读角" -> { bookSection = "书架"; tab = "书房" }
                                    "资料库" -> { bookSection = "资料"; tab = "书房" }
                                    "设置" -> tab = "布置"
                                    else -> tab = it
                                }
                            }, { work(it) })
                            "日记" -> RoomDiary(room, chat, revision, { work(it) })
                            "书房" -> ReadingPanel(role, chat, name, revision, bookSection, { work(it) })
                            "布置" -> {
                                RoleRulesEntry(role)
                                PersonaWizardEntry()
                                CollectionEntry(role, onDismiss)
                                RoomOptions(room, data, { work(it) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (advanced) AssistantThemeDialog(role) { advanced = false }
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" },
        title = { Text("未能完成") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = { error = "" }) { Text("知道了") } })
}

@Composable
private fun RoomHome(room: AssistantRoom, data: JSONObject, chat: String, revision: Long, navigate: (String) -> Unit, work: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var arranging by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var tasks by remember { mutableStateOf(emptyList<JSONObject>()) }
    var diaries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var reading by remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(revision) { withContext(Dispatchers.IO) {
        entries = CompanionStore(context).entries("role:${room.role}")
        tasks = TaskBoard(context, "role:${room.role}", false).list().filter { it.optString("status") !in setOf("done", "archived") }
        diaries = room.diaries()
        reading = ReadingRoom(context, room.role).entries()
    } }
    Card(shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
            if (data.optString("cover").isNotBlank()) AsyncImage(data.optString("cover"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .86f)).padding(18.dp)) {
                Text(data.optString("title", "留在这里的一角"), style = MaterialTheme.typography.headlineSmall)
                Text(data.optString("subtitle", "日记、阅读和想继续的事，都有自己的位置。"))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { arranging = !arranging }) { Icon(if (arranging) Icons.Default.Check else Icons.Default.DashboardCustomize, null); Spacer(Modifier.width(6.dp)); Text(if (arranging) "完成布置" else "布置组件") }
        IconButton(onClick = { navigate("设置") }) { Icon(Icons.Default.Edit, "房间名字与封面") }
    }
    SpaceLinkCard("主题与外观", "调整这位助手的聊天背景、气泡和字体", Icons.Default.Palette, onClick = { navigate("外观") })
    val widgets = RoomPolicy.layout(data)
    for (index in 0 until widgets.length()) {
        val item = widgets.getJSONObject(index)
        val id = item.getString("id")
        fun adjust(change: (JSONArray) -> Unit) {
            val layout = JSONArray(widgets.toString()); change(layout)
            work { room.change(JSONObject().put("widgets", layout), false) }
        }
        if (arranging) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.getBoolean("visible"), { selected -> adjust { it.getJSONObject(index).put("visible", selected) } })
                Text(RoomPolicy.widgets.getValue(id), Modifier.weight(1f))
                IconButton(enabled = index > 0, onClick = { adjust { val previous = it.get(index - 1); it.put(index - 1, it.get(index)); it.put(index, previous) } }) { Icon(Icons.Default.KeyboardArrowUp, "上移组件") }
                IconButton(enabled = index < widgets.length() - 1, onClick = { adjust { val next = it.get(index + 1); it.put(index + 1, it.get(index)); it.put(index, next) } }) { Icon(Icons.Default.KeyboardArrowDown, "下移组件") }
                TextButton(onClick = { adjust { it.getJSONObject(index).put("size", item.getInt("size") % 3 + 1) } }) { Text(listOf("小", "中", "大")[item.getInt("size") - 1]) }
            }
        }
        if (item.getBoolean("visible")) OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().heightIn(min = 90.dp, max = (120 + item.getInt("size") * 70).dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(RoomPolicy.widgets.getValue(id), style = MaterialTheme.typography.titleMedium)
                val count = item.getInt("size") * 2
                when (id) {
                    "diary" -> { if (diaries.isEmpty()) Text("还没有日记。想写的时候再留下。")
                        diaries.take(count).forEach { Text("${it.optString("date")} · ${it.optString("title")}") }
                        FilledTonalButton(onClick = { navigate("日记") }) { Icon(Icons.Default.EditNote, null); Spacer(Modifier.width(6.dp)); Text("打开日记") } }
                    "todo" -> { if (tasks.isEmpty()) Text("暂时没有未完成的打算")
                        tasks.take(count).forEach { Text(it.optString("title")) }
                        FilledTonalButton(onClick = { showTasks = true }) { Icon(Icons.Default.Checklist, null); Spacer(Modifier.width(6.dp)); Text("打开私人打算") } }
                    "reading" -> { Text("${reading.size} 条待读")
                        reading.take(count).forEach { Text(it.optString("title"), maxLines = 2) }
                        FilledTonalButton(onClick = { navigate("阅读角") }) { Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("打开书房") } }
                    "collection" -> { if (entries.isEmpty()) Text("收藏的资料会在这里出现")
                        entries.sortedByDescending { it.optLong("updated") }.take(count).forEach { entry -> Text(entry.optString("title"), maxLines = 2) }
                        FilledTonalButton(onClick = { navigate("资料库") }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("在书房查看资料") } }
                    "dates" -> { val dates = data.optJSONArray("dates")
                        if (dates == null || dates.length() == 0) Text("可以在设置里留下值得记住的日期")
                        else for (i in 0 until dates.length()) { val date = dates.getJSONObject(i); val days = ChronoUnit.DAYS.between(LocalDate.parse(date.getString("date")), LocalDate.now())
                            Text("${date.getString("title")} · ${date.getString("date")} · ${if (days >= 0) "已经${days}天" else "还有${-days}天"}") } }
                }
            }
        }
    }
    if (showTasks) Dialog(onDismissRequest = { showTasks = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                SpaceHeader("私人打算", { showTasks = false })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompanionTodoPanel(CompanionStore(context), "role:${room.role}")
                }
            }
        }
    }
}

@Composable
private fun RoomOptions(room: AssistantRoom, data: JSONObject, work: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var title by remember(data.toString()) { mutableStateOf(data.optString("title")) }
    var subtitle by remember(data.toString()) { mutableStateOf(data.optString("subtitle")) }
    var dateTitle by remember { mutableStateOf("") }; var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var alias by remember { mutableStateOf("") }; var canonical by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(data.toString()) { aliases = room.aliases() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) work {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        room.change(JSONObject().put("cover", uri.toString()), false)
    } }
    OutlinedTextField(title, { title = it }, label = { Text("房间名字") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(subtitle, { subtitle = it }, label = { Text("房间寄语") }, modifier = Modifier.fillMaxWidth())
    Row { Button(onClick = { work { room.change(JSONObject().put("title", title).put("subtitle", subtitle), false) } }) { Text("保存") }
        TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("选择封面") }
        TextButton(onClick = { work { room.change(JSONObject().put("cover", ""), false) } }) { Text("清除封面") } }
    HorizontalDivider()
    fun locks() = RoomPolicy.fields.keys.filter { RoomPolicy.locked(data, it) }.toSet()
    RoomSwitch("允许助手自己布置", data.optBoolean("ai_decorate")) { enabled -> work { room.grants(enabled, data.optBoolean("ai_diary"), locks()) } }
    RoomSwitch("允许助手自主写日记", data.optBoolean("ai_diary")) { enabled -> work { room.grants(data.optBoolean("ai_decorate"), enabled, locks()) } }
    Text("两个开关默认关闭，分别属于当前助手。日记可在聊天或已有唤醒时写下，不为写日记额外启动定时轮询。")
    Text("锁定后，助手不能改这一项；你仍可自己调整。")
    RoomPolicy.fields.forEach { (field, label) -> RoomSwitch("锁定$label", RoomPolicy.locked(data, field)) { enabled ->
        work { room.grants(data.optBoolean("ai_decorate"), data.optBoolean("ai_diary"), if (enabled) locks() + field else locks() - field) }
    } }
    TextButton(onClick = { work { room.restore() } }) { Text("还原上一次房间与外观调整") }
    HorizontalDivider()
    Text("纪念日", style = MaterialTheme.typography.titleMedium)
    val dates = data.optJSONArray("dates") ?: JSONArray()
    for (i in 0 until dates.length()) { val item = dates.getJSONObject(i)
        Row { Text("${item.getString("date")} · ${item.getString("title")}", Modifier.weight(1f)); TextButton(onClick = { work { val updated = JSONArray(dates.toString()); updated.remove(i); room.change(JSONObject().put("dates", updated), false) } }) { Text("移除") } }
    }
    OutlinedTextField(dateTitle, { dateTitle = it }, label = { Text("纪念什么") })
    OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") })
    Button(onClick = { work { LocalDate.parse(date); room.change(JSONObject().put("dates", JSONArray(dates.toString()).put(JSONObject().put("title", dateTitle).put("date", date))), false) } }) { Text("添加纪念日") }
    HorizontalDivider()
    Text("称呼对应表", style = MaterialTheme.typography.titleMedium)
    Text("帮助记忆检索认出昵称，不改变原文。只记录你已确认的对应关系。")
    aliases.keys().forEach { key -> Row { Text("$key → ${aliases.getString(key)}", Modifier.weight(1f)); TextButton(onClick = { work { room.saveAlias(key, null); aliases = room.aliases() } }) { Text("移除") } } }
    OutlinedTextField(alias, { alias = it }, label = { Text("昵称 / 别名") })
    OutlinedTextField(canonical, { canonical = it }, label = { Text("完整称呼") })
    Button(onClick = { work { room.saveAlias(alias, canonical); aliases = room.aliases() } }) { Text("保存对应关系") }
}

@Composable
internal fun RoomSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, change) }
}
