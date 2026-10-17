package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind

/** Automatic device observations are current context, not a growing screen transcript. */
internal object CurrentPerceptionContext {
    private val attachment = Regex(
        """<attachment\b([^>]*)>([\s\S]*?)</attachment>""",
        RegexOption.IGNORE_CASE
    )
    private val automaticId = Regex(
        """(?:^|\s)id\s*=\s*["']message_insert_extra_(?:bundle|time|battery|weather|location|notifications)_""",
        RegexOption.IGNORE_CASE
    )
    private val memorySection = Regex("""(?:^|\r?\n)(?:【相关记忆】|\[Related Memories\])""")
    private val sizeAttribute = Regex("""\s+size\s*=\s*(?:"[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)

    fun retainLatest(turns: List<PromptTurn>): List<PromptTurn> {
        // A new input with sensing disabled must not revive a previous screen observation.
        // Tool continuations have no new USER turn, so the current wake's snapshot stays available.
        val latest = turns.indexOfLast { it.kind == PromptTurnKind.USER }
        if (latest < 0) return turns
        return turns.mapIndexed { index, turn ->
            if (index >= latest || turn.kind != PromptTurnKind.USER) turn
            else turn.copy(content = attachment.replace(turn.content) { match ->
                val attributes = match.groupValues[1]
                if (!automaticId.containsMatchIn(attributes)) match.value
                else {
                    // Keep retrieved relationship/factual memories, but never replay the old
                    // screen, notifications or device state as the current observation.
                    val body = match.groupValues[2]
                    val memory = memorySection.findAll(body).lastOrNull()
                    if (memory == null) ""
                    else "<attachment" + sizeAttribute.replace(attributes, "") + ">" +
                        body.substring(memory.range.first).trimStart() + "</attachment>"
                }
            })
        }
    }
}
