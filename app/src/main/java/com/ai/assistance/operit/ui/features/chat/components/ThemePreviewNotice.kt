package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.experience.ThemeStudio
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun ThemePreviewNotice(chatId: String) {
    val context = LocalContext.current
    val studio = remember { ThemeStudio(context) }
    val revision by ThemeStudio.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var target by remember(chatId) { mutableStateOf<ActivePrompt?>(null) }
    var state by remember(chatId) { mutableStateOf(JSONObject()) }
    var error by remember(chatId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(chatId, revision) {
        val chat = ChatHistoryManager.getInstance(context).getChatMetadata(chatId)
        target = chat?.characterGroupId?.let { ActivePrompt.CharacterGroup(it) }
            ?: chat?.characterCardId?.let { ActivePrompt.CharacterCard(it) }
        target?.let { state = withContext(Dispatchers.IO) { studio.library(it) } }
    }
    val preview = state.optJSONObject("preview")
    val sessions = state.optJSONArray("sessions")
    if (preview == null && (sessions == null || sessions.length() == 0)) return
    Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(12.dp)) {
        if (preview != null) {
            Text("正在试穿：${preview.optString("name")}")
            Row { listOf(true to "保留", false to "取消").forEach { (accept, label) ->
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try { studio.settle(requireNotNull(target), accept); error = "" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message.orEmpty() }
                        finally { busy = false }
                    }
                }) { Text(label) }
            } }
        } else if (sessions != null) {
            val last = sessions.getJSONObject(sessions.length() - 1)
            Text("主题 ${last.getString("name")} · ${when (last.getString("status")) { "applied" -> "已应用"; "cancelled" -> "已取消"; "superseded" -> "已被新试穿替代"; else -> "试穿中" }}")
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    } }
}
