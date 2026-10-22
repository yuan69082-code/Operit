package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrImageSizingTest {
    @Test
    fun tallPhoneScreensStillReceiveBoundedUpscaling() {
        for ((width, height) in listOf(1080 to 2400, 1440 to 3200, 2400 to 1080)) {
            val (scaledWidth, scaledHeight) = OcrImageSizing.highQualitySize(width, height)
            assertTrue(scaledWidth > width)
            assertTrue(scaledHeight > height)
            assertTrue(maxOf(scaledWidth, scaledHeight) <= 4096)
            assertTrue(scaledWidth.toLong() * scaledHeight <= 8_000_000L)
            // Integer rounding can move an edge by less than one pixel.
            assertTrue(kotlin.math.abs(scaledWidth.toDouble() / scaledHeight - width.toDouble() / height) < 0.001)
        }
    }

    @Test
    fun squareScreensDoNotAllocateA4096SquaredBitmap() {
        val (width, height) = OcrImageSizing.highQualitySize(2048, 2048)
        assertTrue(width > 2048)
        assertEquals(width, height)
        assertTrue(width.toLong() * height <= 8_000_000L)
    }

    @Test
    fun smallScreensAreNotEnlargedBeyondTwoTimes() {
        assertEquals(1418 to 3072, OcrImageSizing.highQualitySize(709, 1536))
    }

    @Test
    fun largeSourcesKeepTheirOriginalPixelsWithoutOverflow() {
        for (size in listOf(4096 to 2160, 1080 to 10000, 3000 to 3000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertEquals(size, OcrImageSizing.highQualitySize(size.first, size.second))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        OcrImageSizing.highQualitySize(0, 2400)
    }
}
