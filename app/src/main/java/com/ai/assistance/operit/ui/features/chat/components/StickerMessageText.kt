package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.ai.assistance.operit.ui.common.markdown.MarkdownImageRenderer
import com.ai.assistance.operit.util.StickerProtocol

@Composable
fun StickerMessageText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, enableDialogs: Boolean = true) {
    if (!StickerProtocol.pattern.containsMatchIn(text)) {
        Text(text, color = color, style = style, modifier = modifier)
        return
    }
    Column(modifier) {
        var position = 0
        StickerProtocol.pattern.findAll(text).forEach { match ->
            val before = text.substring(position, match.range.first).trim()
            if (before.isNotEmpty()) Text(before, color = color, style = style)
            MarkdownImageRenderer(match.value, maxImageHeight = 160, enableDialogs = enableDialogs)
            position = match.range.last + 1
        }
        val after = text.substring(position).trim()
        if (after.isNotEmpty()) Text(after, color = color, style = style)
    }
}
