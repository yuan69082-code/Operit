package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONArray
import org.json.JSONObject

/** Keep media/text and parallel responses intact, with responses at the turn boundary. */
internal fun orderGeminiToolResultParts(parts: JSONArray): JSONArray {
    val values = (0 until parts.length()).map { parts.getJSONObject(it) }
    if (values.none { it.has("functionResponse") }) return parts
    val (responses, otherParts) = values.partition { it.has("functionResponse") }
    return JSONArray().apply {
        (otherParts + responses).forEach { part: JSONObject -> put(part) }
    }
}
