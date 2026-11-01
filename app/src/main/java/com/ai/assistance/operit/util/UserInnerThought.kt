package com.ai.assistance.operit.util

/** User-authored context, never a provider's assistant reasoning channel. Stored in ordinary content
 * so history, queues, retries, exports and every provider retain the same attribution. */
object UserInnerThought {
    private const val LEGACY_OPEN = "[用户双栏消息：心里话是用户未说出口的想法，AI可以理解，但不要当作用户已经说出口的话；正文才是实际说出的话。这不是AI的思考过程。]\n<user_inner_thought>"
    private const val LEGACY_CLOSE = "</user_inner_thought>\n[正文／实际说出的话]"

    private const val OPEN = "[人类思考链]\n<user_inner_thought>"
    private const val CLOSE = "</user_inner_thought>\n【正文回复】"
    const val EMPTY = "【本轮人类思考链为空】"
    private val block = Regex(
        "(?:" + Regex.escape(OPEN) + "|" + Regex.escape(LEGACY_OPEN) + ")(.*?)(?:" +
            Regex.escape(CLOSE) + "|" + Regex.escape(LEGACY_CLOSE) + ")\\n?",
        RegexOption.DOT_MATCHES_ALL,
    )

    data class Parts(val thought: String, val spoken: String, val spokenOffset: Int = 0, val present: Boolean = false)

    fun encode(thought: String, spoken: String, includeEmpty: Boolean = false): String {
        if (thought.isEmpty() && !includeEmpty) return spoken
        return OPEN + (if (thought.isBlank()) EMPTY else escape(thought)) + CLOSE + "\n" + spoken
    }

    fun decode(content: String): Parts {
        val match = block.find(content) ?: return Parts("", content)
        val offset = match.range.last + 1
        val thought = unescape(match.groupValues[1]).let { if (it == EMPTY) "" else it }
        return Parts(thought, content.substring(0, match.range.first) + content.substring(offset),
            offset - match.range.first, present = true)
    }

    fun withoutThoughts(content: String): String = block.replace(content, "")

    /** Re-project every stored block on each request, including merged and legacy messages. */
    fun forModel(content: String, canRead: Boolean): String = block.replace(content) { match ->
        if (!canRead) "" else {
            val thought = unescape(match.groupValues[1]).let { if (it == EMPTY) "" else it }
            encode(thought, "", includeEmpty = true)
        }
    }

    // Escaping prevents a thought containing attachment/think tags from becoming app markup.
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
