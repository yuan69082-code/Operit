package com.ai.assistance.operit.util

import kotlin.math.min
import kotlin.math.sqrt

/** Bounds the extra bitmap used by OCR; source images are never downsampled here. */
internal object OcrImageSizing {
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 8_000_000

    fun highQualitySize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        // Double arithmetic avoids overflow for large source images. If the source already
        // exceeds the budget, keep its detail without allocating an even larger copy.
        val scale = min(
            2.0,
            min(
                MAX_DIMENSION.toDouble() / maxOf(width, height),
                sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height))
            )
        )
        if (scale <= 1.0) return width to height
        return (width * scale).toInt() to (height * scale).toInt()
    }
}
