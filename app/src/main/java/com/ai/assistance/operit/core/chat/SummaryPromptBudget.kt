package com.ai.assistance.operit.core.chat

/** Request-only excerpts. The saved summary remains the authoritative, retrievable record. */
internal object SummaryPromptBudget {
    const val MAX_CHARS = 8192
    const val EVENT_CHARS = 3200
    private const val NOTICE = "\n〔摘要节选；未显示不表示已结束或遗忘。完整摘要用 group_chat history_message 读取，message_at 可从 events/windows 的 summary_at 或 history 查询。〕"
    private const val GAP = "\n〔中间省略，需回查〕\n"
    private val heading = Regex("(?m)^(?:【[^\\n】]+】|\\[[^\\n\\]]+])\\s*$")

    fun project(text: String, budget: Int): String {
        if (text.length <= budget) return text
        require(budget >= 512)
        val available = budget - NOTICE.length
        val starts = heading.findAll(text).map { it.range.first }.toList()
        val boundaries = (listOf(0) + starts + text.length).distinct()
        val sections = boundaries.zipWithNext { from, to -> text.substring(from, to).trim() }
            .filter { it.isNotEmpty() }
        // Keep space for each section, so a long chronology cannot evict current state or
        // corrections in the later key-information section. No semantic claims are invented.
        if (sections.size > 12) return excerpt(text, available) + NOTICE
        val quota = (available - (sections.size - 1) * 2) / sections.size
        return sections.joinToString("\n\n") { excerpt(it, quota) } + NOTICE
    }

    private fun excerpt(text: String, budget: Int): String {
        if (text.length <= budget) return text
        val room = budget - GAP.length
        val headEnd = room * 2 / 3
        val tailStart = text.length - (room - headEnd)
        // End/start at sentence or line boundaries; never join halves into an apparent quote.
        val head = text.take(headEnd).let { prefix ->
            val stop = prefix.indexOfLast { it in "\n。！？.!?；;" }
            if (stop >= 0) prefix.take(stop + 1) else ""
        }
        val tail = text.substring(tailStart).let { suffix ->
            val start = suffix.indexOfFirst { it in "\n。！？.!?；;" }
            if (start >= 0) suffix.substring(start + 1) else ""
        }
        return head.trimEnd() + GAP + tail.trimStart()
    }
}
