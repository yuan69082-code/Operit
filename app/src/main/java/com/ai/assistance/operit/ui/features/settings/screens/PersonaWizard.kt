package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Creates a new role without rewriting the current role or its independently evolving state. */
@Composable
internal fun PersonaWizardEntry() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("用向导创建新助手") }
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("朋友") }
    var tone by remember { mutableStateOf("自然随意") }
    var detail by remember { mutableStateOf("简洁") }
    var background by remember { mutableStateOf("") }
    var boundaries by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("新助手向导") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it; preview = null }, label = { Text("名字") })
            Text("与你的关系")
            listOf("朋友", "创作搭档", "学习伙伴", "工作助手").forEach { option ->
                FilterChip(relationship == option, { relationship = option; preview = null }, { Text(option) })
            }
            Text("说话方式")
            listOf("自然随意", "温柔细致", "直率清楚", "幽默活泼").forEach { option ->
                FilterChip(tone == option, { tone = option; preview = null }, { Text(option) })
            }
            Row { listOf("简洁", "充分展开").forEach { option ->
                FilterChip(detail == option, { detail = option; preview = null }, { Text(option) })
            } }
            OutlinedTextField(background, { background = it; preview = null }, label = { Text("背景、喜好与共同经历") })
            OutlinedTextField(boundaries, { boundaries = it; preview = null }, label = { Text("你希望遵守的边界") })
            TextButton(enabled = name.isNotBlank(), onClick = {
                preview = "你的名字是${name.trim()}，你与用户的关系是${relationship}。\n表达方式：${tone}；回答篇幅：${detail}。\n背景与喜好：\n${background}\n相处边界：\n${boundaries}"
            }) { Text("组装并预览设定") }
            preview?.let { prompt -> OutlinedTextField(prompt, { preview = it }, label = { Text("完整设定（可修改）") }) }
            if (status.isNotBlank()) Text(status)
        }
    }, confirmButton = { TextButton(enabled = !busy && !preview.isNullOrBlank() && name.isNotBlank(), onClick = {
        busy = true
        scope.launch {
            try {
                CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard(
                    id = "", name = name.trim(), characterSetting = requireNotNull(preview), description = relationship,
                ))
                status = "已创建，可在助手列表选择。"; preview = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "创建失败：${e.message}" }
            finally { busy = false }
        }
    }) { Text(if (busy) "保存中" else "创建新助手") } }, dismissButton = {
        TextButton(enabled = !busy, onClick = { open = false }) { Text("关闭") }
    })
}
