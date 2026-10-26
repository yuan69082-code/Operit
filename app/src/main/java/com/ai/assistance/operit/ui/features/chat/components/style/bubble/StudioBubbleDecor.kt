package com.ai.assistance.operit.ui.features.chat.components.style.bubble

import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.ai.assistance.operit.ui.theme.LocalThemePreferenceSnapshot

@Composable
internal fun studioBubbleShape(user: Boolean, rounded: Boolean): CornerBasedShape {
    val values = LocalThemePreferenceSnapshot.current.values
    val radius = if (rounded) values.float("studio_bubble_corner") ?: 20f else 0f
    // Liquid glass consumes corner radii; draw the decorative tail outside that shape.
    return if (user) RoundedCornerShape(radius.dp, (radius / 5).dp, radius.dp, radius.dp)
        else RoundedCornerShape((radius / 5).dp, radius.dp, radius.dp, radius.dp)
}

internal fun Modifier.studioSticker(user: Boolean, background: Color): Modifier = composed {
    val values = LocalThemePreferenceSnapshot.current.values
    val text = values.string("studio_bubble_sticker").orEmpty().take(8)
    val tail = values.boolean("studio_bubble_tail") == true
    if (text.isEmpty() && !tail) this else this.drawWithContent {
        drawContent()
        if (tail) {
            val t = 6.dp.toPx(); val y = if (text.isEmpty()) t else 18.dp.toPx() + t
            val edge = if (user) size.width else 0f
            drawPath(Path().apply { moveTo(edge, y); lineTo(edge + if (user) t else -t, y); lineTo(edge, y + 2 * t); close() }, background)
        }
        if (text.isNotEmpty()) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 16.dp.toPx() }
            drawContext.canvas.nativeCanvas.drawText(text, (size.width - paint.measureText(text) - 4.dp.toPx()).coerceAtLeast(0f), 16.dp.toPx(), paint)
        }
    }.padding(top = if (text.isEmpty()) 0.dp else 18.dp)
}
