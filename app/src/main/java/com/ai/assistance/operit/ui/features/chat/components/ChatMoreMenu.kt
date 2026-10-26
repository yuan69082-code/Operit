package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.settings.screens.AssistantRoomDialog
import com.ai.assistance.operit.ui.features.settings.screens.SpaceHeader

@Composable
fun ChatMoreMenu(chatId: String?, actor: String?, groupId: String?, viewModel: ChatViewModel, onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var page by remember(chatId) { mutableStateOf<String?>(null) }
    val groupTabs = listOf("群设置", "成员", "禁言", "群待办", "游戏", "活动", "群状态", "@")
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "聊天更多操作") }
        DropdownMenu(expanded, { expanded = false }) {
            if (actor != null && groupId == null) DropdownMenuItem(text = { Text("房间") }, leadingIcon = { Icon(Icons.Default.Home, null) },
                onClick = { expanded = false; page = "房间" })
            DropdownMenuItem(text = { Text("关系网") }, leadingIcon = { Icon(Icons.Default.PeopleOutline, null) },
                enabled = chatId != null, onClick = { expanded = false; page = "关系网" })
            DropdownMenuItem(text = { Text("群聊记忆") }, leadingIcon = { Icon(Icons.Default.History, null) },
                enabled = chatId != null, onClick = { expanded = false; page = "群聊记忆" })
            DropdownMenuItem(text = { Text("编辑助手与群组") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onEdit() })
            if (groupId != null) {
                HorizontalDivider()
                groupTabs.forEach { title -> DropdownMenuItem(text = { Text(if (title == "@") "@ 群成员" else title) },
                    onClick = { expanded = false; page = title }) }
            }
        }
    }
    if (chatId != null && groupId != null) GroupControls(chatId, groupId, viewModel,
        showToolbar = false, requestedTab = page?.takeIf { it in groupTabs }, onClose = { page = null })
    if (page == "房间" && actor != null && chatId != null) AssistantRoomDialog(actor, chatId) { page = null }
    if (page in listOf("关系网", "群聊记忆") && chatId != null) {
        Dialog(onDismissRequest = { page = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(horizontal = 18.dp)) {
                    SpaceHeader(page.orEmpty(), { page = null })
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (page == "关系网") RelationshipEditor(chatId)
                        else GroupMemoryPanel(actor.takeIf { groupId == null }) { id -> viewModel.switchChat(id); page = null }
                    }
                }
            }
        }
    }
}
