package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GeminiToolResultPartsTest {
    @get:org.junit.Rule val appLog = com.ai.assistance.operit.util.JvmAppLogRule()

    @Test
    fun `history serialization finishes mixed tool turns with the real response`() {
        val provider = GeminiProvider(
            apiEndpoint = "https://example.test",
            apiKeyProvider = SingleApiKeyProvider("test-key"),
            modelName = "gemini-test",
            client = OkHttpClient(),
            enableToolCall = true,
        )
        val history = listOf(
            PromptTurn(kind = PromptTurnKind.TOOL_CALL,
                content = "<tool name=\"stickers\"><param name=\"action\">view</param></tool>"),
            PromptTurn(kind = PromptTurnKind.TOOL_RESULT,
                content = "<tool_result name=\"stickers\" status=\"success\"><content>done</content></tool_result>attachment note")
        )
        val method = GeminiProvider::class.java.declaredMethods.single {
            it.name == "buildContentsAndCountTokens" && it.parameterCount == 3
        }
        method.isAccessible = true
        val result = method.invoke(provider, history, null, false) as Pair<*, *>
        val contents = (result.first as Pair<*, *>).first as JSONArray
        val last = contents.getJSONObject(contents.length() - 1)
        val parts = last.getJSONArray("parts")
        assertEquals("user", last.getString("role"))
        assertEquals("attachment note", parts.getJSONObject(0).getString("text"))
        assertEquals("done", parts.getJSONObject(parts.length() - 1)
            .getJSONObject("functionResponse").getJSONObject("response").getString("result"))
    }

    @Test
    fun `parallel sticker results retain their order and image bytes`() {
        val view = response("Image attached as multimodal input.")
        val send = response("![sticker](file:///example.png)")
        val image = JSONObject().put("inline_data", JSONObject()
            .put("mime_type", "image/png").put("data", "aW1hZ2U="))
        val text = JSONObject().put("text", "tool attachment")
        val ordered = orderGeminiToolResultParts(JSONArray()
            .put(view).put(send).put(image).put(text))

        assertEquals(4, ordered.length())
        assertSame(image, ordered.getJSONObject(0))
        assertSame(text, ordered.getJSONObject(1))
        assertSame(view, ordered.getJSONObject(2))
        assertSame(send, ordered.getJSONObject(3))
        assertEquals("aW1hZ2U=", ordered.getJSONObject(0)
            .getJSONObject("inline_data").getString("data"))
    }

    @Test
    fun `ordinary user images and text are unchanged`() {
        val parts = JSONArray().put(JSONObject().put("text", "describe"))
            .put(JSONObject().put("inlineData", JSONObject().put("data", "aW1hZ2U=")))
        assertSame(parts, orderGeminiToolResultParts(parts))
    }

    @Test
    fun `text only parallel responses keep their order`() {
        val first = response("first")
        val second = response("second")
        val ordered = orderGeminiToolResultParts(JSONArray().put(first).put(second))
        assertSame(first, ordered.getJSONObject(0))
        assertSame(second, ordered.getJSONObject(1))
    }

    private fun response(result: String) = JSONObject().put("functionResponse", JSONObject()
        .put("name", "stickers").put("response", JSONObject().put("result", result)))
}
