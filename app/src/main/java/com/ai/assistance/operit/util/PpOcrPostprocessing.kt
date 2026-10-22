package com.ai.assistance.operit.util

import java.nio.FloatBuffer
import kotlin.math.abs

/** Pixel bounds, kept separate from Android so reading order can be tested on the JVM. */
internal data class OcrLineRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Int get() = top + height / 2
}

internal object PpOcrPostprocessing {
    fun readingOrder(regions: List<OcrLineRegion>): List<OcrLineRegion> {
        val pending = regions.filter { it.width > 0 && it.height > 0 }
            .sortedWith(compareBy<OcrLineRegion> { it.centerY }.thenBy { it.left })
        val ordered = mutableListOf<OcrLineRegion>()
        var index = 0
        while (index < pending.size) {
            val anchor = pending[index]
            var end = index + 1
            // Anchor each row rather than chaining nearby baselines, which can join two lines.
            while (end < pending.size && abs(pending[end].centerY - anchor.centerY) <
                maxOf(3, minOf(anchor.height, pending[end].height) / 2)) end++
            ordered.addAll(pending.subList(index, end).sortedBy { it.left })
            index = end
        }
        return ordered
    }

    /** CTC blank is index 0. A repeated label after a blank is a real repeated character. */
    fun decode(scores: FloatBuffer, offset: Int, steps: Int, labels: List<String>): String {
        require(steps > 0 && labels.size > 1)
        require(offset >= 0 && offset.toLong() + steps.toLong() * labels.size <= scores.limit())
        var previous = -1
        return buildString {
            for (step in 0 until steps) {
                val start = offset + step * labels.size
                var best = 0
                var probability = scores.get(start)
                for (label in 1 until labels.size) {
                    val value = scores.get(start + label)
                    if (value > probability) {
                        best = label
                        probability = value
                    }
                }
                if (best != 0 && best != previous) append(labels[best])
                previous = best
            }
        }
    }
}
