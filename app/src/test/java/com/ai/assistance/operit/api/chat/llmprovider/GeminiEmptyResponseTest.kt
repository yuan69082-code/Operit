package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.JvmAppLogRule
import com.ai.assistance.operit.util.stream.StreamCollector
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

class GeminiEmptyResponseTest {
    @get:Rule val appLog = JvmAppLogRule()

    @Test fun emptyAndThoughtOnlyResponsesAreNotSuccessfulReplies() = runBlocking {
        for (streaming in listOf(false, true)) {
            for (parts in listOf(JSONArray(), JSONArray().put(JSONObject().put("text", "checking").put("thought", true)))) {
                try {
                    process(parts, streaming)
                    fail("An empty business response must not complete successfully")
                } catch (expected: IOException) {
                    assertEquals("no output", expected.message)
                }
            }
        }
    }

    @Test fun realTextAndToolOnlyResponsesRemainValid() = runBlocking {
        for (streaming in listOf(false, true)) {
            assertEquals("done", process(JSONArray().put(JSONObject().put("text", "done")), streaming))
            val output = process(JSONArray().put(JSONObject().put("functionCall", JSONObject()
                .put("name", "package_proxy").put("args", JSONObject().put("tool_name", "demo:say")
                    .put("params", "{\"text\":\"hello\"}")))), streaming)
            assertTrue(output.contains("package_proxy"))
            assertTrue(output.contains("hello"))
        }
    }

    private suspend fun process(parts: JSONArray, streaming: Boolean): String {
        val context = mock<Context>()
        whenever(context.getString(R.string.gemini_response_no_output)).thenReturn("no output")
        val provider = GeminiProvider("https://example.test", SingleApiKeyProvider("test-key"),
            "gemini-test", OkHttpClient(), enableToolCall = true)
        val payload = JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("finishReason", "STOP").put("content", JSONObject().put("parts", parts))))
            .put("usageMetadata", JSONObject().put("promptTokenCount", 1).put("candidatesTokenCount", 1))
        val body = if (streaming) "data: $payload\n\n" else payload.toString()
        val response = Response.Builder().request(Request.Builder().url("https://example.test").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        val output = StringBuilder()
        val collector = object : StreamCollector<String> {
            override suspend fun emit(value: String) { output.append(value) }
        }
        response.use {
            if (streaming) provider.processStreamingResponse(context, it, collector, "test", { _, _, _ -> },
                StringBuilder(), true)
            else provider.processNonStreamingResponse(context, it, collector, "test", { _, _, _ -> },
                StringBuilder(), true)
        }
        return output.toString()
    }
}
