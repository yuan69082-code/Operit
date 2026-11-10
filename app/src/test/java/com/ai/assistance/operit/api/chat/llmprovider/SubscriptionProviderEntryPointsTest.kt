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
            assertEquals(if (channel == "kimi") "api.kimi.com" else "api.x.ai", request.url.host)
            assertEquals("Bearer subscription-test", request.header("Authorization"))
            val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
            requests.add(JSONObject(buffer.readUtf8()))
            val body = if (channel == "kimi") {
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
        if (channel == "kimi") accounts.saveKimiKey("subscription-test") else vault.put("grok",
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
        if (channel == "kimi") {
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
}
