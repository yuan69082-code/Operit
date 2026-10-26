package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.CancellationException

/** Module configuration selects an identity; its current conversation is resolved at dispatch. */
@Composable
internal fun AssistantTargetPicker(selected: String?, enabled: Boolean, showExplanation: Boolean = true, onSelected: (String) -> Unit) {
    val context = LocalContext.current
    var cards by remember { mutableStateOf<List<CharacterCard>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { cards = CharacterCardManager.getInstance(context).getAllCharacterCards() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "助手列表读取失败：${failure.message}" }
    }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
            Text(cards.firstOrNull { it.id == selected }?.name ?: if (selected == null) "选择助手" else "助手已删除，请重新选择")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            cards.forEach { card -> DropdownMenuItem(text = { Text(card.name) }, onClick = {
                expanded = false
                onSelected(card.id)
            }) }
        }
    }
    if (showExplanation) Text("只绑定助手，执行时跟随这位助手最近活跃的私聊；换窗口无需重新设置。尚无私聊时等待建立私聊。")
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}
