package com.ai.assistance.operit.core.experience

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RoleRules(context: Context) {
    private val prefs = context.getSharedPreferences("role_expression_rules", Context.MODE_PRIVATE)
    fun read(role: String): JSONObject = JSONObject(prefs.getString(role, "{}")!!)
    fun save(role: String, value: JSONObject) {
        validate(value)
        check(prefs.edit().putString(role, value.toString()).commit()) { "角色规则保存失败" }
    }
    fun context(role: String, history: List<String>, input: String): String {
        val rules = read(role)
        val tone = rules.optJSONArray("tone") ?: JSONArray()
        val book = rules.optJSONArray("worldbook") ?: JSONArray()
        val matches = (0 until book.length()).map { book.getJSONObject(it) }.filter { item ->
            item.optBoolean("always") || Regex(item.getString("pattern")).containsMatchIn(
                (history.takeLast(item.optInt("depth", 6)) + input).joinToString("\n"))
        }.sortedByDescending { it.optInt("priority", 0) }
        return buildString {
            if (tone.length() > 0) {
                append("\n[用户设置的表达偏好]\n")
                for (i in 0 until tone.length()) append("- ").append(tone.getString(i)).append('\n')
            }
            if (matches.isNotEmpty()) {
                append("\n[本轮匹配的角色资料]\n")
                matches.forEach { append(it.getString("text")).append("\n") }
            }
        }
    }
    companion object {
        fun validate(value: JSONObject) {
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
