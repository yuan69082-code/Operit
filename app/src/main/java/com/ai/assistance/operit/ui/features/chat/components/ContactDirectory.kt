package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.ui.common.rememberLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Uses the same stable identities, sort preference and activation path as the original selector. */
@Composable
fun ContactDirectory(onOpen: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val cardsManager = remember { CharacterCardManager.getInstance(context) }
    val groupsManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activeManager = remember { ActivePromptManager.getInstance(context) }
    val ids by cardsManager.characterCardListFlow.collectAsState(initial = emptyList())
    val groups by groupsManager.allCharacterGroupCardsFlow.collectAsState(initial = emptyList())
    val active by activeManager.activePromptFlow.collectAsState(initial = null)
    var cards by remember { mutableStateOf(emptyList<CharacterCard>()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var createGroup by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val sort = rememberLocal("ModelPromptsSettingsScreen.CharacterCardTab.sortOption", "DEFAULT")
    val scope = rememberCoroutineScope()
    LaunchedEffect(ids) {
        if (ids.isEmpty()) { cards = emptyList(); return@LaunchedEffect }
        try {
            combine(ids.map { cardsManager.getCharacterCardFlow(it) }) { it.toList() }
                .collect { cards = it; error = "" }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "好友读取失败：${e.message}" }
    }
    fun select(target: ActivePrompt) {
        if (busy) return
        busy = true
        scope.launch {
            try { activeManager.setActivePrompt(target); onOpen() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "打开失败：${e.message}" } finally { busy = false }
        }
    }
    val sorted = when (sort.value) {
        "NAME_ASC" -> cards.sortedBy { it.name.lowercase() }
        "CREATED_DESC" -> cards.sortedByDescending { it.updatedAt }
        else -> cards
    }.filter { it.name.contains(query, true) }
    val foundGroups = groups.filter { it.name.contains(query, true) }
    Surface(Modifier.fillMaxSize(), color = if (com.ai.assistance.operit.ui.theme.LocalThemePreferenceSnapshot.current.useBackgroundImage) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("聊天", style = MaterialTheme.typography.headlineLarge)
                    Text("${cards.size} 位好友 · ${groups.size} 个群聊", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, "好友排序") }
                    DropdownMenu(sortOpen, { sortOpen = false }) {
                        listOf("DEFAULT" to "默认顺序", "NAME_ASC" to "按名称", "CREATED_DESC" to "最近更新").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { sort.value = value; sortOpen = false })
                        }
                    }
                }
                IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Default.PersonAdd, "新增或管理助手") }
                FilledTonalIconButton(onClick = { createGroup = true }, enabled = !busy) { Icon(Icons.Default.GroupAdd, "新建群聊") }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索好友或群聊") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                listOf("全部", "好友", "群聊").forEach { name -> FilterChip(filter == name, { filter = name }, label = { Text(name) }) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (filter != "群聊") {
                    item { Text("好友", style = MaterialTheme.typography.titleSmall) }
                    items(sorted, key = { "friend:${it.id}" }) { card ->
                        CharacterItem(card, active == ActivePrompt.CharacterCard(card.id)) { select(ActivePrompt.CharacterCard(card.id)) }
                    }
                    if (sorted.isEmpty()) item { Text("没有符合条件的好友", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (filter != "好友") {
                    item { Text("群聊", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp)) }
                    items(foundGroups, key = { "group:${it.id}" }) { group ->
                        if (group.ended) Text("已结束 · 保留历史", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CharacterGroupItem(group, active == ActivePrompt.CharacterGroup(group.id)) { select(ActivePrompt.CharacterGroup(group.id)) }
                    }
                    if (foundGroups.isEmpty()) item { Text("没有符合条件的群聊", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (createGroup) CreateGroupChatDialog(cards, { createGroup = false }, { createGroup = false; onOpen() })
}
