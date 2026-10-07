package com.ai.assistance.operit.data.preferences

import android.content.Context

/** Separate from the chat model: text-only models receive the analysis as text. */
class VoiceCallAnalysisPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_call_analysis", Context.MODE_PRIVATE)
    val endpoint: String get() = prefs.getString("endpoint", DEFAULT_ENDPOINT)!!
    val model: String get() = prefs.getString("model", "qwen-omni-turbo")!!
    val apiKey: String get() = prefs.getString("api_key", "")!!
    val extraPrompt: String get() = prefs.getString("extra_prompt", "")!!

    fun save(endpoint: String, model: String, apiKey: String, extraPrompt: String) {
        require(endpoint.startsWith("https://")) { "请使用 HTTPS 接口地址" }
        require(model.isNotBlank()) { "请填写音频模型名称" }
        prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim())
            .putString("api_key", apiKey.trim()).putString("extra_prompt", extraPrompt).apply()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    }
}
