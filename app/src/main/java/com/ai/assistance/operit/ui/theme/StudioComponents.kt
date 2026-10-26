package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Named native styling targets, not arbitrary CSS selectors. */
object StudioComponents {
    val targets = listOf("send_button", "attachment", "tool_receipt", "history_search", "room_tabs")
    fun validate(text: String) {
        val styles = JSONObject(text)
        for (key in styles.keys()) {
            require(key in targets) { "未知原生组件：$key" }
            val style = styles.getJSONObject(key)
            require(style.keys().asSequence().all { it == "color" || it == "corner" })
            if (style.has("color")) style.getInt("color")
            if (style.has("corner")) require(style.getDouble("corner") in 0.0..40.0)
        }
    }
    @Composable fun color(target: String, original: Color): Color {
        val style = JSONObject(LocalThemePreferenceSnapshot.current.values.string("studio_component_styles") ?: "{}").optJSONObject(target)
        return if (style?.has("color") == true) Color(style.getInt("color")) else original
    }
    @Composable fun shape(target: String, original: Shape): Shape {
        val style = JSONObject(LocalThemePreferenceSnapshot.current.values.string("studio_component_styles") ?: "{}").optJSONObject(target)
        return if (style?.has("corner") == true) RoundedCornerShape(style.getDouble("corner").toFloat().dp) else original
    }
}
