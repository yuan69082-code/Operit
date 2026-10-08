package com.ai.assistance.operit.data.preferences

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Separate from the chat model: text-only models receive the analysis as text. */
class VoiceCallAnalysisPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_call_analysis", Context.MODE_PRIVATE)
    val savedEndpoint: String get() = prefs.getString("endpoint", DEFAULT_ENDPOINT)!!
    val endpoint: String get() = normalizeEndpoint(savedEndpoint)
    val model: String get() = prefs.getString("model", "qwen-omni-turbo")!!
    val apiKey: String get() = prefs.getString("api_key", "")!!
    val extraPrompt: String get() = prefs.getString("extra_prompt", "")!!

    fun save(endpoint: String, model: String, apiKey: String, extraPrompt: String) {
        val requestEndpoint = normalizeEndpoint(endpoint)
        require(model.isNotBlank()) { "请填写音频模型名称" }
        require(apiKey.trim().none { it.isWhitespace() }) { "API Key 中不能包含空格或换行" }
        prefs.edit().putString("endpoint", requestEndpoint).putString("model", model.trim())
            .putString("api_key", apiKey.trim()).putString("extra_prompt", extraPrompt).apply()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

        /** Accept SDK base URLs and full HTTP endpoints without changing the user's provider. */
        fun normalizeEndpoint(value: String): String {
            val url = value.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps) { "请填写有效的 HTTPS 接口地址" }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "接口地址不能包含账号、密码、查询参数或片段"
            }
            val path = url.encodedPath.trimEnd('/')
            require(path.endsWith("/v1") || path.endsWith("/chat/completions")) {
                "地址需以 /v1 或 /chat/completions 结尾"
            }
            val requestPath = if (path.endsWith("/v1")) "$path/chat/completions" else path
            return url.newBuilder().encodedPath(requestPath).build().toString()
        }
    }
}
