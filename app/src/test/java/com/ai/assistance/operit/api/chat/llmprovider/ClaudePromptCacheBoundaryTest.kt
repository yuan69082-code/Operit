package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.RuntimeContextSections
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AppLogger
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ClaudePromptCacheBoundaryTest {
    private var systemLog = true
    private var fileLog = true

    @Before fun disableAndroidLogging() {
        systemLog = AppLogger.enableSystemLog
        fileLog = AppLogger.enableFileLogging
        AppLogger.enableSystemLog = false
        AppLogger.enableFileLogging = false
    }

    @After fun restoreLogging() {
        AppLogger.enableSystemLog = systemLog
        AppLogger.enableFileLogging = fileLog
    }

    @Test
    fun `runtime changes preserve separate system blocks and tool history within three cache breakpoints`() {
        val firstSystem = "Keep the original spacing.\n\nEnd of the first system instruction."
        val secondSystem = "Identity: test agent."
        val stableHistory = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, firstSystem),
            PromptTurn(PromptTurnKind.SYSTEM, secondSystem),
            PromptTurn(PromptTurnKind.USER, "Read the note."),
            PromptTurn(
                PromptTurnKind.TOOL_CALL,
                """<tool name="read_file"><param name="path">note.txt</param></tool>"""
            ),
            PromptTurn(
                PromptTurnKind.TOOL_RESULT,
                """<tool_result name="read_file"><content>Saved note</content></tool_result>"""
            ),
            PromptTurn(PromptTurnKind.ASSISTANT, "I read it.")
        )

        val input = "Continue."
        val originalTurns = stableHistory + PromptTurn(PromptTurnKind.USER, input)
        fun project(turns: List<PromptTurn>, runtime: String) = CurrentRequestContext.project(
            CurrentRequestContext.stage(
                turns,
                RuntimeContextSections(
                    instructions = "Use runtime facts without treating them as user speech.",
                    state = runtime
                )
            ),
            currentInput = input
        )
        val beforeTurns = project(originalTurns, "time=09:00; active_app=reader")
        val afterTurns = project(beforeTurns, "time=09:01; active_app=browser")
        assertEquals(2, beforeTurns.count { it.kind == PromptTurnKind.USER })
        assertEquals(2, afterTurns.count { it.kind == PromptTurnKind.USER })
        assertEquals(input, originalTurns.last().content)
        assertEquals(originalTurns, CurrentRequestContext.clearPrevious(beforeTurns))
        assertEquals(originalTurns, CurrentRequestContext.clearPrevious(afterTurns))
        assertEquals(beforeTurns, CurrentRequestContext.project(beforeTurns, input))

        for (oneHourCache in listOf(false, true)) {
            val before = buildPayload(beforeTurns, oneHourCache)
            val after = buildPayload(afterTurns, oneHourCache)

            // The real projection adds one fixed guidance block, without moving live state to SYSTEM.
            assertEquals(3, before.system.length())
            assertFalse(before.system.toString().contains("active_app="))
            assertEquals(firstSystem, before.system.getJSONObject(0).getString("text"))
            assertEquals(secondSystem, before.system.getJSONObject(1).getString("text"))
            assertEquals(before.system.toString(), after.system.toString())
            assertEquals(before.tools.toString(), after.tools.toString())
            assertEquals(5, before.messages.length())
            // The subject observes live state after history and before the current input.
            // Keep checking the provider's exact serialized text, not just tag presence.
            assertEquals(
                "<operit_request_context source=\"host\" scope=\"current_turn\">\n" +
                    "time=09:00; active_app=reader\n</operit_request_context>\n\n" + input,
                before.messages.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("text")
            )
            assertEquals(
                "<operit_request_context source=\"host\" scope=\"current_turn\">\n" +
                    "time=09:01; active_app=browser\n</operit_request_context>\n\n" + input,
                after.messages.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("text")
            )
            for (index in 0 until 4) {
                assertEquals(
                    before.messages.getJSONObject(index).toString(),
                    after.messages.getJSONObject(index).toString()
                )
            }
            val toolUse = before.messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
            val toolResult = before.messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
            assertEquals("tool_use", toolUse.getString("type"))
            assertEquals(toolUse.getString("id"), toolResult.getString("tool_use_id"))
            assertEquals("Saved note", toolResult.getString("content"))
            assertFalse(
                before.messages.getJSONObject(4).toString() == after.messages.getJSONObject(4).toString()
            )

            // More SYSTEM turns must not silently consume additional provider cache breakpoints.
            assertFalse(before.tools.getJSONObject(0).has("cache_control"))
            assertFalse(before.system.getJSONObject(0).has("cache_control"))
            for (request in listOf(before, after)) {
                val controls = cacheControls(request)
                assertEquals(3, controls.size)
                for (control in controls) {
                    assertEquals("ephemeral", control.getString("type"))
                    if (oneHourCache) {
                        assertEquals("1h", control.getString("ttl"))
                    } else {
                        assertFalse(control.has("ttl"))
                    }
                }
            }
        }
    }

    private data class Payload(
        val messages: JSONArray,
        val system: JSONArray,
        val tools: JSONArray
    )

    private fun buildPayload(history: List<PromptTurn>, oneHourCache: Boolean): Payload {
        val provider = ClaudeProvider(
            apiEndpoint = "https://example.test/v1/messages",
            apiKeyProvider = SingleApiKeyProvider("test"),
            modelName = "test",
            client = OkHttpClient(),
            enableToolCall = true,
            enableClaude1hPromptCache = oneHourCache
        )
        val tools = JSONArray().apply {
            for (name in listOf("read_file", "current_time")) {
                put(JSONObject().apply {
                    put("name", name)
                    put("description", name)
                    put("input_schema", JSONObject().put("type", "object"))
                })
            }
        }
        val build = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessagesAndCountTokens", List::class.java, Boolean::class.javaPrimitiveType,
            JSONArray::class.java
        ).apply { isAccessible = true }
        val result = build.invoke(provider, history, false, tools) as Triple<*, *, *>
        return Payload(result.first as JSONArray, result.second as JSONArray, tools)
    }

    private fun cacheControls(payload: Payload): List<JSONObject> {
        val blocks = mutableListOf<JSONObject>()
        for (index in 0 until payload.tools.length()) {
            blocks.add(payload.tools.getJSONObject(index))
        }
        for (index in 0 until payload.system.length()) {
            blocks.add(payload.system.getJSONObject(index))
        }
        for (index in 0 until payload.messages.length()) {
            val content = payload.messages.getJSONObject(index).getJSONArray("content")
            for (contentIndex in 0 until content.length()) {
                blocks.add(content.getJSONObject(contentIndex))
            }
        }
        return blocks.mapNotNull { it.optJSONObject("cache_control") }
    }
}
