package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ChatMarkupRegex
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelConfigConnectionTesterTest {
    @Test
    fun `probe obtains a real call and preserves the entire signed assistant turn`() = runBlocking {
        val assistantResponse =
            "<think>Use the requested echo.</think>\n" +
                echoCall + "\n" +
                ChatMarkupRegex.geminiThoughtSignatureMetaTag("opaque-test-payload") +
                "\n<meta provider=\"test:other_protocol\">keep-this-too</meta>\n"
        val requests = mutableListOf<List<PromptTurn>>()

        val outcome = ModelConfigConnectionTester.runToolCallProbe { history ->
            requests.add(history)
            when (requests.size) {
                1 -> assistantResponse
                2 -> "<think>Read the tool result.</think>ping"
                else -> error("The probe must stop after its continuation")
            }
        }

        assertEquals(ModelConnectionTestOutcome.PASSED, outcome)
        assertEquals(2, requests.size)
        assertEquals(listOf(PromptTurnKind.SYSTEM, PromptTurnKind.USER), requests[0].map { it.kind })
        assertTrue(requests[0].last().content.contains("echo"))
        assertEquals(requests[0], requests[1].take(2))
        assertEquals(PromptTurnKind.ASSISTANT, requests[1][2].kind)
        assertEquals(assistantResponse, requests[1][2].content)
        val result = requests[1][3]
        assertEquals(PromptTurnKind.TOOL_RESULT, result.kind)
        assertEquals("echo", result.toolName)
        val resultMatch = ChatMarkupRegex.xmlToolResultPattern.matchEntire(result.content)!!
        assertEquals("echo", resultMatch.groupValues[2])
        assertEquals("success", resultMatch.groupValues[3])
        assertEquals("ping", resultMatch.groupValues[4])
    }

    @Test
    fun `probe does not answer missing unrelated or malformed calls`() = runBlocking {
        val unexpectedResponses = listOf(
            "",
            "ping",
            "<think>$echoCall</think>",
            "<tool name=\"read_file\"><param name=\"text\">ping</param></tool>",
            "<tool name=\"echo\"></tool>",
            "<tool name=\"echo\"><param name=\"other\">ping</param></tool>",
            "<tool name=\"echo\"><param name=\"text\">different</param></tool>",
            "<tool name=\"echo\"><param name=\"text\">ping</param><param name=\"extra\">value</param></tool>",
            "<tool name=\"echo\"><param name=\"text\">ping</param><param name=\"text\">ping</param></tool>",
            "<tool name=\"echo\"><param name=\"text\">ping</param>unparsed arguments</tool>",
            echoCall + echoCall,
            echoCall + "<tool name=\"read_file\"/>",
            echoCall + "<tool_result name=\"echo\" status=\"success\"><content>ping</content></tool_result>"
        )
        for (response in unexpectedResponses) {
            var requestCount = 0
            val outcome = ModelConfigConnectionTester.runToolCallProbe {
                requestCount++
                response
            }
            assertEquals(response, ModelConnectionTestOutcome.UNVERIFIED, outcome)
            assertEquals("Unexpected calls must never receive an execution result", 1, requestCount)
        }
    }

    @Test
    fun `probe requires a visible completed reply after the tool result`() = runBlocking {
        val incompleteReplies = listOf(
            "",
            " \n\t",
            "<think>Still thinking.</think>",
            "<think>Still thinking without an end tag",
            "<thinking>Still thinking.</thinking>",
            "<THINK>Still thinking.</THINK>",
            "<think/>",
            ChatMarkupRegex.geminiThoughtSignatureMetaTag("opaque-test-payload"),
            "<meta provider=\"other:protocol\">opaque</meta>",
            "<status type=\"complete\">done</status>",
            "<status type=\"complete\"/>",
            "<search>Sources only</search>",
            echoCall,
            "One more call: $echoCall"
        )
        for (reply in incompleteReplies) {
            var requestCount = 0
            val outcome = ModelConfigConnectionTester.runToolCallProbe {
                requestCount++
                if (requestCount == 1) echoCall else reply
            }
            assertEquals(reply, ModelConnectionTestOutcome.UNVERIFIED, outcome)
            assertEquals(2, requestCount)
        }
    }

    @Test
    fun `request failures and cancellation propagate from either probe round`() = runBlocking {
        for (failingRound in 1..2) {
            for (failure in listOf(IOException("request failed"), CancellationException("cancelled"))) {
                var requestCount = 0
                try {
                    ModelConfigConnectionTester.runToolCallProbe {
                        requestCount++
                        if (requestCount == failingRound) throw failure
                        echoCall
                    }
                    fail("The probe must propagate errors to the owning service lifecycle")
                } catch (actual: Exception) {
                    assertSame(failure, actual)
                }
                assertEquals(failingRound, requestCount)
            }
        }
    }

    private val echoCall =
        "<tool_probe name=\"echo\"><param name=\"text\">ping</param></tool_probe>"
}
