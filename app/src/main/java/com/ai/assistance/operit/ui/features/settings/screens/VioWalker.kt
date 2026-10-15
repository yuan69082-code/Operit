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

/**
 * 镇上的路。底图是一张画，代码不知道哪里是屋顶哪里是石板路，
 * 以前直线走就会踩房顶。现在只沿着这些路口走（坐标按底图比例，照园园 10-15 的截图对的）。
 * 北边瞭望塔、镇公所、工坊那几条巷子还没对，先不去。
 */
private val roadNodes: Map<String, Pair<Float, Float>> = mapOf(
    "gate" to (0.50f to 0.83f),      // 镇口内侧
    "south" to (0.50f to 0.74f),     // 念头池南
    "ledger" to (0.28f to 0.67f),    // 账房门口
    "west" to (0.31f to 0.60f),      // 账房和池子之间
    "nw" to (0.33f to 0.535f),       // 我们家篱笆角
    "neighbors" to (0.17f to 0.465f),// 邻居门口
    "north" to (0.50f to 0.535f),    // 念头池北
    "home" to (0.45f to 0.49f),      // 我们家门口
    "ne" to (0.66f to 0.535f),
    "library" to (0.68f to 0.475f),  // 图书馆门口
    "board" to (0.76f to 0.56f),     // 公告栏前
    "east" to (0.69f to 0.62f),      // 念头池东
    "games" to (0.76f to 0.64f),     // 桌游馆门口
    "well" to (0.55f to 0.75f),      // 回音井前
)
private val roadEdges: List<Pair<String, String>> = listOf(
    "gate" to "south", "south" to "ledger", "south" to "well", "ledger" to "west", "west" to "nw",
    "nw" to "neighbors", "nw" to "north", "north" to "home", "north" to "ne", "ne" to "library",
    "ne" to "board", "ne" to "east", "east" to "games", "east" to "well", "board" to "games",
)
/** 会停下来待一会儿的地方。 */
private val stops = listOf("gate", "home", "neighbors", "library", "board", "games", "well", "ledger")
/** 沿路找最短的路口序列（BFS，路口少，够用）。 */
private fun route(from: String, to: String): List<String> {
    val adj = roadEdges.flatMap { listOf(it, it.second to it.first) }.groupBy({ it.first }, { it.second })
    val prev = mutableMapOf(from to from)
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (cur == to) break
        for (n in adj[cur].orEmpty()) if (n !in prev) { prev[n] = cur; queue.addLast(n) }
    }
    if (to !in prev) return listOf(to)
    val path = mutableListOf(to)
    while (path.last() != from) path.add(prev.getValue(path.last()))
    return path.reversed().drop(1)
}
/**
 * 叠在小镇底图上的一层，不吃点击，点房子照旧进房子。
 * @param charHeightFrac 小人高度占底图高度的比例。
 */
@Composable
fun VioWalkers(modifier: Modifier = Modifier, charHeightFrac: Float = 0.062f) {
    val sheet: ImageBitmap = ImageBitmap.imageResource(R.drawable.vio_walk_pair)
    var x by remember { mutableFloatStateOf(0.5f) }
    var y by remember { mutableFloatStateOf(0.85f) }
    var facing by remember { mutableStateOf(Facing.DOWN) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var at = "gate"
        x = roadNodes.getValue(at).first; y = roadNodes.getValue(at).second
        var path = route(at, stops.filter { it != at }.random())
        var rest = Random.nextInt(40, 120)
        var tick = 0
        val speed = 0.0025f
        while (isActive) {
            delay(50)
            tick++
            var moving = false
            if (rest > 0) {
                rest--
            } else {
                val next = path.first()
                val target = roadNodes.getValue(next)
                val dx = target.first - x
                val dy = (target.second - y) * 1.5f // 底图 2:3，纵向在屏幕上更长
                val d = hypot(dx, dy)
                if (d < speed) {
                    x = target.first; y = target.second
                    at = next
                    path = path.drop(1)
                    if (path.isEmpty()) {
                        rest = Random.nextInt(40, 120) // 到了站一会儿，2～6 秒
                        facing = Facing.DOWN
                        path = route(at, stops.filter { it != at }.random())
                    }
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
