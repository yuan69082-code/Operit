package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Column(modifier) {
        OutlinedTextField(
            value = draft.thought,
            onValueChange = draft::changeThought,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.user_inner_thought_title)) },
            placeholder = { Text(stringResource(R.string.user_inner_thought_hint)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
            ),
            minLines = 1,
            maxLines = 4,
        )
        Text(stringResource(R.string.user_spoken_text_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}
