package com.ai.assistance.operit.ui.features.settings.screens.theme

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.operit.core.experience.ThemeStudio
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun ThemeStudioEntry(target: ActivePrompt, enabled: Boolean, onClosed: () -> Unit) {
    var open by remember(target) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("主题工坊 · 生成 / 试穿 / 皮肤库") }
    if (open) ThemeStudioDialog(target) { open = false; onClosed() }
}

@Composable
private fun ThemeStudioDialog(target: ActivePrompt, close: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val studio = remember { ThemeStudio(context) }
    val scope = rememberCoroutineScope()
    var state by remember(target) { mutableStateOf(studio.library(target)) }
    var name by remember { mutableStateOf("我的皮肤") }
    var hue by remember { mutableFloatStateOf(210f) }
    var seed by remember { mutableIntStateOf(0) }
    var colorCount by remember { mutableFloatStateOf(3f) }
    var mood by remember { mutableFloatStateOf(.5f) }
    var starOpacity by remember { mutableFloatStateOf(0f) }
    var starSize by remember { mutableFloatStateOf(3f) }
    var starGlow by remember { mutableFloatStateOf(8f) }
    var warmth by remember { mutableFloatStateOf(.5f) }
    var bubbleRadius by remember { mutableFloatStateOf(20f) }
    var sticker by remember { mutableStateOf("") }
    var tail by remember { mutableStateOf(false) }
    var patchText by remember { mutableStateOf("{\"ints\":{\"custom_primary_color\":-12627531}}") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf("") }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); state = studio.library(target) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLogger.e("ThemeStudio", "Theme action failed", e); status = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run {
            val text = studio.exportSkin(target, name)
            requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) }
            status = "皮肤已导出"
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val text = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            studio.importSkin(target, text); status = "已导入试穿；返回聊天查看效果，再保留或取消"
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("主题工坊", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = close) { Text("查看界面") }
                }
                Text("生成后立即试穿当前角色或群聊的原生主题。未确认的试穿保留取消入口，关闭面板不会替你确认。")
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("皮肤名称") }, modifier = Modifier.fillMaxWidth())
                Text("色相 ${hue.toInt()}°")
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..359f, enabled = !busy)
                Text("配色数量 "+colorCount.toInt()); Slider(colorCount, { colorCount = it }, valueRange = 1f..6f, steps = 4)
                Text("氛围浓度"); Slider(mood, { mood = it })
                Text("星光透明度"); Slider(starOpacity, { starOpacity = it })
                Text("星光大小"); Slider(starSize, { starSize = it }, valueRange = 0f..10f)
                Text("星光光晕"); Slider(starGlow, { starGlow = it }, valueRange = 0f..30f)
                Text("星光暖度"); Slider(warmth, { warmth = it })
                OutlinedTextField(seed.toString(), { it.toIntOrNull()?.let { n -> seed = n } }, label = { Text("生成种子（相同参数得到相同图案）") })
                ThemeStudio.families.chunked(3).forEach { row ->
                    Row {
                        row.forEach { family -> TextButton(enabled = !busy, onClick = {
                            run { studio.generatePreview(target, family, hue, seed, name, colorCount.toInt(), mood, starOpacity, starSize, starGlow, warmth); status = "正在试穿 $family" }
                        }) { Text(family) } }
                    }
                }
                if (state.has("preview")) {
                    Text("试穿中：${state.getJSONObject("preview").optString("name")}")
                    Row {
                        Button(enabled = !busy, onClick = { run { studio.settle(target, true); status = "已保留主题" } }) { Text("保留") }
                        TextButton(enabled = !busy, onClick = { run { studio.settle(target, false); status = "已取消试穿" } }) { Text("取消试穿") }
                    }
                }
                Row {
                    TextButton(enabled = !busy, onClick = { run { studio.saveSkin(target, name); status = "已存入皮肤库" } }) { Text("保存皮肤") }
                    TextButton(enabled = !busy, onClick = { export.launch("operit-theme-${System.currentTimeMillis()}.json") }) { Text("导出") }
                    TextButton(enabled = !busy, onClick = { run { clipboard.setText(androidx.compose.ui.text.AnnotatedString(studio.exportSkin(target, name))); status = "已复制主题" } }) { Text("复制") }
                    TextButton(enabled = !busy, onClick = { import.launch(arrayOf("application/json", "text/plain")) }) { Text("导入") }
                }
                listOf("skins" to "皮肤库", "history" to "历史版本").forEach { (key, title) ->
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val rows = state.optJSONArray(key)
                    if (rows == null || rows.length() == 0) Text("暂无记录")
                    else for (i in rows.length() - 1 downTo 0) {
                        val row = rows.getJSONObject(i)
                        val id = row.getString("id")
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(enabled = !busy, onClick = { run { studio.restore(target, id, key == "history"); status = "已载入试穿" } }, modifier = Modifier.weight(1f)) { Text(row.getString("name")) }
                            if (key == "skins") {
                                TextButton(enabled = !busy, onClick = { run { studio.rename(target, id, name) } }) { Text("改名") }
                                TextButton(enabled = !busy, onClick = { run { studio.delete(target, id) } }) { Text("删除") }
                            }
                        }
                    }
                }
                Text("气泡圆角 "+bubbleRadius.toInt()); Slider(bubbleRadius, { bubbleRadius = it }, valueRange = 0f..40f)
                Row { Checkbox(tail, { tail = it }); Text("气泡尾巴") }
                OutlinedTextField(sticker, { sticker = it.take(8) }, label = { Text("气泡装饰贴纸（短文字或emoji）") })
                TextButton(enabled = !busy, onClick = { run {
                    studio.preview(target, JSONObject().put("floats", JSONObject().put("studio_bubble_corner", bubbleRadius))
                        .put("booleans", JSONObject().put("studio_bubble_tail", tail)).put("strings", JSONObject().put("studio_bubble_sticker", sticker)), "气泡装饰")
                } }) { Text("试穿气泡装饰") }
                OutlinedTextField(patchText, { patchText = it }, label = { Text("局部原生字段补丁 JSON") })
                TextButton(enabled = !busy, onClick = { run { studio.preview(target, JSONObject(patchText), name); status = "已试穿局部修改" } }) { Text("试穿局部修改") }
                Text("先检查参数，再修改对应背景、顶部栏、气泡、文字、输入区字段。未提供的字段保持当前值。")
                TextButton(enabled = !busy, onClick = { run { inspection = ThemeStudio.encode(studio.current(target)).toString(2) } }) { Text("检查当前主题参数") }
                if (inspection.isNotEmpty()) Text(inspection, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status)
            }
        }
    }
}
