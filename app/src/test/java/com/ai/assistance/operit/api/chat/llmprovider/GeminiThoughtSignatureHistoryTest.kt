package com.ai.assistance.operit.api.chat.llmprovider

import android.util.Base64
import com.ai.assistance.operit.api.chat.enhance.ConversationService
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.JvmAppLogRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

class GeminiThoughtSignatureHistoryTest {
    @get:Rule val appLog = JvmAppLogRule()

    @Test
    fun `Vertex to Google removes only signatures from completed turns without changing stored history`() {
        val originalCall = "Before the tool.\n" + call("echo") + "\n" + signature("old-signature")
        val originalReply = "Completed answer.\n" + signature("old-final-signature")
        val history = listOf(
            user("Earlier question"),
            assistant(originalCall, source(ApiProviderType.VERTEX_AI)),
            result("echo", "ping"),
            assistant(originalReply, source(ApiProviderType.VERTEX_AI)),
            user("New question")
        )
        val originalSnapshot = history.toList()

        val prepared = prepare(history)

        assertEquals(history.size, prepared.size)
        assertEquals("Before the tool.\n" + call("echo"), prepared[1].content)
        assertEquals("Completed answer.", prepared[3].content)
        assertEquals(history[1].metadata, prepared[1].metadata)
        for (index in listOf(0, 2, 4)) assertSame(history[index], prepared[index])
        assertEquals(originalSnapshot, history)
        assertEquals(originalCall, history[1].content)
        assertEquals(originalReply, history[3].content)
    }

    @Test
    fun `same provider and model keep old signatures while a model change removes them`() {
        val signed = assistant(call("echo") + signature("signature"), source(ApiProviderType.GOOGLE))
        val history = listOf(user("Earlier question"), signed, result("echo", "ping"), user("Next"))

        assertSame(signed, prepare(history)[1])
        val normalizedSource = signed.copy(metadata = mapOf(
            PromptTurn.SOURCE_PROVIDER_METADATA_KEY to " google ",
            PromptTurn.SOURCE_MODEL_METADATA_KEY to " $MODEL "
        ))
        assertSame(normalizedSource, prepare(history.toMutableList().apply { set(1, normalizedSource) })[1])
        assertEquals(call("echo"), prepareGeminiThoughtSignatureHistory(
            history, ApiProviderType.GOOGLE, "gemini-other-model"
        )[1].content)
        assertEquals(call("echo") + signature("signature"), signed.content)
    }

    @Test
    fun `tool results never start a new user turn in a multi round tool loop`() {
        val currentSource = source(ApiProviderType.VERTEX_AI, "different-model")
        val history = listOf(
            user("Old question"),
            assistant(call("old") + signature("old"), source(ApiProviderType.VERTEX_AI)),
            result("old", "old result"),
            user("Current question"),
            assistant(call("first") + signature("first"), currentSource),
            result("first", "first result"),
            PromptTurn(
                kind = PromptTurnKind.TOOL_CALL,
                content = call("second") + signature("second"),
                metadata = currentSource
            ),
            result("second", "second result")
        )

        val prepared = prepare(history)

        assertFalse(prepared[1].content.contains("<meta"))
        for (index in 3 until history.size) assertSame(history[index], prepared[index])
        val calls = functionCallParts(buildContents(history))
        assertFalse(calls[0].has("thoughtSignature"))
        assertEquals("first", calls[1].getString("thoughtSignature"))
        assertEquals("second", calls[2].getString("thoughtSignature"))
    }

    @Test
    fun `history without a later user boundary never loses a signature`() {
        val signed = assistant(call("echo") + signature("current"), source(ApiProviderType.VERTEX_AI))
        for (history in listOf(
            listOf(signed, result("echo", "ping")),
            listOf(user("Current question"), signed, result("echo", "ping"))
        )) {
            assertSame(history, prepare(history))
        }
    }

