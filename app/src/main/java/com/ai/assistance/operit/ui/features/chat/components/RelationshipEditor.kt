package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.group.*
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelationshipEditor(chatId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val network = remember { RelationshipNetwork(context) }
    val revision by GroupStore.revision.collectAsState()
    val entries = remember(revision) { network.all() }
    var cards by remember { mutableStateOf(emptyList<CharacterCard>()) }
    var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }; var opinion by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }; var experiences by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) { cards = CharacterCardManager.getInstance(context).getAllCharacterCards() }
    fun name(id: String) = cards.firstOrNull { it.id == id }?.name ?: "已删除助手 ($id)"
    fun work(action: suspend () -> Unit) { scope.launch {
        try { error = null; action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    } }
    Text("每条记录有明确方向：A 对 B 的看法不等于 B 对 A 的看法。你可以编辑全部；助手修改自己的记录，主助手可全局管理。")
    entries.forEach { item ->
        TextButton(onClick = { from = item.getString("from"); to = item.getString("to"); relation = item.getString("relation"); opinion = item.optString("opinion"); progress = item.optString("progress"); experiences = item.optString("experiences") }) {
            Text("${name(item.getString("from"))} → ${name(item.getString("to"))}：${item.optString("relation")}")
        }
        Text("更新：" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.optLong("updated_at"))) + " · 来源：${item.optString("source_chat")}", style = MaterialTheme.typography.labelSmall)
    }
    Text("记录属于谁")
    FlowRow { cards.forEach { card -> FilterChip(selected = from == card.id, onClick = { from = card.id }, label = { Text(card.name) }) } }
    Text("对哪位助手")
    FlowRow { cards.filter { it.id != from }.forEach { card -> FilterChip(selected = to == card.id, onClick = { to = card.id }, label = { Text(card.name) }) } }
    OutlinedTextField(relation, { relation = it }, label = { Text("关系") })
    OutlinedTextField(opinion, { opinion = it }, label = { Text("看法") })
    OutlinedTextField(progress, { progress = it }, label = { Text("目前进展") })
    OutlinedTextField(experiences, { experiences = it }, label = { Text("共同经历，保留参与者、时间和来源") }, minLines = 3)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        TextButton(onClick = { work { network.save(null, from, to, relation, opinion, progress, experiences, chatId) } }) { Text("保存") }
        TextButton(onClick = { work { network.delete(null, from, to) } }) { Text("删除这条关系") }
    }
}

@Composable
fun PrivateSocialControls(chatId: String, actor: String?, viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by remember(chatId) { mutableStateOf("") }
    val revision by GroupStore.revision.collectAsState()
    val store = remember { GroupStore(context) }
    val links = remember(actor, revision) { if (actor == null) emptyList() else store.ids().mapNotNull { id ->
        store.read(id).optJSONObject("seen")?.optJSONObject(actor)?.let { id to it.optString("excerpt") }
    } }
    Row(modifier) {
        TextButton(onClick = { tab = "关系网" }) { Text("关系网") }
        if (links.isNotEmpty()) TextButton(onClick = { tab = "参与过的群聊" }) { Text("群聊记忆 (${links.size})") }
    }
    if (tab.isNotEmpty()) AlertDialog(onDismissRequest = { tab = "" }, title = { Text(tab) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == "关系网") RelationshipEditor(chatId)
            else links.forEach { (id, excerpt) ->
                var title by remember(id) { mutableStateOf(id) }
                LaunchedEffect(id) { title = ChatHistoryManager.getInstance(context).getChatMetadata(id)?.title ?: id }
                TextButton(onClick = { viewModel.switchChat(id); tab = "" }) { Text(title) }
                Text(excerpt, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = { tab = "" }) { Text("关闭") } })
}
