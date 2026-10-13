package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.group.*
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun GroupMutePanel(chatId: String, members: List<CharacterCard>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moderation = remember(context) { GroupModeration(context) }
    val revision by GroupStore.revision.collectAsState()
    var selected by remember(chatId) { mutableStateOf(emptySet<String>()) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    fun change(all: Boolean, muted: Boolean) {
        scope.launch {
            try {
                moderation.change(chatId, null, members.map { it.id }.toSet(), selected.toList(), all, muted)
                error = null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.message }
        }
    }
    Text("选中一位或多位成员后操作。游戏规则也会限制公开发言，游戏行动仍从游戏面板提交。")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        TextButton(onClick = { change(true, true) }) { Text("全员禁言") }
        TextButton(onClick = { change(true, false) }) { Text("解除全部手动禁言") }
    }
    (listOf("user" to "你") + members.map { it.id to it.name }).forEach { (id, name) ->
        val manual = remember(chatId, id, revision) { moderation.manuallyMuted(chatId, id) }
        val game = remember(chatId, id, revision) { moderation.gameMuted(chatId, id) }
        Row {
            Checkbox(id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
            Text(name + when { manual -> " · 已禁言"; game -> " · 游戏规则禁言"; else -> " · 可发言" }, Modifier.padding(top = 12.dp))
        }
    }
    Row {
        TextButton(enabled = selected.isNotEmpty(), onClick = { change(false, true) }) { Text("禁言选中成员") }
        TextButton(enabled = selected.isNotEmpty(), onClick = { change(false, false) }) { Text("解除选中成员") }
    }
}

@Composable
fun GroupTasksPanel(chatId: String, members: List<CharacterCard>) {
    val context = LocalContext.current
    val board = remember(chatId) { com.ai.assistance.operit.core.tasks.TaskBoard(context, chatId, true) }
    TaskBoardPanel(board, members.associate { it.id to it.name })
}

@Composable
fun GroupMemberModels(members: List<CharacterCard>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cards = remember { CharacterCardManager.getInstance(context) }
    val configs = remember { ModelConfigManager(context) }
    val summaries by configs.configSummariesFlow.collectAsState(initial = emptyList())
    var choosing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("每位成员使用自己的模型。这里切换后，其私聊也使用所选模型；其他成员不受影响。")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    members.forEach { member ->
        val card by cards.getCharacterCardFlow(member.id).collectAsState(initial = member)
        val config = summaries.firstOrNull { it.id == card.chatModelConfigId }
        TextButton(onClick = { choosing = if (choosing == member.id) null else member.id }) {
            Text("${member.name}：${config?.let { getModelList(it.modelName).getOrNull(card.chatModelIndex) } ?: "未配置模型"}")
        }
        if (choosing == member.id) summaries.forEach { configItem ->
            getModelList(configItem.modelName).forEachIndexed { index, model ->
                TextButton(onClick = { scope.launch {
                    try {
                        cards.selectChatModel(member.id, configItem.id, index)
                        choosing = null; error = null
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) { error = failure.message }
                } }) { Text("${configItem.name} · $model") }
            }
        }
    }
}
