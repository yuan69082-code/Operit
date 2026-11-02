package com.ai.assistance.operit.core.companion

import org.junit.Assert.*
import org.junit.Test

class StudyReaderPolicyTest {
    @Test fun paginationPreservesTextAndUnicodeAndAbsoluteAnchorOffsets() {
        val text = "开头\n" + "文😀字\n".repeat(1000) + "结尾"
        val pages = StudyReaderPolicy.pages(text, 37)
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        pages.forEachIndexed { i, page ->
            assertTrue(page.end > page.start)
            assertFalse(Character.isLowSurrogate(text[page.start]))
            assertEquals(i, StudyReaderPolicy.pageAt(pages, page.start))
            assertEquals(i, StudyReaderPolicy.pageAt(pages, page.end - 1))
        }
        assertEquals(pages.lastIndex, StudyReaderPolicy.pageAt(pages, text.length))
    }
    @Test fun emptyPhotoChapterHasOnePage() {
        assertEquals(listOf(StudyReaderPolicy.Page(0, 0)), StudyReaderPolicy.pages(""))
        assertEquals(0, StudyReaderPolicy.pageAt(StudyReaderPolicy.pages(""), 0))
    }
}
