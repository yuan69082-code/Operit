package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R

@Composable
fun CreateGroupChatDialog(cards: List<CharacterCard>, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createdGroupId by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.assistant_create_group)) },
        text = { Column {
            OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy && createdGroupId == null,
                label = { Text(stringResource(R.string.assistant_group_name)) }, singleLine = true)
            Text(stringResource(R.string.assistant_group_explanation),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(cards, key = { it.id }) { card ->
                    Row {
                        Checkbox(checked = card.id in selected, enabled = !busy && createdGroupId == null,
                            onCheckedChange = { checked -> selected = if (checked) selected + card.id else selected - card.id })
                        Text(card.name, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && selected.size >= 2, onClick = {
            scope.launch {
                busy = true
                try {
                    val members = cards.filter { it.id in selected }.mapIndexed { index, card -> GroupMemberConfig(card.id, index) }
                    val id = createdGroupId ?: CharacterGroupCardManager.getInstance(context)
                        .createCharacterGroupCard(CharacterGroupCard(id = "", name = name.trim(), members = members))
                    createdGroupId = id
                    ActivePromptManager.getInstance(context).setActivePrompt(ActivePrompt.CharacterGroup(id))
                    onCreated()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppLogger.e("CreateGroupChat", "Group creation failed", failure); error = failure.message }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.assistant_create_and_open)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
