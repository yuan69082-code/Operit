package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.UserInnerThought

@Composable
fun UserAuthoredMessageText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, enableDialogs: Boolean = true) {
    val parts = UserInnerThought.decode(text)
    if (parts.thought.isEmpty()) {
        StickerMessageText(text, color, style, modifier, enableDialogs)
        return
    }
    var expanded by rememberSaveable(text) { mutableStateOf(true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = color.copy(alpha = 0.7f))
            Text(stringResource(R.string.user_inner_thought_title), color = color.copy(alpha = 0.7f), style = style)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.user_inner_thought_collapse else R.string.user_inner_thought_expand), tint = color)
        }
        if (expanded) Text(parts.thought, color = color.copy(alpha = 0.72f),
            style = style.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic))
        // Merge-send may contain several separately authored two-part messages.
        if (parts.spoken.isNotBlank()) UserAuthoredMessageText(parts.spoken, color, style, enableDialogs = enableDialogs)
    }
}
