package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONObject

/** A local request error; neither a transport retry nor a synthetic prompt can repair it. */
internal class GeminiRequestContentException(message: String) : IllegalArgumentException(message)

internal fun requireGeminiRequestContent(request: JSONObject, message: String) {
    val contents = request.optJSONArray("contents")
    if (contents == null || contents.length() == 0) throw GeminiRequestContentException(message)
    for (index in 0 until contents.length()) {
        val parts = contents.optJSONObject(index)?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) throw GeminiRequestContentException(message)
        for (partIndex in 0 until parts.length()) {
            val part = parts.optJSONObject(partIndex)
            if (part == null || part.length() == 0 ||
                (part.has("text") && part.optString("text").isBlank())) {
                throw GeminiRequestContentException(message)
            }
        }
    }
}
