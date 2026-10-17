package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.junit.Assert.*
import org.junit.Test

class HistoricalPackageContextTest {
    private val catalogue = "Using MCP server: ob\nDescription: durable memory\n\nAvailable tools:\n- ob:recall: schema details"
    private fun user(text: String) = PromptTurn(kind = PromptTurnKind.USER, content = text)
    private fun result(text: String) = PromptTurn(kind = PromptTurnKind.TOOL_RESULT, content = text)

    @Test fun oldCatalogueKeepsIdentityDescriptionAndReloadRoute() {
        val old = result(catalogue).copy(toolName = "use_package", metadata = mapOf("callId" to "1"))
        val turns = listOf(user("earlier"), old, user("now"))
        val compact = HistoricalPackageContext.compact(turns)
        assertTrue(compact[1].content.contains("Description: durable memory"))
        assertTrue(compact[1].content.contains("package_name=\"ob\""))
        assertFalse(compact[1].content.contains("schema details"))
        assertEquals(old.toolName, compact[1].toolName)
        assertEquals(old.metadata, compact[1].metadata)
        assertEquals(catalogue, turns[1].content)
    }

    @Test fun liveToolChainAndItsStatusRemainComplete() {
        val turns = listOf(user("current task"), result(catalogue),
            PromptTurn(kind = PromptTurnKind.USER, content = "<status type=\"working\">working</status>"))
        assertEquals(turns, HistoricalPackageContext.compact(turns))
    }

    @Test fun memoryFileAndExecutionResultsRemainVerbatim() {
        val turns = listOf(user("earlier"), result("Memory: our agreement"),
            result("File contents: Available tools:\nnot a package catalogue"),
            result("exitCode=0\noperation finished"), user("now"))
        assertEquals(turns, HistoricalPackageContext.compact(turns))
    }

    @Test fun wrappedPackageResultPreservesEnvelopeAndCanBeProcessedAgain() {
        val old = result("<tool_result name=\"use_package\" status=\"success\">" +
            catalogue.replace("Using MCP server: ob", "Using package: super_admin")
                .replace("Available tools:", "Available tools in this package:") + "</tool_result>")
        val turns = listOf(old, user("now"))
        val compact = HistoricalPackageContext.compact(turns)
        assertTrue(compact[0].content.startsWith("<tool_result name=\"use_package\" status=\"success\">"))
        assertTrue(compact[0].content.endsWith("</tool_result>"))
        assertTrue(compact[0].content.contains("package_name=\"super_admin\""))
        assertEquals(compact, HistoricalPackageContext.compact(compact))
    }

    @Test fun realRandomizedTagAndContentEnvelopeArePreserved() {
        val old = result("<tool_result_CCY7 name=\"use_package\" status=\"success\"><content>\n" +
            catalogue + "</content></tool_result_CCY7>")
        val compact = HistoricalPackageContext.compact(listOf(old, user("now")))[0].content
        assertTrue(compact.startsWith("<tool_result_CCY7 name=\"use_package\" status=\"success\"><content>"))
        assertTrue(compact.endsWith("</content></tool_result_CCY7>"))
        assertFalse(compact.contains("schema details"))
        assertTrue(compact.contains("package_name=\"ob\""))
    }
}
