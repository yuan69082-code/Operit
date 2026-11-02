package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.util.UserInnerThought
import org.json.JSONObject

/** Human activation and assistant activation are independent; revocation clears both. */
class HumanThoughtAccess(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("human_thought_access", Context.MODE_PRIVATE)
    val allowed get() = preferences.getBoolean("allowed", false)
    val name get() = preferences.getString("name", "园园")!!
    fun userEnabled(role: String?) = !role.isNullOrBlank() && preferences.getBoolean("user_enabled:$role", false)
    fun enabled(role: String?) = allowed && !role.isNullOrBlank() && (userEnabled(role) || preferences.getBoolean("enabled:$role", false))
    fun chooseByUser(role: String, value: Boolean) = synchronized(lock) {
        require(role.isNotBlank())
        val edit = preferences.edit().putBoolean("user_enabled:$role", value)
        if (value) edit.putBoolean("allowed", true)
        edit.apply()
    }

    fun authorize(value: Boolean) = synchronized(lock) {
        val edit = preferences.edit().putBoolean("allowed", value)
        // Reauthorization must not silently resurrect either actor's old activation.
        if (!value) preferences.all.keys.filter { it.startsWith("enabled:") || it.startsWith("user_enabled:") }.forEach { edit.remove(it) }
        edit.apply()
    }
    fun choose(role: String, value: Boolean) = synchronized(lock) {
        require(role.isNotBlank())
        check(!value || allowed) { "用户尚未允许 AI 开启人类思考链" }
        preferences.edit().putBoolean("enabled:$role", value).apply()
    }
    fun rename(value: String) {
        require(value.isNotBlank() && value.length <= 40 && '\n' !in value && '\r' !in value) { "称呼请填写1到40个字符，不能换行" }
        preferences.edit().putString("name", value.trim()).apply()
    }
    fun status(role: String?) = JSONObject().put("allowed", allowed).put("enabled", enabled(role)).put("user_enabled", userEnabled(role)).put("ai_enabled", role != null && preferences.getBoolean("enabled:$role", false)).put("name", name)
    fun project(content: String, role: String?) = UserInnerThought.forModel(content, enabled(role))
    fun submit(content: String, active: Boolean): String {
        val parts = UserInnerThought.decode(content)
        require(!active || parts.spoken.isNotBlank()) { "请填写正文回复；人类思考链可以留空" }
        return if (active) UserInnerThought.encode(parts.thought, parts.spoken, includeEmpty = true)
        else UserInnerThought.withoutThoughts(content)
    }
    suspend fun activeIn(context: Context, prompt: ActivePrompt): Boolean = when (prompt) {
        is ActivePrompt.CharacterCard -> enabled(prompt.id)
        is ActivePrompt.CharacterGroup -> CharacterGroupCardManager.getInstance(context)
            .getCharacterGroupCard(prompt.id)?.members?.any { enabled(it.characterCardId) } == true
    }
    suspend fun activeInComposer(context: Context) = activeIn(context, ActivePromptManager.getInstance(context).getActivePrompt())
    companion object { private val lock = Any() }
}
