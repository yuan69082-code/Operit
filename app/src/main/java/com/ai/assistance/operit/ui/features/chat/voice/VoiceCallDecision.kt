package com.ai.assistance.operit.ui.features.chat.voice

import com.ai.assistance.operit.util.ChatUtils

/** A reply is never implicitly treated as consent to answer the phone. */
object VoiceCallDecision {
    enum class Action { ACCEPT, REJECT, WAIT }
    data class Result(val action: Action, val reason: String, val waitMillis: Long = 0)
    private val terminal = Regex("""<voice_call_(accept|reject)\s*/>|<voice_call_wait\s+seconds=["'](\d+)["']\s*/>""")

    fun parse(raw: String): Result {
        val text = ChatUtils.stripOpenAiResponsesProtocolMarkup(ChatUtils.removeThinkingContent(raw)).trim()
        val tags = terminal.findAll(text).toList()
        check(tags.size == 1 && tags.single().range.last == text.lastIndex) { "对方未返回明确的接听决定，电话尚未接通。" }
        val tag = tags.single()
        val reason = VoiceCallPublicText.clean(text.substring(0, tag.range.first))
        return when (tag.groupValues[1]) {
            "accept" -> Result(Action.ACCEPT, reason)
            "reject" -> Result(Action.REJECT, reason)
            else -> {
                val seconds = checkNotNull(tag.groupValues[2].toLongOrNull()) { "等待时间无效，电话尚未接通。" }
                check(seconds > 0 && seconds <= Long.MAX_VALUE / 1000) { "等待时间无效，电话尚未接通。" }
                Result(Action.WAIT, reason, seconds * 1000)
            }
        }
    }
}
