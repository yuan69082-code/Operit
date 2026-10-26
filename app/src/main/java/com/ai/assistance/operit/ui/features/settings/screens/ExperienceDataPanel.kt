package com.ai.assistance.operit.ui.features.settings.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.experience.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.*

@Composable
internal fun ExperienceDataEntry() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<KelivoImport.Plan?>(null) }
    val importer = remember { KelivoImport(context) }
    fun work(block: suspend () -> Unit) { if (!busy) scope.launch {
        busy = true
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { report = "未完成：${e.message}。已成功写入的条目会保留，重试时按来源标记识别。" }
        finally { busy = false }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { plan?.archive?.delete(); plan = importer.inspect(uri); report = requireNotNull(plan).summary }
    }
    TextButton(onClick = { open = true }) { Text("数据检查 / Kelivo 迁移") }
    if (!open) return
    AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("数据检查与迁移") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("检查只读取数据，不自动删除或修复。迁移只添加有来源标记的数据。")
            Button(enabled = !busy, onClick = { work {
                report = withContext(Dispatchers.IO) {
                    val issues = mutableListOf<String>()
                    val cards = CharacterCardManager.getInstance(context).getAllCharacterCards()
                    val ids = cards.map { it.id }.toSet()
                    val histories = ChatHistoryManager.getInstance(context).getAllChatMetadata()
                    val chats = histories.associateBy { it.id }
                    val models = ModelConfigManager(context)
                    for (card in cards) {
                        card.chatModelConfigId?.let { if (models.getModelConfig(it) == null) issues.add("助手 ${card.name} 引用了缺失的模型配置 $it") }
                        val theme = ThemeStudio(context).current(ActivePrompt.CharacterCard(card.id))
                        for (field in listOf("background_image_uri", "bubble_user_image_uri", "bubble_ai_image_uri")) {
                            theme.string(field)?.takeIf { it.isNotBlank() }?.let { value ->
                                try { requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(value))).use { it.read() } }
                                catch (e: Exception) { issues.add("助手 ${card.name} 的 $field 无法读取：$value") }
                            }
                        }
                    }
                    for (chat in histories) if (chat.characterCardId != null && chat.characterCardId !in ids) issues.add("会话 ${chat.title} 的助手不存在：${chat.characterCardId}")
                    val collections = CollectionStore(context).list()
                    for (item in collections) {
                        if (!item.getString("role").startsWith("group:") && item.getString("role") !in ids) issues.add("收藏 ${item.getString("title")} 的助手已删除")
                        val chatId = item.getString("chat")
                        if (chatId !in chats) issues.add("收藏 ${item.getString("title")} 的原会话已删除，副本仍存在")
                        else if (ChatHistoryManager.getInstance(context).loadChatMessagesFromTimestamp(chatId, item.getLong("message")).none { it.timestamp == item.getLong("message") }) issues.add("收藏 ${item.getString("title")} 的原消息已删除")
                        if (item.getBoolean("toolEnabled") && !item.getJSONObject("files").has("tool.js")) issues.add("收藏 ${item.getString("title")} 已启用工具但缺少 tool.js")
                    }
                    "检查 ${cards.size} 位助手、${histories.size} 个会话、${collections.size} 个收藏。\n" + if (issues.isEmpty()) "上述检查未发现问题。" else issues.joinToString("\n\n")
                }
            } }) { Text("检查引用、所属助手与主题素材") }
            OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("选择 Kelivo ZIP 备份") }
            if (plan != null) Button(enabled = !busy, onClick = { work {
                report = importer.apply(requireNotNull(plan)); plan?.archive?.delete(); plan = null
            } }) { Text("按上方说明导入") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(report)
            TextButton(enabled = report.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("复制报告") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("关闭") } })
}
