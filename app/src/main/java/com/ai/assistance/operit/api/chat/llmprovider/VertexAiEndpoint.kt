package com.ai.assistance.operit.api.chat.llmprovider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Vertex API-key requests use publisher resources with an optional explicit project/location. */
internal object VertexAiEndpoint {
    private val projectLocationPath =
        Regex("/v1(?:beta1)?/projects/[A-Za-z0-9_-]+/locations/[a-z0-9-]+")

    fun requestUrl(endpoint: String, modelName: String, streaming: Boolean): HttpUrl {
        val base = endpoint.trim().removeSuffix("#").toHttpUrl()
        require(base.query == null && base.fragment == null) {
            "Vertex AI endpoint must not contain query parameters or fragments"
        }
        val path = base.encodedPath.trimEnd('/')
        // Preserve an explicit location: removing it delegates routing back to Express,
        // which can select a region where the requested model is unavailable.
        val resourceBase = path.removeSuffix("/publishers/google/models").removeSuffix("/publishers/google")
        val modelsPath = when {
            path.isEmpty() -> "/v1/publishers/google/models"
            resourceBase == "/v1" || resourceBase == "/v1beta1" ||
                projectLocationPath.matches(resourceBase) -> "$resourceBase/publishers/google/models"
            else -> throw IllegalArgumentException(
                "Use a Vertex AI base URL or /v1/projects/PROJECT_ID/locations/LOCATION/publishers/google/models"
            )
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
