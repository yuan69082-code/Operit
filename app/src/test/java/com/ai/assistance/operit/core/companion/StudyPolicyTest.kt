package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StudyPolicyTest {
    @Test fun splittingLongBooksPreservesEveryCharacterAndUnicodeBoundary() {
        val text = "夜色\n\n".repeat(3000) + "x".repeat(11999) + "🐱" + "正文".repeat(12000)
        val pages = StudyPolicy.split(text)
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.all { it.length <= 12000 && !Character.isLowSurrogate(it.first()) && !Character.isHighSurrogate(it.last()) })
    }
    @Test fun anchorRejectsOldOrMismatchedQuotesInsteadOfMovingNotes() {
        StudyPolicy.anchor("这是原文。", 2, 4, "原文")
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.anchor("这是改稿。", 2, 4, "原文") }
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.anchor("正文", 0, 3, "正文") }
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.anchor("🐱", 0, 1, "\uD83D") }
    }
    @Test fun drawingsMustStayWithinNormalizedCanvasAndResourceBounds() {
        StudyPolicy.drawing(JSONArray("[[[0,0],[1,1]]]"))
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.drawing(JSONArray("[[[-1,0],[1,1]]]")) }
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.drawing(JSONArray("[[[0,2]]]")) }
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.style("invalid", "#123456") }
        assertThrows(IllegalArgumentException::class.java) { StudyPolicy.style("hand", "transparent") }
    }
}
