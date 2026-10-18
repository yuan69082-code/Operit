package com.ai.assistance.operit.core.chat

/** A bounded response is a page, never the apparent end of a truncated message. */
internal object HistoryTextPage {
    const val SIZE = 6000
    data class Page(val text: String, val total: Int, val next: Int?)

    fun read(text: String, offset: Int): Page {
        require(offset in 0..text.length) { "offset 超出原文范围" }
        var end = (offset.toLong() + SIZE).coerceAtMost(text.length.toLong()).toInt()
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return Page(text.substring(offset, end), text.length, end.takeIf { it < text.length })
    }
}
