package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallController

/** Same draft and delivery queue in the full call screen and the service overlay. */
@Composable
fun VoiceCallTextInput(
    call: VoiceCallController,
    onEditingChanged: (Boolean) -> Unit = {},
) {
    var expanded by remember(call) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val enabled = call.isConnected && call.isRunning &&
        call.phase != VoiceCallController.Phase.ERROR && call.phase != VoiceCallController.Phase.ENDED
    DisposableEffect(Unit) {
        onDispose { onEditingChanged(false) }
    }
    Column(Modifier.fillMaxWidth()) {
        if (!expanded) {
            TextButton(onClick = { expanded = true; onEditingChanged(true) }, enabled = enabled) {
                Text("打字说话")
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = call.typedDraft,
                    onValueChange = call::updateTypedDraft,
                    modifier = Modifier.weight(1f),
                    label = { Text("打字给对方") },
                    placeholder = { Text("他仍会用语音回复") },
                    enabled = enabled,
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { call.sendTypedDraft() }),
                )
                IconButton(onClick = { call.sendTypedDraft() }, enabled = enabled && call.typedDraft.isNotBlank()) {
                    Icon(Icons.Default.Send, contentDescription = "发送通话文字")
                }
                IconButton(onClick = {
                    keyboard?.hide()
                    focus.clearFocus()
                    expanded = false
                    onEditingChanged(false)
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "收起打字，通话继续")
                }
            }
        }
        if (call.pendingTypedCount > 0) {
            Text("已排队，等待本轮结束 · ${call.pendingTypedCount} 条",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
