package com.ai.assistance.operit.core.chat

import org.junit.Assert.*
import org.junit.Test

class HistoryTextPageTest {
    @Test fun aLongMessageCanBeReconstructedWithoutGapsOrBrokenEmoji() {
        val original = "a".repeat(5999) + "😀" + "后续的完整工具结果".repeat(1500)
        val restored = StringBuilder()
        var offset = 0
        while (true) {
            val page = HistoryTextPage.read(original, offset)
            assertTrue(page.text.length <= HistoryTextPage.SIZE)
            assertFalse(page.text.lastOrNull()?.isHighSurrogate() == true)
            restored.append(page.text)
            val next = page.next ?: break
            assertTrue(next > offset)
            offset = next
        }
        assertEquals(original, restored.toString())
    }

    @Test fun endOfMessageAndEmptyMessageAreExplicitlyComplete() {
        assertNull(HistoryTextPage.read("", 0).next)
        val page = HistoryTextPage.read("完整", 2)
        assertEquals("", page.text)
        assertEquals(2, page.total)
        assertNull(page.next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidOffsetDoesNotPretendTheMessageEnded() {
        HistoryTextPage.read("abc", 4)
    }
}
