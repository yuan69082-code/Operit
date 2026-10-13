package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Explicit assistant targets throughout; the active UI or selected model is never an owner. */
class AssistantRoom(private val context: Context, val role: String) {
    init { require(role.isNotBlank()) }
    private val store = CompanionStore(context)
    private val scope = "room:$role"
    private val preferences by lazy { UserPreferencesManager.getInstance(context) }
    fun read() = store.read(scope).put("owner", role)

    suspend fun grants(decorate: Boolean, diary: Boolean, locks: Set<String>) = mutex.withLock {
        require(locks.all { it in RoomPolicy.fields })
        store.update(scope) { it.put("ai_decorate", decorate).put("ai_diary", diary).put("locks", JSONArray(locks.toList())) }
        val cards = CharacterCardManager.getInstance(context)
        if (decorate) cards.setToolSelected(role, "builtin", "companion_room", true)
        if (diary) cards.setToolSelected(role, "builtin", "companion_diary", true)
        revision.update { it + 1 }
    }

    suspend fun change(patch: JSONObject, ai: Boolean): JSONObject = mutex.withLock {
        val before = read()
        RoomPolicy.authorize(before, patch, ai); RoomPolicy.validate(patch)
        val theme = preferences.resolveThemePreferenceSnapshot(characterCardId = role).values
        val nextTheme = patch.optJSONObject("theme")?.let { RoomTheme.apply(theme, it) }
        // Save a user-visible restore point before either write; never discard a user's existing theme.
        store.update("room-undo:$role") { it.put("room", before).put("theme", RoomTheme.encode(theme)) }
        if (nextTheme != null) preferences.replaceThemeForPrompt(ActivePrompt.CharacterCard(role), nextTheme)
        val data = store.update(scope) { RoomPolicy.apply(it, patch, ai, System.currentTimeMillis()) }
        revision.update { it + 1 }
        data.put("owner", role)
    }

    suspend fun restore() = mutex.withLock {
        val point = store.read("room-undo:$role")
        check(point.has("room") && point.has("theme")) { "还没有可还原的外观" }
        preferences.replaceThemeForPrompt(ActivePrompt.CharacterCard(role), RoomTheme.decode(point.getJSONObject("theme")))
        store.update(scope) { current ->
            // Restoring appearance must not restore older grants or unlock user-locked settings.
            val old = point.getJSONObject("room")
            RoomPolicy.fields.keys.forEach { key -> current.remove(key); if (old.has(key)) current.put(key, old.get(key)) }
        }
        revision.update { it + 1 }
    }

    fun diaries(): List<JSONObject> = store.read("diary-index:$role").optJSONArray("items").objects().sortedByDescending { it.optString("date") }
    fun diary(id: String): JSONObject {
        require(diaries().any { it.getString("id") == id }) { "日记不属于当前助手或不存在" }
        return store.read("diary:$role:$id")
    }

    suspend fun saveDiary(id: String?, date: String, title: String, text: String, chat: String, ai: Boolean, expected: Long?): JSONObject = mutex.withLock {
        if (ai) check(read().optBoolean("ai_diary", false)) { "用户尚未开启助手自主写日记" }
        LocalDate.parse(date)
        require(title.length in 1..160 && text.length in 1..12000) { "标题须为1–160字，日记须为1–12000字" }
        val entryId = id ?: UUID.randomUUID().toString()
        val old = if (id == null) null else diary(id)
        if (old != null) check(expected == old.optLong("version")) { "日记已更新，请重新打开后再编辑" }
        if (ai && old != null) check(old.optString("author") == role) { "不能将用户写下的记录改作自己的日记" }
        val now = System.currentTimeMillis()
        val entry = JSONObject().put("id", entryId).put("owner", role).put("author", old?.getString("author") ?: if (ai) role else "user")
            .put("date", date).put("title", title).put("text", text).put("created", old?.getLong("created") ?: now)
            .put("updated", now).put("edited_by", if (ai) role else "user").put("source_chat", old?.getString("source_chat") ?: chat)
            .put("version", (old?.optLong("version") ?: 0L) + 1L)
        // Each revision remains separately addressable; a user edit never impersonates the original author.
        if (old != null) store.update("diary:$role:$entryId:v${old.getLong("version")}") { target -> old.keys().forEach { target.put(it, old.get(it)) } }
        store.update("diary:$role:$entryId") { target -> entry.keys().forEach { target.put(it, entry.get(it)) } }
        store.update("diary-index:$role") { data ->
            val items = data.optJSONArray("items").objects().filter { it.getString("id") != entryId }.toMutableList()
            items.add(JSONObject().put("id", entryId).put("date", date).put("title", title).put("author", entry.getString("author")).put("preview", text.take(200)))
            data.put("items", JSONArray(items))
        }
        revision.update { it + 1 }; entry
    }

