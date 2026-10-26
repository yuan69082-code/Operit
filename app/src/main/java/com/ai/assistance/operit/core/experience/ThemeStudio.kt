package com.ai.assistance.operit.core.experience

import android.content.Context
import android.graphics.Color
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uses existing native theme fields: every accepted patch has an actual rendering consumer. */
class ThemeStudio(private val context: Context) {
    private val storage = context.getSharedPreferences("native_theme_studio", Context.MODE_PRIVATE)
    private val preferences = UserPreferencesManager.getInstance(context)
    private val active = ActivePromptManager.getInstance(context)

    private fun key(target: ActivePrompt): String = when (target) {
        is ActivePrompt.CharacterCard -> "role:${target.id}"
        is ActivePrompt.CharacterGroup -> "group:${target.id}"
    }

    suspend fun generatePreview(target: ActivePrompt, family: String, hue: Float, seed: Int, name: String,
        colorCount: Int = 3, mood: Float = .5f, starOpacity: Float = 0f, starSize: Float = 3f, starGlow: Float = 8f, warmth: Float = .5f): JSONObject {
        val patch = generate(family, hue, seed)
        val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ThemeTexture.create(context, family, hue, seed, colorCount, mood, starOpacity, starSize, starGlow, warmth)
        }
        patch.getJSONObject("strings").put("background_image_uri", uri).put("background_media_type", "image")
        patch.getJSONObject("booleans").put("use_background_image", true)
        patch.getJSONObject("floats").put("background_image_opacity", 1f)
        return preview(target, patch, name)
    }

    suspend fun current(target: ActivePrompt): ThemePreferenceValues = when (target) {
        is ActivePrompt.CharacterCard -> preferences.resolveThemePreferenceSnapshot(characterCardId = target.id).values
        is ActivePrompt.CharacterGroup -> preferences.resolveThemePreferenceSnapshot(characterGroupId = target.id).values
    }

    fun library(target: ActivePrompt): JSONObject = JSONObject(storage.getString(key(target), "{}")!!)

    private fun save(target: ActivePrompt, state: JSONObject) {
        check(storage.edit().putString(key(target), state.toString()).commit()) { "主题库保存失败" }
        revision.value++
    }

    suspend fun preview(target: ActivePrompt, changes: JSONObject, name: String): JSONObject = mutex.withLock {
        val before = current(target)
        val next = patch(before, changes)
        val state = library(target)
        if (!state.has("preview")) state.put("preview", JSONObject().put("original", encode(before)))
        val sessions = state.optJSONArray("sessions") ?: JSONArray()
        if (state.getJSONObject("preview").has("id")) {
            val previousId = state.getJSONObject("preview").getString("id")
            for (i in 0 until sessions.length()) if (sessions.getJSONObject(i).getString("id") == previousId) sessions.getJSONObject(i).put("status", "superseded")
        }
        val id = UUID.randomUUID().toString()
        sessions.put(JSONObject().put("id", id).put("name", name).put("status", "preview").put("time", System.currentTimeMillis()))
        state.put("sessions", sessions)
        state.getJSONObject("preview").put("id", id).put("name", name).put("proposed", encode(next))
        // Persist original before applying so an interrupted process does not lose the cancel target.
        save(target, state)
        active.commitThemeDraft(target, next)
        JSONObject().put("status", "preview").put("name", name).put("values", encode(next))
    }

    suspend fun settle(target: ActivePrompt, accept: Boolean): JSONObject = mutex.withLock {
        val state = library(target)
        val preview = state.getJSONObject("preview")
        val proposed = decode(preview.getJSONObject("proposed"))
        check(current(target) == proposed) { "主题已被其他操作修改，请重新试穿，避免覆盖新修改" }
        if (accept) {
            val history = state.optJSONArray("history") ?: JSONArray()
            history.put(record("试穿前", preview.getJSONObject("original")))
            state.put("history", history)
        } else {
            active.commitThemeDraft(target, decode(preview.getJSONObject("original")))
        }
        val sessions = state.optJSONArray("sessions")
        if (sessions != null) for (i in 0 until sessions.length()) {
            val session = sessions.getJSONObject(i)
            if (session.getString("id") == preview.optString("id")) session.put("status", if (accept) "applied" else "cancelled")
        }
        state.remove("preview")
        save(target, state)
        JSONObject().put("status", if (accept) "applied" else "cancelled")
    }

    suspend fun saveSkin(target: ActivePrompt, name: String): JSONObject = mutex.withLock {
        require(name.isNotBlank()) { "请填写皮肤名称" }
        val state = library(target)
        val skin = record(name, encode(current(target)))
        val skins = state.optJSONArray("skins") ?: JSONArray()
        skins.put(skin)
        state.put("skins", skins)
        save(target, state)
        skin
    }

    suspend fun restore(target: ActivePrompt, id: String, history: Boolean): JSONObject {
        val rows = library(target).getJSONArray(if (history) "history" else "skins")
        val skin = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == id }
        return preview(target, skin.getJSONObject("values"), skin.getString("name"))
    }

    suspend fun importSkin(target: ActivePrompt, text: String): JSONObject {
        val document = JSONObject(text)
        require(document.getInt("schema") == 1) { "不支持的皮肤格式" }
        val values = document.getJSONObject("values")
        val assets = document.optJSONObject("assets")
        if (assets != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (field in assets.keys()) {
                require(field in setOf("background_image_uri", "bubble_user_image_uri", "bubble_ai_image_uri"))
                val bytes = android.util.Base64.decode(assets.getString(field), android.util.Base64.DEFAULT)
                require(bytes.size <= 8_000_000) { "皮肤图片过大" }
                val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "皮肤图片无效" }
                try {
                    val folder = java.io.File(context.filesDir, "theme_studio_assets").apply { mkdirs() }
                    val file = java.io.File(folder, UUID.randomUUID().toString() + ".png")
                    file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                    values.getJSONObject("strings").put(field, android.net.Uri.fromFile(file).toString())
                } finally { bitmap.recycle() }
            }
        }
        return preview(target, values, document.getString("name"))
    }

    suspend fun exportSkin(target: ActivePrompt, name: String): String {
        val values = current(target)
        val assets = JSONObject()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (field in listOf("background_image_uri", "bubble_user_image_uri", "bubble_ai_image_uri")) {
                val uri = values.string(field)?.takeIf { it.isNotBlank() } ?: continue
                val bytes = requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(uri))).use { it.readBytes() }
                require(bytes.size <= 8_000_000) { "皮肤图片过大" }
                assets.put(field, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
        }
        return JSONObject().put("schema", 1).put("name", name).put("values", encode(values)).put("assets", assets).toString(2)
    }

    suspend fun rename(target: ActivePrompt, id: String, name: String) = mutex.withLock {
        require(name.isNotBlank())
            val state = library(target)
            val rows = state.getJSONArray("skins")
            (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == id }.put("name", name)
            save(target, state)
    }

    suspend fun delete(target: ActivePrompt, id: String) = mutex.withLock {
            val state = library(target)
            val rows = state.getJSONArray("skins")
            val kept = JSONArray()
            for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("id") != id) kept.put(rows.getJSONObject(i))
            state.put("skins", kept)
            save(target, state)
    }

    companion object {
        val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
        private val mutex = Mutex()
        val families = listOf("雾玻璃", "纸张", "糖果", "墨色", "织物", "暗夜")
        private fun record(name: String, values: JSONObject) = JSONObject().put("id", UUID.randomUUID().toString())
            .put("name", name).put("time", System.currentTimeMillis()).put("values", values)

        fun encode(values: ThemePreferenceValues): JSONObject = JSONObject()
            .put("strings", JSONObject(values.strings)).put("booleans", JSONObject(values.booleans))
            .put("ints", JSONObject(values.ints)).put("floats", JSONObject(values.floats))

        fun decode(value: JSONObject): ThemePreferenceValues {
            fun <T> entries(section: String, read: (JSONObject, String) -> T): Map<String, T> {
                val obj = value.optJSONObject(section) ?: return emptyMap()
                return obj.keys().asSequence().associateWith { read(obj, it) }
            }
            return ThemePreferenceValues(entries("strings") { o, k -> o.getString(k) },
                entries("booleans") { o, k -> o.getBoolean(k) }, entries("ints") { o, k -> o.getInt(k) },
                entries("floats") { o, k -> o.getDouble(k).toFloat() })
        }

        private val colors = setOf("custom_primary_color", "custom_secondary_color", "custom_app_bar_color",
            "custom_status_bar_color", "custom_navigation_drawer_background_color", "custom_navigation_drawer_accent_color",
            "cursor_user_bubble_color", "bubble_user_bubble_color", "bubble_ai_bubble_color", "bubble_user_text_color", "bubble_ai_text_color")

        fun patch(base: ThemePreferenceValues, document: JSONObject): ThemePreferenceValues {
            val change = decode(document)
            change.strings["studio_component_styles"]?.let(com.ai.assistance.operit.ui.theme.StudioComponents::validate)
            val defaults = ThemePreferenceValues.defaultVisual()
            require(change.strings.keys.all { it in defaults.strings || it in base.strings || it in setOf("background_image_uri", "bubble_user_image_uri", "bubble_ai_image_uri") }) { "未知主题文字字段" }
            require(change.booleans.keys.all { it in defaults.booleans }) { "未知主题开关" }
            require(change.ints.keys.all { it in colors || it in base.ints }) { "未知主题数值字段" }
            require(change.floats.keys.all { it in defaults.floats }) { "未知主题尺寸字段" }
            require(change.floats.values.all { it.isFinite() && it >= 0f && it <= 100f }) { "主题尺寸超出范围" }
            for ((name, value) in change.floats) {
                if (name == "font_scale") require(value in .5f..3f) { "字体缩放须为0.5到3" }
                if (name.endsWith("opacity") || name.contains("repeat_")) require(value in 0f..1f) { "透明度和分割比例须为0到1" }
                if (name == "studio_bubble_corner") require(value in 0f..40f)
            }
            require(change.strings["studio_bubble_sticker"].orEmpty().length <= 8) { "贴纸文字过长" }
            return ThemePreferenceValues(base.strings + change.strings, base.booleans + change.booleans,
                base.ints + change.ints, base.floats + change.floats)
        }

        fun generate(family: String, hue: Float, seed: Int): JSONObject {
            require(family in families)
            require(hue.isFinite())
            val dark = family == "暗夜" || family == "墨色"
            val glass = family == "雾玻璃"
            val saturation = when (family) { "糖果" -> .48f; "墨色" -> .08f; "织物" -> .23f; else -> .18f }
            fun color(offset: Float, value: Float) = Color.HSVToColor(floatArrayOf(((hue + offset) % 360 + 360) % 360, saturation, value))
            val accent = color(0f, if (dark) .85f else .62f)
            val surface = color(18f, if (dark) .18f else .96f)
            val text = if (dark) Color.rgb(239, 239, 242) else Color.rgb(35, 35, 42)
            return encode(ThemePreferenceValues(
                strings = mapOf("theme_mode" to if (dark) "dark" else "light", "chat_style" to UserPreferencesManager.CHAT_STYLE_BUBBLE),
                booleans = mapOf("use_system_theme" to false, "use_custom_colors" to true,
                    "use_custom_app_bar_color" to true, "use_custom_navigation_drawer_background_color" to true,
                    "cursor_user_bubble_follow_theme" to false, "chat_input_water_glass" to glass,
                    "bubble_user_bubble_water_glass" to glass, "bubble_ai_bubble_water_glass" to glass,
                    "bubble_user_use_image" to false, "bubble_ai_use_image" to false),
                ints = mapOf("custom_primary_color" to accent, "custom_secondary_color" to color(35f, .7f),
                    "custom_app_bar_color" to surface, "custom_navigation_drawer_background_color" to surface,
                    "bubble_user_bubble_color" to color((seed % 31).toFloat(), if (dark) .32f else .86f),
                    "cursor_user_bubble_color" to surface, "bubble_ai_bubble_color" to surface,
                    "bubble_user_text_color" to text, "bubble_ai_text_color" to text),
                floats = mapOf("bubble_content_padding_left" to 14f, "bubble_content_padding_right" to 14f,
                    "bubble_ai_content_padding_left" to 14f, "bubble_ai_content_padding_right" to 14f)))
        }
    }
}
