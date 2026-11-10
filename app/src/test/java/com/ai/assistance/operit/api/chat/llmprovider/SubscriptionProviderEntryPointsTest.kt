package com.ai.assistance.operit.api.chat.llmprovider

import android.app.Application
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.companion.OperitStorageTestRunner
import com.ai.assistance.operit.data.api.SubscriptionAccounts
import com.ai.assistance.operit.data.api.SubscriptionVault
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ToolPrompt
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Simulated subscription HTTP responses through the production factory and streaming
 * provider. No real subscription, paid inference or external tool is called. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubscriptionProviderEntryPointsTest {
    private class Vault : SubscriptionVault {
        private val values = mutableMapOf<String, JSONObject>()
        @Synchronized override fun get(channel: String) = values[channel]?.let { JSONObject(it.toString()) }
        @Synchronized override fun put(channel: String, value: JSONObject?) {
            if (value == null) values.remove(channel) else values[channel] = JSONObject(value.toString())
        }
    }
    private fun sse(vararg data: String) = data.joinToString("") { "data: $it\n\n" }
    private suspend fun exercise(channel: String) {
        val context = RuntimeEnvironment.getApplication()
        val vault = Vault()
        val requests = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(when (channel) { "kimi" -> "api.kimi.com"; "glm" -> "open.bigmodel.cn"; else -> "api.x.ai" }, request.url.host)
            if (channel == "glm") assertEquals("/api/coding/paas/v4/chat/completions", request.url.encodedPath)
            assertEquals("Bearer subscription-test", request.header("Authorization"))
            val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
            requests.add(JSONObject(buffer.readUtf8()))
            val body = if (channel != "grok") {
                if (requests.size == 1) sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"server-call","type":"function","function":{"name":"sleep","arguments":"{}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""", "[DONE]")
                else sse("""{"choices":[{"index":0,"delta":{"content":"已经取得本地回执。"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]")
            } else if (requests.size == 1) sse(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"server-call","name":"sleep"}}""",
                """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}""",
                """{"type":"response.function_call_arguments.done","output_index":0,"arguments":"{}"}""",
                """{"type":"response.completed","response":{"id":"r1","status":"completed","output":[]}}""")
            else sse("""{"type":"response.output_text.delta","output_index":0,"delta":"已经取得本地回执。"}""",
                """{"type":"response.completed","response":{"id":"r2","status":"completed","output":[]}}""")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("simulated")
                .header("Content-Type", "text/event-stream").body(body.toResponseBody()).build()
        }.build()
        val accounts = SubscriptionAccounts(vault, client)
        if (channel != "grok") accounts.savePlanKey(channel, "subscription-test") else vault.put("grok",
            JSONObject().put("session", "grok-test").put("access", "subscription-test").put("refresh", "refresh")
                .put("expires_at", System.currentTimeMillis() + 3_600_000))
        val provider = SubscriptionProviderFactory.create(accounts,
            ModelConfigData("same-config", "测试", modelName = "service-model", subscriptionChannel = channel, enableToolCall = true), client)
        val tools = listOf(ToolPrompt("sleep", "Local test receipt", parametersStructured = emptyList()))
        val history = listOf(PromptTurn(PromptTurnKind.SYSTEM, "原助手的已采用身份材料"), PromptTurn(PromptTurnKind.USER, "原会话输入"))
        suspend fun request(turns: List<PromptTurn>): String {
            val output = StringBuilder()
            provider.sendMessage(context, turns, availableTools = tools, enableRetry = false, recordTokenUsage = false)
                .collect { output.append(it) }
            return output.toString()
        }
        val call = request(history)
        assertTrue(call, call.contains("<tool") && call.contains("sleep"))
        val answer = request(history + PromptTurn(PromptTurnKind.ASSISTANT, call) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, "<tool_result name=\"sleep\" status=\"success\"><content>确切本地结果</content></tool_result>", "sleep"))
        assertTrue(answer, answer.contains("已经取得本地回执"))
        assertEquals(2, requests.size)
        assertTrue(requests[1].toString().contains("原助手的已采用身份材料"))
        assertTrue(requests[1].toString().contains("确切本地结果"))
        if (channel != "grok") {
            val rows = requests[1].getJSONArray("messages")
            val messages = (0 until rows.length()).map { rows.getJSONObject(it) }
            val tool = messages.single { it.optString("role") == "tool" }
            val invocation = messages.flatMap { m -> m.optJSONArray("tool_calls")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }.single()
            assertEquals(invocation.getString("id"), tool.getString("tool_call_id"))
        } else {
            val rows = requests[1].getJSONArray("input")
            val messages = (0 until rows.length()).map { rows.getJSONObject(it) }
            assertEquals(messages.single { it.optString("type") == "function_call" }.getString("call_id"),
                messages.single { it.optString("type") == "function_call_output" }.getString("call_id"))
        }
        accounts.logout(channel)
        try { request(history); fail("old provider cannot use a logged-out account") } catch (_: Exception) { }
        assertEquals("logout must stop before transport", 2, requests.size)
    }
    @Test fun kimiStreamsAndPairsToolContinuationWithoutChangingAssistantMaterial(): Unit = runBlocking { exercise("kimi") }
    @Test fun grokStreamsAndPairsToolContinuationWithoutChangingAssistantMaterial(): Unit = runBlocking { exercise("grok") }
    @Test fun glmUsesPlanEndpointAndPairsToolContinuationWithoutChangingAssistantMaterial(): Unit = runBlocking { exercise("glm") }

    @Test fun geminiCodeAssistPreservesNativePartsAndSignaturesThroughNormalFactory(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val vault = Vault()
        vault.put("gemini", JSONObject().put("session", "google-test").put("access", "google-access")
            .put("refresh", "refresh").put("project", "service-managed-project").put("expires_at", System.currentTimeMillis() + 3_600_000))
        val requests = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("cloudcode-pa.googleapis.com", request.url.host)
            assertEquals("/v1internal:streamGenerateContent", request.url.encodedPath)
            assertEquals("sse", request.url.queryParameter("alt"))
            assertNull(request.url.queryParameter("key"))
            assertEquals("Bearer google-access", request.header("Authorization"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val input = JSONObject(buffer.readUtf8()); requests.add(input)
            assertEquals("service-managed-project", input.getString("project"))
            assertEquals(0, input.getJSONArray("enabled_credit_types").length())
            assertTrue(input.getJSONObject("request").toString().contains("持续身份材料"))
            val part = if (requests.size == 1)
                """{"functionCall":{"id":"google-call","name":"sleep","args":{}},"thoughtSignature":"preserve-signature"}"""
            else """{"text":"已看到准确回执。"}"""
            val body = sse("""{"response":{"candidates":[{"content":{"role":"model","parts":[$part]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}},"traceId":"trace"}""")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("mocked")
                .body(body.toResponseBody()).build()
        }.build()
        val accounts = SubscriptionAccounts(vault, client)
        val provider = SubscriptionProviderFactory.create(accounts,
            ModelConfigData("same-assistant-config", "Google", subscriptionChannel = "gemini", modelName = "gemini-2.5-pro", enableToolCall = true), client)
        val history = listOf(PromptTurn(PromptTurnKind.SYSTEM, "持续身份材料"), PromptTurn(PromptTurnKind.USER, "请取得本地结果"))
        suspend fun send(turns: List<PromptTurn>): String {
            val text = StringBuilder()
            provider.sendMessage(context, turns, availableTools = listOf(ToolPrompt("sleep", "Local receipt", parametersStructured = emptyList())),
                enableRetry = false, recordTokenUsage = false).collect { text.append(it) }
            return text.toString()
        }
        val call = send(history)
        assertTrue(call, call.contains("sleep"))
        val answer = send(history + PromptTurn(PromptTurnKind.ASSISTANT, call) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, "<tool_result name=\"sleep\" status=\"success\"><content>准确回执</content></tool_result>", "sleep"))
        assertTrue(answer, answer.contains("已看到准确回执"))
        val contents = requests[1].getJSONObject("request").getJSONArray("contents")
        val parts = (0 until contents.length()).flatMap { i -> contents.getJSONObject(i).getJSONArray("parts").let { a ->
            (0 until a.length()).map(a::getJSONObject)
        } }
        val function = parts.single { it.has("functionCall") }
        assertEquals("preserve-signature", function.getString("thoughtSignature"))
        assertEquals("sleep", parts.single { it.has("functionResponse") }.getJSONObject("functionResponse").getString("name"))
        assertTrue(contents.toString().contains("准确回执"))
        accounts.logout("gemini")
        try { send(history); fail("old account must not send") } catch (_: Exception) {}
        assertEquals(2, requests.size)
    }
}