    suspend fun aliases(): JSONObject {
        val card = CharacterCardManager.getInstance(context).getCharacterCard(role)
        return store.read("memory-alias:${requireNotNull(card.memoryProfileId) { "助手尚未分配记忆库" }}")
    }
    suspend fun saveAlias(alias: String, name: String?) = mutex.withLock {
        require(alias.length in 2..48 && (name == null || name.length in 2..48)) { "别名与全名须为2–48字" }
        val card = CharacterCardManager.getInstance(context).getCharacterCard(role)
        store.update("memory-alias:${requireNotNull(card.memoryProfileId)}") {
            if (name == null) it.remove(alias) else it.put(alias, name)
            require(it.length() <= 200) { "称呼表最多200项" }
        }
        revision.update { it + 1 }
    }

    fun prompt(): String {
        val data = read()
        return buildString {
            if (data.optBoolean("ai_decorate")) append("你可以用 companion_room 查看并布置自己的房间和聊天外观；用户锁定的项目保持不变。\n")
            if (data.optBoolean("ai_diary")) append("你可以用 companion_diary 写自己的日记或回看某日，不要求每天写，也不必写任务汇报。先看已有条目，避免重复。记录实际经历、当时想法与未证实的猜测时分清来源；查看他人记录不等于亲历。\n")
        }
    }

    companion object {
        private val mutex = Mutex()
        val revision = MutableStateFlow(0L)
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

object RoomTheme {
    private val colors = setOf("bubble_user_bubble_color", "bubble_ai_bubble_color", "bubble_user_text_color", "bubble_ai_text_color", "custom_primary_color")
    private val bools = setOf("bubble_rounded_corners_enabled", "bubble_ai_rounded_corners_enabled", "use_custom_colors", "use_system_theme", "use_background_image", "use_background_blur", "bubble_user_use_custom_font", "bubble_ai_use_custom_font", "bubble_user_bubble_liquid_glass", "bubble_ai_bubble_liquid_glass", "bubble_user_bubble_water_glass", "bubble_ai_bubble_water_glass")
    private val fonts = setOf("default", "serif", "sans-serif", "monospace", "cursive")
    fun apply(base: ThemePreferenceValues, patch: JSONObject): ThemePreferenceValues {
        var values = base
        patch.keys().forEach { key ->
            values = when {
                key in colors -> values.withInt(key, android.graphics.Color.parseColor(patch.getString(key)))
                key in bools -> values.withBoolean(key, patch.getBoolean(key))
                key == "font_scale" -> values.withFloat(key, patch.getDouble(key).also { require(it in 0.8..1.5) }.toFloat())
                key == "background_image_opacity" -> values.withFloat(key, patch.getDouble(key).also { require(it in 0.0..1.0) }.toFloat())
                key == "background_blur_radius" -> values.withFloat(key, patch.getDouble(key).also { require(it in 0.0..30.0) }.toFloat())
                key in setOf("bubble_ai_system_font_name", "bubble_user_system_font_name") -> {
                    val name = patch.getString(key); require(name in fonts) { "请选择受支持的系统字体" }
                    val prefix = key.removeSuffix("system_font_name")
                    values.withString(key, name).withString(prefix + "font_type", "system").withBoolean(prefix + "use_custom_font", true)
                }
                key == "background_image_uri" -> values.withString(key, patch.getString(key)).withString("background_media_type", "image")
                key == "chat_style" -> values.withString(key, patch.getString(key).also { require(it in setOf("bubble", "cursor")) })
                else -> error("不支持的外观项目：$key")
            }
        }
        return values
    }
    fun encode(values: ThemePreferenceValues) = JSONObject().put("strings", JSONObject(values.strings)).put("booleans", JSONObject(values.booleans)).put("ints", JSONObject(values.ints)).put("floats", JSONObject(values.floats))
    fun decode(data: JSONObject): ThemePreferenceValues {
        val s = data.getJSONObject("strings"); val b = data.getJSONObject("booleans"); val i = data.getJSONObject("ints"); val f = data.getJSONObject("floats")
        return ThemePreferenceValues(s.keys().asSequence().associateWith { s.getString(it) }, b.keys().asSequence().associateWith { b.getBoolean(it) },
            i.keys().asSequence().associateWith { i.getInt(it) }, f.keys().asSequence().associateWith { f.getDouble(it).toFloat() })
    }
}
