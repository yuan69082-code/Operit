package com.ai.assistance.operit.util

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class PpOcrPostprocessingTest {
    private val labels = listOf("", "园", "你", " ", "A")

    private fun scores(vararg tokens: Int): FloatBuffer = FloatBuffer.wrap(
        FloatArray(tokens.size * labels.size).also { values ->
            tokens.forEachIndexed { index, label -> values[index * labels.size + label] = 1f }
        }
    )

    @Test fun ctcKeepsRepeatedChineseCharactersSeparatedByBlank() {
        assertEquals("园园", PpOcrPostprocessing.decode(scores(1, 1, 0, 1, 1), 0, 5, labels))
    }

    @Test fun ctcPreservesMixedTextAndSpaces() {
        assertEquals("你 A", PpOcrPostprocessing.decode(scores(0, 2, 3, 4, 4, 0), 0, 6, labels))
    }

    @Test fun ctcUsesBatchOffsetWithoutReadingTheOtherLine() {
        assertEquals("你", PpOcrPostprocessing.decode(scores(1, 1, 0, 2, 2, 0), 3 * labels.size, 3, labels))
    }

    @Test fun blankOnlyDoesNotInventText() {
        assertEquals("", PpOcrPostprocessing.decode(scores(0, 0), 0, 2, labels))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedOutputIsRejected() {
        PpOcrPostprocessing.decode(scores(1), 0, 2, labels)
    }

    @Test fun readingOrderDoesNotFollowMlKitBlockOrder() {
        val first = OcrLineRegion(100, 350, 560, 385)
        val wrapped = OcrLineRegion(100, 395, 160, 430)
        val nextParagraph = OcrLineRegion(100, 455, 560, 490)
        assertEquals(listOf(first, wrapped, nextParagraph),
            PpOcrPostprocessing.readingOrder(listOf(wrapped, nextParagraph, first)))
    }

    @Test fun sameRowWithSlightBaselineDifferencesReadsLeftToRight() {
        val left = OcrLineRegion(20, 140, 85, 173)
        val middle = OcrLineRegion(120, 137, 210, 171)
        val right = OcrLineRegion(235, 138, 386, 172)
        assertEquals(listOf(left, middle, right), PpOcrPostprocessing.readingOrder(listOf(right, middle, left)))
    }
}
