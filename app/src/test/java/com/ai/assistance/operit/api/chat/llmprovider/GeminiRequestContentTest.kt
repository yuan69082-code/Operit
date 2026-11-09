package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.JvmAppLogRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

class GeminiRequestContentTest {
    @get:Rule val appLog = JvmAppLogRule()

    @Test fun emptyAssistantAndThinkingOnlyHistoryAreNotSentAsEmptyText() {
        for (tools in listOf(false, true)) {
            val request = serialize(listOf(
                turn(PromptTurnKind.USER, "first"),
                turn(PromptTurnKind.ASSISTANT, "<think>private</think>"),
                turn(PromptTurnKind.USER, "next"),
                turn(PromptTurnKind.ASSISTANT, " "),
            ), tools)
            requireGeminiRequestContent(request, "empty")
            val contents = request.getJSONArray("contents")
            assertEquals(2, contents.length())
            assertEquals("next", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        }
    }

    @Test fun missingHistoricalMediaDoesNotPoisonFollowingTextRequestOrMutateHistory() {
        for (tools in listOf(false, true)) {
            val history = listOf(
                turn(PromptTurnKind.USER, "first"),
                turn(PromptTurnKind.ASSISTANT, "answer"),
                turn(PromptTurnKind.USER, missingImage),
                turn(PromptTurnKind.ASSISTANT, "earlier answer"),
                turn(PromptTurnKind.USER, "continue"),
            )
            val request = serialize(history, tools)
            requireGeminiRequestContent(request, "empty")
            assertEquals(4, request.getJSONArray("contents").length())
            assertEquals(missingImage, history[2].content)
        }
    }

    @Test fun currentMissingMediaCannotSilentlyResendAnOlderQuestion() {
        for (tools in listOf(false, true)) {
            val request = serialize(listOf(
                turn(PromptTurnKind.USER, "old question"),
                turn(PromptTurnKind.ASSISTANT, "old answer"),
                turn(PromptTurnKind.USER, missingImage),
            ), tools)
            assertRejected(request)
        }
    }

    @Test fun systemOnlyAndBlankRequestsAreRejected() {
        assertRejected(serialize(emptyList()))
        assertRejected(serialize(listOf(turn(PromptTurnKind.SYSTEM, "instructions"))))
        assertRejected(serialize(listOf(turn(PromptTurnKind.USER, " \n "))))
    }

    @Test fun blankOptionalSystemInstructionIsOmitted() {
        val request = serialize(listOf(
            turn(PromptTurnKind.SYSTEM, " "), turn(PromptTurnKind.USER, "hello")))
        assertFalse(request.has("systemInstruction"))
        requireGeminiRequestContent(request, "empty")
    }

    @Test fun realToolPairsSurviveEmptyHistoricalPlaceholders() {
        val request = serialize(listOf(
            turn(PromptTurnKind.USER, "look"),
            turn(PromptTurnKind.ASSISTANT, "<think>private</think>"),
            turn(PromptTurnKind.TOOL_CALL, "<tool name=\"inspect\"><param name=\"target\">file</param></tool>"),
            turn(PromptTurnKind.TOOL_RESULT, "<tool_result name=\"inspect\" status=\"success\"><content>actual result</content></tool_result>"),
        ), true)
        requireGeminiRequestContent(request, "empty")
        val contents = request.getJSONArray("contents")
        val call = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertTrue(call.has("functionCall"))
        val response = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0)
        assertEquals("actual result", response.getJSONObject("functionResponse").getJSONObject("response").getString("result"))
    }

    @Test fun mediaOnlyAndStructuredPartsDoNotRequireInventedText() {
        for (part in listOf(
            JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", "aW1hZ2U=")),
            JSONObject().put("fileData", JSONObject().put("mimeType", "video/mp4").put("fileUri", "gs://example/video")),
            JSONObject().put("functionResponse", JSONObject().put("name", "inspect").put("response", JSONObject())),
        )) {
            val request = JSONObject().put("contents", JSONArray().put(JSONObject()
                .put("role", "user").put("parts", JSONArray().put(part))))
            requireGeminiRequestContent(request, "empty")
            assertFalse(part.has("text"))
        }
    }

    @Test fun finalBodyOverridesCannotRemoveContentsOrParts() {
        for (request in listOf(
            JSONObject(), JSONObject().put("contents", JSONArray()),
            JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user"))),
            JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()))),
            JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", " "))))),
        )) assertRejected(request)
    }

    @Test fun invalidCurrentInputNeverReachesNetworkAndIsNotRetried(): Unit = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            error("Invalid request must not reach HTTP")
        }.build()
        val context = mock<Context>()
        whenever(context.getString(any())).thenReturn("request status")
        whenever(context.getString(R.string.gemini_error_empty_request_content)).thenReturn("no usable input")
        val provider = provider(false, client)
        val retries = mutableListOf<String>()
        val output = provider.sendMessage(context,
            listOf(turn(PromptTurnKind.USER, missingImage)), emptyList(),
            enableThinking = false, stream = true, availableTools = null,
            preserveThinkInHistory = false, onTokensUpdated = { _, _, _ -> },
            onUsageReported = null, onNonFatalError = { retries.add(it) },
            enableRetry = true, recordTokenUsage = false, onUsageFinalized = null)
        try {
            output.collect { fail("Invalid input cannot produce a successful reply") }
            fail("Expected local rejection")
        } catch (error: GeminiRequestContentException) {
            assertEquals("no usable input", error.message)
        }
        assertEquals(0, calls.get())
        assertTrue(retries.isEmpty())
    }

    private fun assertRejected(request: JSONObject) {
        try {
            requireGeminiRequestContent(request, "empty")
            fail("Expected request content error")
        } catch (expected: GeminiRequestContentException) {
            assertEquals("empty", expected.message)
        }
    }

    private fun serialize(history: List<PromptTurn>, tools: Boolean = true): JSONObject {
        val method = GeminiProvider::class.java.declaredMethods.single {
            it.name == "buildContentsAndCountTokens" && it.parameterCount == 3
        }
        method.isAccessible = true
        val result = method.invoke(provider(tools), history, null, false) as Pair<*, *>
        val content = result.first as Pair<*, *>
        return JSONObject().put("contents", content.first).also {
            content.second?.let { system -> it.put("systemInstruction", system) }
        }
    }

    private fun provider(tools: Boolean, client: OkHttpClient = OkHttpClient()) = GeminiProvider(
        "https://example.test", SingleApiKeyProvider("test-key"), "gemini-test", client,
        enableToolCall = tools)
    private fun turn(kind: PromptTurnKind, content: String) = PromptTurn(kind, content)
    private val missingImage = "<link type=\"image\" id=\"missing-gemini-content-test\"/>"
}
