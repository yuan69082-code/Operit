package com.ai.assistance.operit.core.companion

import java.time.Instant
import java.time.ZoneId

/**
 * 枝芽：把最近几天聊过的话摆到自主探索那一轮面前。
 * 不替助手提炼题目，挑不挑、挑哪个、怎么写题目都由助手自己决定；
 * 软件只负责把材料摆出来，并在存成来源时标明是哪天的聊天长出来的。
 */
object BranchSprout {
    private val originPattern = Regex("^chat:\\d{4}-\\d{2}-\\d{2}$")

    /** 聊天来源标记，格式 chat:YYYY-MM-DD。 */
    fun validOrigin(origin: String) = originPattern.matches(origin)

    /** 从聊天长出来的搜索题目不能带她的名字或称呼，防止把私人信息送去公开搜索。 */
    private val privateWords = listOf("园园", "郑园园", "沈知屿", "阿屿")
    // 英文称呼按整词匹配，免得 true、construe 这类词被误拦
    private val privateLatin = Regex("\\b(rue|cove)\\b", RegexOption.IGNORE_CASE)
    private val coordinate = Regex("\\d{2}\\.\\d{4,}")

    fun privacyProblem(address: String): String? {
        privateWords.firstOrNull { address.contains(it) }?.let { return "搜索题目里带了私人称呼「$it」，换成泛的说法" }
        privateLatin.find(address)?.let { return "搜索题目里带了私人称呼「${it.value}」，换成泛的说法" }
        if (coordinate.containsMatchIn(address)) return "搜索题目里像是带了坐标，去掉"
        return null
    }

    data class Line(val at: Long, val sender: String, val text: String)

    /**
     * 最近 [days] 天的聊天按天摘出至多 [max] 句，均匀抽样，每句截短。
     * 只是材料，不是题目。
     */
    fun digest(lines: List<Line>, now: Long, days: Int = 3, max: Int = 30, clip: Int = 50): String {
        val zone = ZoneId.systemDefault()
        val since = now - days * 86_400_000L
        val picked = lines.filter { it.at in since..now && it.text.isNotBlank() }
            // 附带的时间/屏幕/记忆附件不是她说的话，截掉
            .map { it.copy(text = it.text.substringBefore("<attachment").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()) }
            .filter { it.text.length >= 4 }
        if (picked.isEmpty()) return "最近${days}天没有可用的聊天。"
        val step = maxOf(1.0, picked.size.toDouble() / max)
        val sample = (0 until minOf(max, picked.size)).map { picked[(it * step).toInt()] }
        return sample.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().toString() }
            .entries.joinToString("\n") { (day, items) ->
                "$day\n" + items.joinToString("\n") { "  ${if (it.sender == "user") "她" else "我"}：${it.text.take(clip)}" }
            }
    }
}
