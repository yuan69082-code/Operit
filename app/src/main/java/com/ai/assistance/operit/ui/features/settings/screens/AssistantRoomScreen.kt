package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.core.tasks.TaskBoard
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun AssistantRoomDialog(role: String, chat: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val room = remember(role) { AssistantRoom(context, role) }
    val scope = rememberCoroutineScope()
    val revision by AssistantRoom.revision.collectAsState()
    var data by remember(role) { mutableStateOf(JSONObject()) }
    var name by remember(role) { mutableStateOf("") }
    var tab by remember(role) { mutableStateOf("房间") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
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
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$name · 房间", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("回到聊天") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("房间", "外观", "日记", "阅读角", "设置").forEach { label ->
                        FilterChip(selected = label == tab, onClick = { tab = label; error = "" }, label = { Text(label) })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                key(role, tab) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            "房间" -> RoomHome(room, data, chat, revision, { tab = it }, { work(it) })
                            "外观" -> RoomAppearance(room, { work(it) }, { advanced = true })
                            "日记" -> RoomDiary(room, chat, revision, { work(it) })
                            "阅读角" -> ReadingPanel(role, chat, revision, { work(it) })
                            "设置" -> RoomOptions(room, data, { work(it) })
                        }
                    }
                }
            }
        }
    }
    if (advanced) Dialog(onDismissRequest = { advanced = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
            TextButton(onClick = { advanced = false }) { Text("返回房间") }
            ThemeSettingsScreen()
        } }
    }
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
    var detail by remember { mutableStateOf<JSONObject?>(null) }
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
    Row { TextButton(onClick = { arranging = !arranging }) { Text(if (arranging) "完成布置" else "调整组件") }; TextButton(onClick = { navigate("设置") }) { Text("名字与封面") } }
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
                TextButton(enabled = index > 0, onClick = { adjust { val previous = it.get(index - 1); it.put(index - 1, it.get(index)); it.put(index, previous) } }) { Text("↑") }
                TextButton(enabled = index < widgets.length() - 1, onClick = { adjust { val next = it.get(index + 1); it.put(index + 1, it.get(index)); it.put(index, next) } }) { Text("↓") }
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
                        TextButton(onClick = { navigate("日记") }) { Text("打开日记") } }
                    "todo" -> { if (tasks.isEmpty()) Text("暂时没有未完成的打算")
                        tasks.take(count).forEach { Text(it.optString("title")) }
                        TextButton(onClick = { showTasks = true }) { Text("打开私人待办") } }
                    "reading" -> { Text("${reading.size} 条待读")
                        reading.take(count).forEach { Text(it.optString("title"), maxLines = 2) }
                        TextButton(onClick = { navigate("阅读角") }) { Text("去看看") } }
                    "collection" -> { if (entries.isEmpty()) Text("收藏的资料会在这里出现")
                        entries.sortedByDescending { it.optLong("updated") }.take(count).forEach { entry -> TextButton(onClick = { detail = entry }) { Text(entry.optString("title")) } } }
                    "dates" -> { val dates = data.optJSONArray("dates")
                        if (dates == null || dates.length() == 0) Text("可以在设置里留下值得记住的日期")
                        else for (i in 0 until dates.length()) { val date = dates.getJSONObject(i); val days = ChronoUnit.DAYS.between(LocalDate.parse(date.getString("date")), LocalDate.now())
                            Text("${date.getString("title")} · ${date.getString("date")} · ${if (days >= 0) "已经${days}天" else "还有${-days}天"}") } }
                }
            }
        }
    }
    if (showTasks) AlertDialog(onDismissRequest = { showTasks = false }, title = { Text("私人打算") }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState())) { CompanionTodoPanel(CompanionStore(context), "role:${room.role}") }
    }, confirmButton = { TextButton(onClick = { showTasks = false }) { Text("关闭") } })
    detail?.let { entry -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(entry.optString("title")) }, text = { Text(entry.optString("content"), Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
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

@Composable
private fun RoomAppearance(room: AssistantRoom, work: (suspend () -> Unit) -> Unit, advanced: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { UserPreferencesManager.getInstance(context) }
    val snapshot by remember(room.role) { prefs.observeThemePreferenceSnapshot(characterCardId = room.role) }.collectAsState(initial = null)
    var patch by remember { mutableStateOf(JSONObject()) }
    fun set(key: String, value: Any) { patch = JSONObject(patch.toString()).put(key, value) }
    val base = snapshot?.values ?: return
    val values = RoomTheme.apply(base, patch)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) work {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        set("background_image_uri", uri.toString()); set("use_background_image", true)
    } }
    Text("边调边看", style = MaterialTheme.typography.titleLarge)
    Text("预览满意后保存，仅改变这位助手的私聊外观。字体文件、气泡图片等可在完整编辑器继续调整。")
    RoomPreview(values)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("晨雾" to ("#E4EDF0" to "#F4E5E8"), "纸页" to ("#F7F1E4" to "#DCE6D8"), "夜色" to ("#263B4D" to "#514364")).forEach { (label, colors) ->
            TextButton(onClick = { set("chat_style", "bubble"); set("bubble_ai_bubble_color", colors.first); set("bubble_user_bubble_color", colors.second)
                set("bubble_ai_text_color", if (label == "夜色") "#FFFFFF" else "#24343A"); set("bubble_user_text_color", if (label == "夜色") "#FFFFFF" else "#24343A") }) { Text(label) }
        }
        TextButton(onClick = { patch = JSONObject() }) { Text("撤销未保存调整") }
    }
    listOf("bubble_ai_bubble_color" to "助手气泡", "bubble_user_bubble_color" to "我的气泡").forEach { (field, label) ->
        Text(label)
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("#E4EDF0", "#F4E5E8", "#DCE6D8", "#EFE6F5", "#263B4D", "#FFFFFF").forEach { color ->
            TextButton(onClick = { set("chat_style", "bubble"); set(field, color) }) { Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(Color(android.graphics.Color.parseColor(color)))) }
        } }
    }
    listOf("bubble_ai_system_font_name" to "助手字体", "bubble_user_system_font_name" to "我的字体").forEach { (field, label) ->
        Text(label)
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("default" to "默认", "serif" to "衬线", "sans-serif" to "简洁", "monospace" to "等宽", "cursive" to "手写").forEach { (font, title) ->
            FilterChip(selected = values.string(field) == font, onClick = { set(field, font) }, label = { Text(title) })
        } }
    }
    Text("字号 ${(values.float("font_scale")!! * 100).toInt()}%")
    Slider(values.float("font_scale")!!, { set("font_scale", it) }, valueRange = .8f..1.5f)
    RoomSwitch("助手气泡圆角", values.boolean("bubble_ai_rounded_corners_enabled")!!) { set("bubble_ai_rounded_corners_enabled", it) }
    RoomSwitch("我的气泡圆角", values.boolean("bubble_rounded_corners_enabled")!!) { set("bubble_rounded_corners_enabled", it) }
    Row { TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("聊天背景") }; TextButton(onClick = { set("use_background_image", false) }) { Text("清除背景") } }
    Text("背景透明度 ${(values.float("background_image_opacity")!! * 100).toInt()}%")
    Slider(values.float("background_image_opacity")!!, { set("background_image_opacity", it) })
    Row { Button(onClick = { val save = JSONObject(patch.toString()); work { room.change(JSONObject().put("theme", save), false); patch = JSONObject() } }) { Text("保存外观") }
        TextButton(onClick = advanced) { Text("完整主题编辑器") } }
    TextButton(onClick = { work { room.restore(); patch = JSONObject() } }) { Text("还原上一次保存") }
}

@Composable
private fun RoomPreview(values: ThemePreferenceValues) {
    Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (values.boolean("use_background_image") == true) AsyncImage(values.string("background_image_uri"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = values.float("background_image_opacity")!!)
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            listOf(false to "这里留着我们慢慢说过的话。", true to "嗯，这个样子我喜欢。").forEach { (user, text) ->
                val prefix = if (user) "bubble_user" else "bubble_ai"
                val font = when (values.string(prefix + "_system_font_name")) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive; else -> FontFamily.SansSerif }
                val round = values.boolean(if (user) "bubble_rounded_corners_enabled" else "bubble_ai_rounded_corners_enabled") == true
                Text(text, Modifier.align(if (user) Alignment.End else Alignment.Start).clip(RoundedCornerShape(if (round) 18.dp else 0.dp)).background(values.int(prefix + "_bubble_color")?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer).padding(14.dp),
                    color = values.int(prefix + "_text_color")?.let { Color(it) } ?: MaterialTheme.colorScheme.onSecondaryContainer, fontSize = (15 * values.float("font_scale")!!).sp, fontFamily = font)
            }
        }
    }
}