    @Test
    fun `missing blank or invalid provenance does not authorize removing signatures`() {
        val unknownSources = listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf(PromptTurn.SOURCE_PROVIDER_METADATA_KEY to null, PromptTurn.SOURCE_MODEL_METADATA_KEY to null),
            mapOf(PromptTurn.SOURCE_PROVIDER_METADATA_KEY to " ", PromptTurn.SOURCE_MODEL_METADATA_KEY to ""),
            mapOf(PromptTurn.SOURCE_PROVIDER_METADATA_KEY to "UNKNOWN", PromptTurn.SOURCE_MODEL_METADATA_KEY to MODEL),
            mapOf(PromptTurn.SOURCE_PROVIDER_METADATA_KEY to 42, PromptTurn.SOURCE_MODEL_METADATA_KEY to false)
        )
        for (metadata in unknownSources) {
            val signed = assistant(call("echo") + signature("unknown-source"), metadata)
            val history = listOf(user("Old"), signed, result("echo", "ping"), user("New"))
            assertSame(signed, prepare(history)[1])
        }
    }

    @Test
    fun `different source assistant fragments are isolated before history compilation merges metadata`() {
        val foreign = assistant("Foreign text." + signature("foreign"), source(ApiProviderType.VERTEX_AI))
        val local = assistant("Local text." + signature("local"), source(ApiProviderType.GOOGLE))
        val history = listOf(user("Old"), foreign, local, user("New"))

        val prepared = prepare(history)
        val compiled = StructuredToolCallBridge.compileHistoryForProvider(prepared, useToolCall = true)

        assertEquals("Foreign text.", prepared[1].content)
        assertSame(local, prepared[2])
        assertEquals(3, compiled.size)
        assertEquals("Foreign text.\nLocal text." + signature("local"), compiled[1].content)
        assertEquals(source(ApiProviderType.GOOGLE), compiled[1].metadata)
        assertEquals("Foreign text." + signature("foreign"), foreign.content)
    }

    @Test
    fun `wire serialization cleans a foreign signature before an adjacent local fragment can mask its source`() {
        val history = listOf(
            user("Old question"),
            assistant("Before tool.\n" + call("echo") + signature("foreign"), source(ApiProviderType.VERTEX_AI)),
            assistant("Local commentary.", source(ApiProviderType.GOOGLE)),
            result("echo", "ping"),
            user("New question")
        )

        val contents = buildContents(history)
        val toolCall = functionCallParts(contents).single()
        val toolResult = parts(contents).single { it.has("functionResponse") }.getJSONObject("functionResponse")
        val text = parts(contents).filter { it.has("text") }.map { it.getString("text") }

        assertFalse(toolCall.has("thoughtSignature"))
        assertEquals("echo", toolCall.getJSONObject("functionCall").getString("name"))
        assertEquals("ping", toolCall.getJSONObject("functionCall").getJSONObject("args").getString("text"))
        assertEquals("echo", toolResult.getString("name"))
        assertEquals("ping", toolResult.getJSONObject("response").getString("result"))
        assertTrue(text.any { it.contains("Before tool.") && it.contains("Local commentary.") })
        assertEquals("New question", text.last())
        assertFalse(contents.toString().contains(PromptTurn.SOURCE_PROVIDER_METADATA_KEY))
        assertFalse(contents.toString().contains("工具结果缺失"))
    }

    @Test
    fun `wire serialization retains an old signature from the same provider and model`() {
        val history = listOf(
            user("Old question"),
            assistant(call("echo") + signature("same-source"), source(ApiProviderType.GOOGLE)),
            result("echo", "ping"),
            user("New question")
        )

        assertEquals("same-source", functionCallParts(buildContents(history)).single().getString("thoughtSignature"))
    }

    @Test
    fun `conversation tool splitting preserves provenance on text calls signatures and results`(): Unit = runBlocking {
        val service = Mockito.mock(ConversationService::class.java, Mockito.CALLS_REAL_METHODS)
        val metadata = source(ApiProviderType.VERTEX_AI) + ("other_metadata" to "preserved")
        val tags = listOf(
            listOf("text", "Before tool."),
            listOf("tool_probe", call("echo")),
            listOf("meta", signature("split-signature")),
            listOf("tool_result_probe", result("echo", "ping").content),
            listOf("text", "After tool.")
        )
        val split = mutableListOf<PromptTurn>()

        service.processChatMessageWithTools(
            content = tags.joinToString("") { it[1] },
            xmlTags = tags,
            conversationHistory = split,
            messageIndex = 1,
            totalMessages = 3,
            messageMetadata = metadata
        )

        assertEquals(listOf(
            PromptTurnKind.ASSISTANT, PromptTurnKind.TOOL_CALL, PromptTurnKind.ASSISTANT,
            PromptTurnKind.TOOL_RESULT, PromptTurnKind.ASSISTANT
        ), split.map { it.kind })
        assertEquals(tags.map { it[1] }, split.map { it.content })
        split.forEach { assertEquals(metadata, it.metadata) }
        val contents = buildContents(listOf(user("Old")) + split + user("New"))
        assertFalse(functionCallParts(contents).single().has("thoughtSignature"))
        assertEquals(signature("split-signature"), split[2].content)

        val plain = mutableListOf<PromptTurn>()
        service.processChatMessageWithTools("Plain reply", emptyList(), plain, 0, 1, metadata)
        assertEquals(listOf(assistant("Plain reply", metadata)), plain)
    }

    @Test
    fun `both assistant history branches retain the persisted provider and model`() {
        val content = call("echo") + signature("stored-signature")
        val message = ChatMessage(
            sender = "ai", content = content, timestamp = 1L, roleName = "Assistant",
            provider = "VERTEX_AI / saved configuration", modelName = " $MODEL "
        )
        val method = AIMessageManager::class.java.declaredMethods.single {
            it.name == "processAiMessage" && it.parameterCount == 4
        }.apply { isAccessible = true }
        val keepStatus: (String) -> String = { it }

        for (roleScoped in listOf(false, true)) {
            val turn = method.invoke(AIMessageManager, message, roleScoped, "Assistant", keepStatus) as PromptTurn
            assertEquals(PromptTurnKind.ASSISTANT, turn.kind)
            assertEquals(content, turn.content)
            assertEquals(source(ApiProviderType.VERTEX_AI), turn.metadata)
        }
    }

    private fun prepare(history: List<PromptTurn>) =
        prepareGeminiThoughtSignatureHistory(history, ApiProviderType.GOOGLE, MODEL)

    private fun source(provider: ApiProviderType, model: String = MODEL): Map<String, Any?> = mapOf(
        PromptTurn.SOURCE_PROVIDER_METADATA_KEY to provider.name,
        PromptTurn.SOURCE_MODEL_METADATA_KEY to model
    )

    private fun user(text: String) = PromptTurn(PromptTurnKind.USER, text)

    private fun assistant(text: String, metadata: Map<String, Any?>) =
        PromptTurn(PromptTurnKind.ASSISTANT, text, metadata = metadata)

    private fun call(name: String) =
        "<tool_probe name=\"$name\"><param name=\"text\">ping</param></tool_probe>"

    private fun result(name: String, text: String) = PromptTurn(
        kind = PromptTurnKind.TOOL_RESULT,
        content = "<tool_result_probe name=\"$name\" status=\"success\"><content>$text</content></tool_result_probe>"
    )

    private fun signature(value: String) = ChatMarkupRegex.geminiThoughtSignatureMetaTag(
        java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    )

    private fun functionCallParts(contents: JSONArray) = parts(contents).filter { it.has("functionCall") }

    private fun parts(contents: JSONArray): List<JSONObject> = (0 until contents.length()).flatMap { index ->
        val contentParts = contents.getJSONObject(index).getJSONArray("parts")
        (0 until contentParts.length()).map { contentParts.getJSONObject(it) }
    }

    private fun buildContents(history: List<PromptTurn>): JSONArray {
        // The wire builder is real; only Android's unavailable JVM Base64 method is replaced.
        return Mockito.mockStatic(Base64::class.java).use { base64 ->
            base64.`when`<ByteArray> { Base64.decode(anyString(), anyInt()) }.thenAnswer { invocation ->
                java.util.Base64.getDecoder().decode(invocation.getArgument<String>(0))
            }
            val provider = GeminiProvider(
                apiEndpoint = "https://example.test", apiKeyProvider = SingleApiKeyProvider("test-key"),
                modelName = MODEL, client = OkHttpClient(), providerType = ApiProviderType.GOOGLE,
                enableToolCall = true
            )
            val method = GeminiProvider::class.java.declaredMethods.single {
                it.name == "buildContentsAndCountTokens" && it.parameterCount == 3
            }.apply { isAccessible = true }
            val result = method.invoke(provider, history, null, false) as Pair<*, *>
            (result.first as Pair<*, *>).first as JSONArray
        }
    }

    private companion object {
        const val MODEL = "gemini-test"
    }
}
