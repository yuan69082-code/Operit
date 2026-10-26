package com.ai.assistance.operit.core.experience

import org.json.JSONArray

/** Protect protocol blocks, attachments and code; rules operate only on plain conversation prose. */
object TextTransforms {
    private val protected = Regex("(?s)```.*?```|<([A-Za-z_][\\w:-]*)(?:\\s[^>]*)?>.*?</\\1\\s*>|<[^>]+>")
    fun apply(text: String, rules: JSONArray, direction: String): String {
        val active = (0 until rules.length()).map { rules.getJSONObject(it) }.filter {
            it.optBoolean("enabled", true) && it.getString("direction") in setOf(direction, "both")
        }
        if (active.isEmpty()) return text
        fun transform(part: String): String = active.fold(part) { value, rule ->
            Regex(rule.getString("pattern")).replace(value, rule.getString("replacement"))
        }
        return buildString {
            var offset = 0
            protected.findAll(text).forEach { match ->
                append(transform(text.substring(offset, match.range.first))); append(match.value)
                offset = match.range.last + 1
            }
            append(transform(text.substring(offset)))
        }
    }
    fun validate(rules: JSONArray) {
        require(rules.length() <= 50) { "最多50条文字替换规则" }
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            require(rule.getString("direction") in setOf("input", "output", "both"))
            require(rule.getString("pattern").length in 1..300)
            require(rule.getString("replacement").length <= 2000)
            val regex = Regex(rule.getString("pattern"))
            // Validate replacement references even when a sample does not match the expression.
            val groups = regex.toPattern().matcher("").groupCount()
            Regex("(?<!\\\\)\\$(\\d+)").findAll(rule.getString("replacement")).forEach {
                require(it.groupValues[1].toInt() <= groups) { "替换内容引用了不存在的分组" }
            }
        }
    }
}
