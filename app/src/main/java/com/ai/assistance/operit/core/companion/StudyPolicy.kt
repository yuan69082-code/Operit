package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Offsets refer to UTF-16 in an immutable chapter revision, matching Compose TextRange. */
internal object StudyPolicy {
    val fonts = setOf("hand", "serif", "sans", "mono")
    fun anchor(body: String, start: Int, end: Int, quote: String) {
        require(start >= 0 && end > start && end <= body.length) { "请选择有效的原文范围" }
        require(body.substring(start, end) == quote) { "选中文字与此版本原文不符，请重新选择" }
        require(!(start > 0 && Character.isLowSurrogate(body[start]) && Character.isHighSurrogate(body[start - 1])))
        require(!(end < body.length && Character.isLowSurrogate(body[end]) && Character.isHighSurrogate(body[end - 1])))
    }
    fun style(font: String, color: String) {
        require(font in fonts) { "字体须为 hand/serif/sans/mono" }
        require(Regex("#[0-9A-Fa-f]{6}").matches(color)) { "颜色须为 #RRGGBB" }
    }
    fun drawing(lines: JSONArray) {
        require(lines.length() <= 200) { "手绘最多200笔" }
        var points = 0
        for (i in 0 until lines.length()) {
            val line = lines.getJSONArray(i)
            require(line.length() >= 1)
            points += line.length()
            require(points <= 12000) { "手绘点数过多，请简化后保存" }
            for (j in 0 until line.length()) {
                val point = line.getJSONArray(j)
                require(point.length() == 2 && point.getDouble(0).isFinite() && point.getDouble(1).isFinite())
                require(point.getDouble(0) in 0.0..1.0 && point.getDouble(1) in 0.0..1.0)
            }
        }
    }
    fun split(text: String): List<String> {
        require(text.isNotBlank()) { "没有可阅读的正文" }
        val result = mutableListOf<String>()
        var from = 0
        while (from < text.length) {
            var end = minOf(from + 12000, text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline > from + 6000) end = newline + 1
                if (end < text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end--
            }
            result.add(text.substring(from, end)); from = end
        }
        return result
    }
    fun event(action: String, chapter: String?, note: String?) = JSONObject()
        .put("id", java.util.UUID.randomUUID().toString()).put("action", action)
        .put("chapter", chapter).put("note", note).put("at", System.currentTimeMillis()).put("state", "pending")
}
