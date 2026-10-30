package com.ai.assistance.operit.ui.features.chat.voice

import org.json.JSONObject

internal object VoiceAnalysisServiceError {
    fun describe(envelope: JSONObject, apiKey: String): String? {
        // error:null is a successful chunk, not a service failure.
        if (!envelope.has("error") || envelope.isNull("error")) return null
        val error = envelope.get("error")
        var text = if (error is JSONObject) {
            listOf("code", "type", "message").mapNotNull {
                (error.opt(it) as? String)?.takeIf { value -> value.isNotBlank() }
            }.distinct().joinToString(" · ")
        } else if (error is String) error else ""
        if (apiKey.isNotEmpty()) text = text.replace(apiKey, "[已隐藏密钥]")
        text = text.replace(Regex("Bearer\\s+[^\\s,;]+", RegexOption.IGNORE_CASE), "Bearer [已隐藏]")
        text = text.replace(Regex("https?://[^\\s]+"), "[接口地址]")
        text = text.replace(Regex("data:[^\\s]+", RegexOption.IGNORE_CASE), "[音频数据]")
        text = text.replace(Regex("[A-Za-z0-9+/=_-]{80,}"), "[长数据已隐藏]")
        return text.replace(Regex("[\\r\\n\\t]+"), " ").take(400)
            .ifBlank { "服务端返回了错误，但没有提供具体原因" }
    }

    fun fromBody(body: String, apiKey: String): String? {
        val json = try { JSONObject(body) } catch (_: org.json.JSONException) { return null }
        return describe(json, apiKey)
    }
}
