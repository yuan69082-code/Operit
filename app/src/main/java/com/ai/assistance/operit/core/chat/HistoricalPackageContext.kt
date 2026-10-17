package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.ChatMarkupRegex

/** Package schemas can be reloaded; task results and retrieved memories cannot be discarded. */
internal object HistoricalPackageContext {
    private val packageHeader = Regex("""^\s*Using (?:MCP server|package): ([A-Za-z0-9_.:-]+)\r?\n""")
    private val toolsHeading = Regex("""(?m)^Available tools(?: in this package)?:[ \t]*\r?$""")

    private fun compactCatalogue(body: String): String {
        val header = packageHeader.find(body) ?: return body
        val heading = toolsHeading.find(body) ?: return body
        return body.substring(0, heading.range.first).trimEnd() +
            "\n\nHistorical package schema omitted. Before using this package again, " +
            "call use_package with package_name=\"" + header.groupValues[1] +
            "\" to retrieve the current tool names and parameters."
    }

    private fun compactResult(text: String): String {
        val result = ChatMarkupRegex.toolResultTagWithAttrs.matchEntire(text)
            ?: return compactCatalogue(text)
        val name = ChatMarkupRegex.nameAttr.find(result.groupValues[2])?.groupValues?.get(1)
        if (name != "use_package") return text
        val body = requireNotNull(result.groups[3])
        val content = ChatMarkupRegex.contentTag.find(body.value)
        val compactedBody = if (content != null) {
            body.value.replaceRange(requireNotNull(content.groups[1]).range,
                compactCatalogue(content.groupValues[1]))
        } else compactCatalogue(body.value)
        // Preserve randomized tool-result tags, the content envelope and call associations.
        return text.replaceRange(body.range, compactedBody)
    }

    fun compact(turns: List<PromptTurn>): List<PromptTurn> {
        // Status/warning turns originate inside a tool chain, not from a new user request.
        val currentInput = turns.indexOfLast {
            it.kind == PromptTurnKind.USER &&
                !it.content.trimStart().startsWith("<status") &&
                !it.content.trimStart().startsWith("<warning")
        }
        if (currentInput < 0) return turns
        return turns.mapIndexed { index, turn ->
            if (index >= currentInput || turn.kind != PromptTurnKind.TOOL_RESULT ||
                turn.toolName != null && turn.toolName != "use_package") turn
            else turn.copy(content = compactResult(turn.content))
        }
    }
}
