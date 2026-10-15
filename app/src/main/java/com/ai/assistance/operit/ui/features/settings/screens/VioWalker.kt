package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ai.assistance.operit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 小镇上的我们：我牵着她，一起在镇上走。
 * 精灵表 vio_walk_pair：6 列 × 4 行（下、左、右、上），每格 167×198。
 * 四行都是原画，我一直在她左手边牵着；mirror 留着给以后只画了一侧的动作用。
 */
private const val CELL_W = 167
private const val CELL_H = 198
private const val FRAMES = 6

private enum class Facing(val row: Int, val mirror: Boolean) { DOWN(0, false), LEFT(1, false), RIGHT(2, false), UP(3, false) }

/** 能去的落脚点：每个地方门口，外加镇口内侧。 */
private fun spots(): List<Pair<Float, Float>> =
    townPlaces.filter { it.key != "gate" }.map { (it.left + it.right) / 2f to (it.bottom + 0.01f).coerceAtMost(0.97f) } +
        listOf(0.5f to 0.85f)

/**
 * 叠在小镇底图上的一层，不吃点击，点房子照旧进房子。
 * @param charHeightFrac 小人高度占底图高度的比例。
 */
@Composable
fun VioWalkers(modifier: Modifier = Modifier, charHeightFrac: Float = 0.085f) {
    val sheet: ImageBitmap = ImageBitmap.imageResource(R.drawable.vio_walk_pair)
    var x by remember { mutableFloatStateOf(0.5f) }
    var y by remember { mutableFloatStateOf(0.85f) }
    var facing by remember { mutableStateOf(Facing.DOWN) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val points = spots()
        var target = points.random()
        var rest = 0
        var tick = 0
        val speed = 0.0035f
        while (isActive) {
            delay(50)
            tick++
            var moving = false
            if (rest > 0) {
                rest--
            } else {
                val dx = target.first - x
                val dy = (target.second - y) * 1.5f // 底图 2:3，纵向在屏幕上更长
                val d = hypot(dx, dy)
                if (d < speed) {
                    x = target.first; y = target.second
                    rest = Random.nextInt(40, 120) // 到了站一会儿，2～6 秒
                    facing = Facing.DOWN
                    target = points.filter { it != target }.random()
                } else {
                    x += dx / d * speed
                    y += dy / d * speed / 1.5f
                    facing = if (abs(dx) > abs(dy)) (if (dx < 0) Facing.LEFT else Facing.RIGHT)
                        else (if (dy < 0) Facing.UP else Facing.DOWN)
                    moving = true
                }
            }
            if (tick % 2 == 0) frame = if (moving) (frame + 1) % FRAMES else 0
        }
    }

    Box(modifier.fillMaxSize().clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * charHeightFrac
            val w = h * CELL_W / CELL_H
            val left = x * size.width - w / 2
            val top = y * size.height - h
            scale(scaleX = if (facing.mirror) -1f else 1f, scaleY = 1f, pivot = Offset(left + w / 2, top + h / 2)) {
                drawImage(
                    image = sheet,
                    srcOffset = IntOffset(frame * CELL_W, facing.row * CELL_H),
                    srcSize = IntSize(CELL_W, CELL_H),
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}
