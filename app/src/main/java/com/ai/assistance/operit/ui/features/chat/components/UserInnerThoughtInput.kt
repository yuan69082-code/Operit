package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.UserInnerThought

/** Both editors share the existing draft, including its clear/send/queue/chat-switch lifecycle. */
class UserInnerThoughtDraft(
    private val value: TextFieldValue,
    private val onChange: (TextFieldValue) -> Unit,
) {
    private val parts = UserInnerThought.decode(value.text)
    val thought: String get() = parts.thought
    val spoken: TextFieldValue get() = TextFieldValue(
        text = parts.spoken,
        selection = shift(value.selection, -parts.spokenOffset, parts.spoken.length),
        composition = value.composition?.let { shift(it, -parts.spokenOffset, parts.spoken.length) },
    )

    fun changeSpoken(next: TextFieldValue) {
        val encoded = UserInnerThought.encode(thought, next.text)
        val offset = encoded.length - next.text.length
        onChange(TextFieldValue(encoded, shift(next.selection, offset, encoded.length),
            next.composition?.let { shift(it, offset, encoded.length) }))
    }

    fun changeThought(next: String) {
        val encoded = UserInnerThought.encode(next, parts.spoken)
        val offset = encoded.length - parts.spoken.length
        onChange(TextFieldValue(encoded, shift(spoken.selection, offset, encoded.length)))
    }

    private fun shift(range: TextRange, offset: Int, length: Int) = TextRange(
        (range.start + offset).coerceIn(0, length), (range.end + offset).coerceIn(0, length),
    )
}

@Composable
fun UserInnerThoughtInput(draft: UserInnerThoughtDraft, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val access = androidx.compose.runtime.remember { com.ai.assistance.operit.core.companion.HumanThoughtAccess(context) }
    var revision by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(access) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        access.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { access.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val prompt by com.ai.assistance.operit.data.preferences.ActivePromptManager.getInstance(context)
        .activePromptFlow.collectAsState(initial = null)
    var active by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(prompt, revision) {
        active = prompt?.let { access.activeIn(context, it) } == true
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var editedName by rememberSaveable { mutableStateOf("") }
    var error by androidx.compose.runtime.remember { mutableStateOf("") }
    // Read preferences after each listener notification; UI follows the assistant's tool choice.
    val allowed = androidx.compose.runtime.remember(revision) { access.allowed }
    val name = androidx.compose.runtime.remember(revision) { access.name }
    Column(modifier) {
        FilterChip(
            selected = active,
            onClick = { editedName = name; settings = true; error = "" },
            label = { Text(if (!allowed) "人类思考链 · 未授权" else if (active) "人类思考链 · 已开启" else "人类思考链 · AI未开启") },
        )
        if (active) {
            OutlinedTextField(
                value = draft.thought,
                onValueChange = draft::changeThought,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("人类思考链 · " + name) },
                placeholder = { Text("未说出口的想法；可留空") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                ),
                minLines = 1,
                maxLines = 4,
            )
            Text("正文回复 · 必填", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (settings) androidx.compose.material3.AlertDialog(
        onDismissRequest = { settings = false },
        title = { Text("人类思考链") },
        text = {
            Column {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("允许AI开启人类思考链", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = allowed, onCheckedChange = { access.authorize(it) })
                }
                Text("允许后，由每位AI选择开启或关闭。关闭总开关会撤销所有AI的开启状态。")
                OutlinedTextField(value = editedName, onValueChange = { editedName = it },
                    label = { Text("思考者称呼（你和AI都可以修改）") }, singleLine = true)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            try { access.rename(editedName); settings = false }
            catch (failure: IllegalArgumentException) { error = failure.message.orEmpty() }
        }) { Text("保存称呼") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { settings = false }) { Text("关闭") } },
    )
}
