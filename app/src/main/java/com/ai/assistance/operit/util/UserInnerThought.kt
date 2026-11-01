package com.ai.assistance.operit.util

/** User-authored context, never a provider's assistant reasoning channel. Stored in ordinary content
 * so history, queues, retries, exports and every provider retain the same attribution. */
object UserInnerThought {
    private const val OPEN = "[用户双栏消息：心里话是用户未说出口的想法，AI可以理解，但不要当作用户已经说出口的话；正文才是实际说出的话。这不是AI的思考过程。]\n<user_inner_thought>"
    private const val CLOSE = "</user_inner_thought>\n[正文／实际说出的话]"

    data class Parts(val thought: String, val spoken: String, val spokenOffset: Int = 0)

    fun encode(thought: String, spoken: String): String {
        if (thought.isEmpty()) return spoken
        return OPEN + escape(thought) + CLOSE + "\n" + spoken
    }

    fun decode(content: String): Parts {
        val start = content.indexOf(OPEN)
        if (start < 0) return Parts("", content)
        val end = content.indexOf(CLOSE, start + OPEN.length)
        if (end < 0) return Parts("", content)
        val bodyStart = end + CLOSE.length
        val offset = if (content.getOrNull(bodyStart) == '\n') bodyStart + 1 else bodyStart
        return Parts(
            unescape(content.substring(start + OPEN.length, end)),
            content.substring(0, start) + content.substring(offset),
            offset - start,
        )
    }

    // Escaping prevents a thought containing attachment/think tags from becoming app markup.
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
