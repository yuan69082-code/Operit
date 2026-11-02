package com.ai.assistance.operit.core.companion

/** Pages are views over immutable text; UTF-16 offsets never change when changing reading mode. */
internal object StudyReaderPolicy {
    data class Page(val start: Int, val end: Int)
    fun pages(text: String, limit: Int = 1800): List<Page> {
        require(limit >= 2)
        if (text.isEmpty()) return listOf(Page(0, 0))
        val pages = mutableListOf<Page>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val paragraphEnd = text.lastIndexOf('\n', end - 1) + 1
                if (paragraphEnd > start + limit / 2) end = paragraphEnd
                if (Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end--
            }
            pages.add(Page(start, end)); start = end
        }
        return pages
    }
    fun pageAt(pages: List<Page>, offset: Int): Int {
        require(offset >= 0 && offset <= pages.last().end)
        return pages.indexOfFirst { offset < it.end }.let { if (it < 0) pages.lastIndex else it }
    }
}
