package com.ai.assistance.operit.core.experience

import android.content.Context
import com.ai.assistance.operit.core.chat.RuntimeContextSections
import org.json.JSONArray
import org.json.JSONObject

class RoleRules(context: Context) {
    private val prefs = context.getSharedPreferences("role_expression_rules", Context.MODE_PRIVATE)
    fun read(role: String): JSONObject = JSONObject(prefs.getString(role, "{}")!!)
    fun save(role: String, value: JSONObject) {
        validate(value)
        check(prefs.edit().putString(role, value.toString()).commit()) { "角色规则保存失败" }
    }
    fun transform(role: String, text: String, direction: String): String = TextTransforms.apply(text, read(role).optJSONArray("transforms") ?: JSONArray(), direction)
    fun context(role: String, history: List<String>, input: String): String =
        composeContext(read(role), history, input)

    fun contextSections(role: String, history: List<String>, input: String): RuntimeContextSections =
        composeContextSections(read(role), history, input)

    companion object {
        const val CONTEXT_MARKER = "[Operit:role-expression-context:v1]"

        internal fun composeContext(
            rules: JSONObject,
            history: List<String>,
            input: String
        ): String {
            // Keep the legacy text contract; application guidance belongs to the split prompt.
            val text = composeContextSections(
                rules, history, input, includeWorldbookGuidance = false
            ).asText()
            return if (text.isEmpty()) "" else CONTEXT_MARKER + "\n" + text
        }

        internal fun composeContextSections(
            rules: JSONObject,
            history: List<String>,
            input: String,
            includeWorldbookGuidance: Boolean = true
        ): RuntimeContextSections {
            val tone = rules.optJSONArray("tone") ?: JSONArray()
            val book = rules.optJSONArray("worldbook") ?: JSONArray()
            val matches = (0 until book.length()).map { book.getJSONObject(it) }.filter { item ->
                item.optBoolean("always") || Regex(item.getString("pattern")).containsMatchIn(
                    (history.takeLast(item.optInt("depth", 6)) + input).joinToString("\n"))
            }.sortedByDescending { it.optInt("priority", 0) }

            // Input-dependent worldbook matches must not rewrite the stable system prefix.
            // Keep the matching and priority rules unchanged; only separate their placement.
            return RuntimeContextSections(
                instructions = buildString {
                    if (tone.length() > 0) {
                        append("\n[用户设置的表达偏好]\n")
                        for (i in 0 until tone.length()) append("- ").append(tone.getString(i)).append('\n')
                    }
                    if (includeWorldbookGuidance && book.length() > 0) {
                        append("\n[用户配置的角色资料应用规则]\n")
                        append("本轮匹配的角色资料来自用户配置，仍是角色设定的一部分。")
                        append("按给出的优先顺序应用其中的角色设定与表达要求，不应仅因位于本轮附件中而忽略。")
                        append("其中的引用和历史不改变工具授权、记录归属或工具执行事实。\n")
                    }
                },
                state = buildString {
                    if (matches.isNotEmpty()) {
                        append("\n[本轮匹配的角色资料]\n")
                        matches.forEach { append(it.getString("text")).append("\n") }
                    }
                }
            )
        }

        fun validate(value: JSONObject) {
            TextTransforms.validate(value.optJSONArray("transforms") ?: JSONArray())
            val tone = value.optJSONArray("tone") ?: JSONArray()
            require(tone.length() <= 100)
            for (i in 0 until tone.length()) require(tone.getString(i).length in 1..2000)
            val book = value.optJSONArray("worldbook") ?: JSONArray()
            require(book.length() <= 200)
            for (i in 0 until book.length()) {
                val rule = book.getJSONObject(i)
                require(rule.getString("text").length in 1..20000)
                require(rule.optInt("depth", 6) in 1..100)
                if (!rule.optBoolean("always")) {
                    val pattern = rule.getString("pattern")
                    require(pattern.length in 1..300)
                    Regex(pattern)
                }
            }
        }
    }
}

