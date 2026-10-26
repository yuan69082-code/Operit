package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.toSize
import com.ai.assistance.operit.data.model.ActivePrompt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only mounted, measured nodes are reported. Configuration alone is not render evidence. */
object RenderInspection {
    val targets = listOf("app_background", "user_bubble", "ai_bubble", "send_button", "attachment", "tool_receipt", "history_search", "room_tabs")
    private val nodes = mutableMapOf<String, Pair<String, () -> JSONObject?>>()
    @Synchronized fun register(id: String, owner: String, read: () -> JSONObject?) { nodes[id] = owner to read }
    @Synchronized fun remove(id: String) { nodes.remove(id) }
    @Synchronized fun inspect(owner: String): JSONObject {
        val measured = nodes.values.filter { it.first == owner }.mapNotNull { it.second() }
        val present = measured.map { it.getString("target") }.toSet()
        return JSONObject().put("capturedAt", System.currentTimeMillis()).put("owner", owner)
            .put("nodes", JSONArray(measured)).put("notMounted", JSONArray(targets.filter { it !in present }))
            .put("note", "屏幕坐标单位为px；颜色为实际传入绘制的ARGB，透明色仍受底层背景影响。仅检查已挂载区域，未挂载不代表主题失败；不推断图片、玻璃特效或遮挡后的像素颜色。")
    }
    fun owner(target: ActivePrompt): String = when (target) {
        is ActivePrompt.CharacterCard -> "character_card:${target.id}"
        is ActivePrompt.CharacterGroup -> "character_group:${target.id}"
    }
}

fun Modifier.inspectRender(target: String, background: Color, foreground: Color = Color.Unspecified,
    shape: Shape? = null, details: String = ""): Modifier = composed {
    val snapshot = LocalThemePreferenceSnapshot.current
    val owner = "${snapshot.source}:${snapshot.sourceId}"
    val id = remember { UUID.randomUUID().toString() }
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Properties update even when a color-only theme change does not cause another layout pass.
    val properties by rememberUpdatedState(Triple(background, foreground, details))
    val currentShape by rememberUpdatedState(shape)
    var coordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    DisposableEffect(id, owner, view, density, direction) {
        RenderInspection.register(id, owner) {
            val c = coordinates
            if (c == null || !c.isAttached || !view.isShown) null else {
                val rect = c.boundsInWindow()
                val colors = properties
                val outline = currentShape?.createOutline(c.size.toSize(), direction, density)
                JSONObject().put("target", target).put("instance", id)
                    .put("focusedWindow", view.hasWindowFocus())
                    .put("boundsPx", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                    .put("visibleArea", rect.width > 0 && rect.height > 0)
                    .put("density", density.density).put("fontScale", density.fontScale)
                    .put("backgroundArgb", colors.first.toArgb())
                    .put("foregroundArgb", if (colors.second == Color.Unspecified) JSONObject.NULL else colors.second.toArgb())
                    .put("shape", when (outline) {
                        is Outline.Rounded -> JSONObject().put("kind", "rounded").put("radiiPx", JSONArray(listOf(outline.roundRect.topLeftCornerRadius.x, outline.roundRect.topRightCornerRadius.x, outline.roundRect.bottomRightCornerRadius.x, outline.roundRect.bottomLeftCornerRadius.x)))
                        is Outline.Rectangle -> JSONObject().put("kind", "rectangle")
                        is Outline.Generic -> JSONObject().put("kind", "path").put("bounds", outline.path.getBounds().toString())
                        null -> JSONObject.NULL
                    }).put("details", colors.third)
            }
        }
        onDispose { RenderInspection.remove(id) }
    }
    this.onGloballyPositioned { coordinates = it }
}
