package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.group.*
import com.ai.assistance.operit.ui.features.settings.screens.AssistantTargetPicker
import com.ai.assistance.operit.ui.features.settings.screens.SpaceHeader
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun GroupMemoryPanel(actor: String?, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { GroupMemories(context) }
    val scope = rememberCoroutineScope()
    val revision by GroupStore.revision.collectAsState()
    var owner by remember(actor) { mutableStateOf(actor) }
    var rows by remember(owner) { mutableStateOf(emptyList<JSONObject>()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf("") }
    var deletable by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var lifecycle by remember { mutableStateOf<JSONObject?>(null) }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = ""; withContext(Dispatchers.IO) { action() } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() } finally { busy = false }
        }
    }
    LaunchedEffect(owner, revision) {
        val selected = owner
        if (selected != null) try { rows = withContext(Dispatchers.IO) { store.list(null, selected) } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message.orEmpty() }
    }
    if (actor == null) AssistantTargetPicker(owner, !busy, showExplanation = false) { owner = it }
    Text("记忆按参与者保存。编辑不改变原聊天；删除只移除这位助手的记忆摘要，新的参与仍会留下新记忆。", style = MaterialTheme.typography.bodySmall)
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (rows.isEmpty()) Text(if (owner == null) "选择一位助手查看他的群记忆" else "还没有保留的群记忆")
    rows.forEach { row ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.getString("title"), style = MaterialTheme.typography.titleMedium)
                Text((if (row.getBoolean("group_exists")) "群仍开放" else "群已结束或删除") +
                    if (row.optBoolean("deletable", true)) " · 可删除记忆" else " · 记忆已保护", style = MaterialTheme.typography.labelMedium)
                Text(if (row.has("note")) row.getString("note") else row.optString("excerpt"), maxLines = 5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        editing = row; note = if (row.has("note")) row.getString("note") else row.optString("excerpt")
                        deletable = row.optBoolean("deletable", true); dirty = false
                    }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("编辑") }
                    IconButton(enabled = !busy && row.getBoolean("chat_available"), onClick = { onOpenChat(row.getString("chat_id")) }) { Icon(Icons.Default.OpenInNew, "查看群历史") }
                    IconButton(enabled = !busy && row.getBoolean("chat_available"), onClick = { lifecycle = row }) { Icon(Icons.Default.PowerSettingsNew, "设置群是否存在") }
                    IconButton(enabled = !busy && row.optBoolean("deletable", true), onClick = { deleting = row }) { Icon(Icons.Default.DeleteOutline, "删除这条记忆") }
                }
            }
        }
    }
    editing?.let { row ->
        fun close() { if (!busy) { if (dirty) discard = true else editing = null } }
        Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 18.dp)) {
                    SpaceHeader("编辑群聊记忆", { close() }, enabled = !busy)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(row.getString("title"), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(note, { note = it; dirty = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 8, label = { Text("这位助手保留的记忆") })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("允许删除这条记忆", Modifier.weight(1f))
                            Switch(deletable, { deletable = it; dirty = true }, enabled = !busy)
                        }
                        Text("参与时间、原始记录与发言归属会保留。", style = MaterialTheme.typography.bodySmall)
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), onClick = {
                        val savedNote = note; val savedDeletable = deletable
                        work { store.save(null, row.getString("owner"), row.getString("chat_id"), savedNote, savedDeletable)
                            withContext(Dispatchers.Main) { dirty = false; editing = null } }
                    }) { Text("保存记忆") }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { discard = false; editing = null; dirty = false }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条群记忆？") },
        text = { Text("不会删除原聊天、其他助手的记忆或群本身。") },
        confirmButton = { TextButton(onClick = { deleting = null; work { store.delete(null, row.getString("owner"), row.getString("chat_id")) } }) { Text("删除记忆") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    lifecycle?.let { row ->
        val exists = row.getBoolean("group_exists")
        AlertDialog(onDismissRequest = { lifecycle = null }, title = { Text(if (exists) "结束这个群？" else "重新开放这个群？") },
            text = { Text(if (exists) "群内发言、排队消息和主动唤醒会停止，历史与记忆保留。" else "恢复群内发言，主动唤醒需要另外开启。已删除的群不能恢复。") },
            confirmButton = { TextButton(onClick = { lifecycle = null; work { GroupLifecycle.setExists(context, row.getString("chat_id"), null, !exists) } }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { lifecycle = null }) { Text("取消") } })
    }
}
