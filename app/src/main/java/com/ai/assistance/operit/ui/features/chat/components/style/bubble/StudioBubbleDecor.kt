package com.ai.assistance.operit.ui.features.chat.components.style.bubble

import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.ai.assistance.operit.ui.theme.LocalThemePreferenceSnapshot

@Composable
internal fun studioBubbleShape(user: Boolean, rounded: Boolean): Shape {
    val values = LocalThemePreferenceSnapshot.current.values
    val radius = if (rounded) values.float("studio_bubble_corner") ?: 20f else 0f
    val tail = values.boolean("studio_bubble_tail") == true
    if (!tail) return if (user) RoundedCornerShape(radius.dp, (radius / 5).dp, radius.dp, radius.dp)
        else RoundedCornerShape((radius / 5).dp, radius.dp, radius.dp, radius.dp)
    return object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val r = with(density) { radius.dp.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2)
            val t = with(density) { 6.dp.toPx() }
            val path = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(r, r)))
                val edge = if (user) size.width else 0f
                moveTo(edge, t); lineTo(edge + if (user) t else -t, t); lineTo(edge, t * 3); close()
            }
            return Outline.Generic(path)
        }
    }
}

internal fun Modifier.studioSticker(): Modifier = composed {
    val text = LocalThemePreferenceSnapshot.current.values.string("studio_bubble_sticker").orEmpty().take(8)
    if (text.isEmpty()) this else this.drawWithContent {
        drawContent()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 16.dp.toPx() }
        drawContext.canvas.nativeCanvas.drawText(text, (size.width - paint.measureText(text) - 4.dp.toPx()).coerceAtLeast(0f), 16.dp.toPx(), paint)
    }.padding(top = 18.dp)
}
