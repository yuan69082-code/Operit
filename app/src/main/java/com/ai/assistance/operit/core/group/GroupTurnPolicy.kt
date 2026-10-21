package com.ai.assistance.operit.core.group

/** New public speech lets every other member decide again; silence does not create new work. */
object GroupTurnPolicy {
    data class Control(val action: String, val targets: List<String>)
    private val marker = Regex("""<group_turn\s+action="(quiet|wait_user|end|respond)"(?:\s+targets="([^"]*)")?\s*/>""")
    private val toolBlocks = Regex("""<(tool(?:_result)?(?:_[A-Za-z0-9]+)?)\b[^>]*>[\s\S]*?(?:</\1>|$)""", RegexOption.IGNORE_CASE)
    private val codeBlocks = Regex("""```[\s\S]*?(?:```|$)""")
    fun control(text: String): Control? {
        val body = codeBlocks.replace(toolBlocks.replace(text, ""), "").trimEnd()
        return marker.findAll(body).lastOrNull()?.takeIf { it.range.last == body.lastIndex }?.let {
            Control(it.groupValues[1], it.groupValues[2].split(",").map(String::trim).filter(String::isNotBlank))
        }
    }
    fun publicBody(text: String): String = cleanLegacyPrefix(marker.replace(text, "")).trim()
    fun next(pending: List<String>, members: Set<String>, actor: String, published: Boolean): List<String> {
        val eligible = pending.filter { it in members && it != actor }
        // Keep waiting listeners ahead of members who have already decided. A member's
        // wait/end decision cannot remove anyone else's opportunity to speak.
        return (eligible + if (published) members.filter { it != actor } else emptyList()).distinct()
    }

    fun suppress(action: String?): Boolean = action == "quiet"

    // Only the exact old internal prefix at the beginning is removable. A quote in the body
    // is user-visible content, not metadata. Handles repeated headers including nested ID brackets.
    fun cleanLegacyPrefix(text: String): String {
        var result = text
        val header = Regex("""^\s*\[?历史发言来源[：:][^\r\n]*?(?:这是你自己的发言|这是另一位助手的发言，不是你说过或亲历的内容)\]\s*""")
        while (true) {
            val match = header.find(result) ?: return result
            result = result.substring(match.range.last + 1)
        }
    }
}

