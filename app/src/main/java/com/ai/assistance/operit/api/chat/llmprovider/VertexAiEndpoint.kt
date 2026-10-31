package com.ai.assistance.operit.api.chat.llmprovider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Vertex AI Express uses publisher resources, not the Gemini Developer API models path. */
internal object VertexAiEndpoint {
    fun requestUrl(endpoint: String, modelName: String, streaming: Boolean): HttpUrl {
        val base = endpoint.trim().removeSuffix("#").toHttpUrl()
        require(base.query == null && base.fragment == null) {
            "Vertex AI endpoint must not contain query parameters or fragments"
        }
        val path = base.encodedPath.trimEnd('/')
        val modelsPath = when (path) {
            "" -> "/v1/publishers/google/models"
            "/v1", "/v1beta1" -> "$path/publishers/google/models"
            "/v1/publishers/google/models", "/v1beta1/publishers/google/models" -> path
            else -> throw IllegalArgumentException("Use a Vertex AI Express base URL or publisher models URL")
        }
        val model = modelName.trim().removePrefix("publishers/google/models/").removePrefix("models/")
        require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Vertex AI model ID" }
        val method = if (streaming) "streamGenerateContent" else "generateContent"
        return base.newBuilder()
            .encodedPath(modelsPath)
            .addPathSegment("$model:$method")
            .apply { if (streaming) addQueryParameter("alt", "sse") }
            .build()
    }
}
