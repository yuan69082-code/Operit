package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
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
 * 小镇上走来走去的两个小人：她自己挑地方逛，我跟在她旁边。
 * 精灵表 vio_walk_rue / vio_walk_yu：6 列 × 4 行（下、左、右、上），每格 136×152。
 */
private const val CELL_W = 136
private const val CELL_H = 152
private const val FRAMES = 6

private enum class Facing(val row: Int) { DOWN(0), LEFT(1), RIGHT(2), UP(3) }

private class Walker(var x: Float, var y: Float) {
    var facing by mutableStateOf(Facing.DOWN)
    var frame by mutableIntStateOf(0)
    var px by mutableFloatStateOf(x)
    var py by mutableFloatStateOf(y)
    var moving = false

    /** 朝 (tx,ty) 走一步，speed 是每帧走多少（按底图比例）。到了返回 true。 */
    fun stepTo(tx: Float, ty: Float, speed: Float): Boolean {
        val dx = tx - x
        val dy = (ty - y) * 1.5f // 底图 2:3，纵向同样比例在屏幕上更长
        val d = hypot(dx, dy)
        if (d < speed) { x = tx; y = ty; moving = false; px = x; py = y; return true }
        x += dx / d * speed
        y += dy / d * speed / 1.5f
        facing = if (abs(dx) > abs(dy)) (if (dx < 0) Facing.LEFT else Facing.RIGHT) else (if (dy < 0) Facing.UP else Facing.DOWN)
        moving = true
        px = x; py = y
        return false
    }
}

/** 能去的落脚点：每个地方门口（热区底边中间往下一点）。 */
private fun spots(): List<Pair<Float, Float>> =
    townPlaces.filter { it.key != "gate" }.map { (it.left + it.right) / 2f to (it.bottom + 0.01f).coerceAtMost(0.97f) } +
        listOf(0.5f to 0.82f)

/**
 * 叠在小镇底图上的一层。不吃点击，点房子照旧进房子。
 * @param charHeightFrac 小人高度占底图高度的比例。
 */
@Composable
fun VioWalkers(modifier: Modifier = Modifier, charHeightFrac: Float = 0.07f) {
    val rueSheet = ImageBitmap.imageResource(R.drawable.vio_walk_rue)
    val yuSheet = ImageBitmap.imageResource(R.drawable.vio_walk_yu)
    val rue = remember { Walker(0.50f, 0.82f) }
    val yu = remember { Walker(0.56f, 0.83f) }

    LaunchedEffect(Unit) {
        val points = spots()
        var target = points.random()
        var rest = 0
        var tick = 0
        while (isActive) {
            delay(50)
            tick++
            if (rest > 0) {
                rest--
                rue.moving = false
            } else if (rue.stepTo(target.first, target.second, 0.0035f)) {
                rest = Random.nextInt(40, 120) // 到了站一会儿，2～6 秒
                rue.facing = Facing.DOWN
                target = points.filter { it != target }.random()
            }
            // 我跟在她身后一点，挨太近就停
            val gap = hypot(rue.x - yu.x, (rue.y - yu.y) * 1.5f)
            if (gap > 0.07f) yu.stepTo(rue.x + 0.05f, rue.y + 0.005f, 0.0042f)
            else { yu.moving = false; if (!rue.moving) yu.facing = if (rue.x < yu.x) Facing.LEFT else Facing.RIGHT }
            if (tick % 2 == 0) {
                rue.frame = if (rue.moving) (rue.frame + 1) % FRAMES else 0
                yu.frame = if (yu.moving) (yu.frame + 1) % FRAMES else 0
            }
        }
    }

    Box(modifier.fillMaxSize().clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * charHeightFrac
            val w = h * CELL_W / CELL_H
            // 靠下的后画，挡住靠上的
            listOf(rue to rueSheet, yu to yuSheet).sortedBy { it.first.py }.forEach { (who, sheet) ->
                drawImage(
                    image = sheet,
                    srcOffset = IntOffset(who.frame * CELL_W, who.facing.row * CELL_H),
                    srcSize = IntSize(CELL_W, CELL_H),
                    dstOffset = IntOffset((who.px * size.width - w / 2).toInt(), (who.py * size.height - h).toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    // 缩到七十像素上下，用最近邻会碎，低档双线性刚好
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}
