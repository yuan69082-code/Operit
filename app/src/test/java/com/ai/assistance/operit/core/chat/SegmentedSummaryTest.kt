package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class SegmentedSummaryTest {
    private val msgs = (1..6).map { ChatMessage(if (it % 2 == 1) "user" else "ai", "第$it条", timestamp = 1_000L * it) }

    @Test fun modelNumbersBecomeTimestampIndexes() {
        val out = """
            § 起#1 止#4｜已结束｜文字做爱
            时间：10-16 19:41 到 10-16 21:11
            内容：做了两轮。
            我的感受：（无）
            § 起#5 止#6｜悬着｜改压缩方案
            时间：10-17 00:01 至今未结束
            内容：在讨论。
        """.trimIndent()
        val segs = SegmentedSummary.segments(out, "chat-1", msgs, emptyList())
        assertEquals(2, segs.size)
        assertTrue(segs[0].contains("索引：chat_id=chat-1 from=1000 to=4000"))
        assertTrue(segs[1].contains("from=5000 to=6000"))
    }

    @Test fun closedSegmentsAreKeptVerbatimAndOpenOneIsReplaced() {
        val first = SegmentedSummary.render(SegmentedSummary.parse(null), SegmentedSummary.segments("""
            § 起#1 止#2｜已结束｜甲
            内容：甲的原样内容
            § 起#3 止#4｜悬着｜乙
            内容：乙进行中
        """.trimIndent(), "c", msgs, emptyList()))
        val parsed = SegmentedSummary.parse(first)
        assertEquals(1, parsed.closed.size)
        assertEquals(1, parsed.open.size)
        val next = SegmentedSummary.segments("""
            § 起#0 止#2｜已结束｜乙
            内容：乙结束了
        """.trimIndent(), "c", msgs.drop(4), parsed.open)
        assertTrue(next.single().contains("from=3000 to=6000"))
        val second = SegmentedSummary.render(parsed, next)
        assertTrue(second.contains("甲的原样内容"))
        assertTrue(second.contains("乙结束了"))
        assertFalse(second.contains("乙进行中"))
        assertEquals(listOf("§ 已结束｜甲", "§ 已结束｜乙"), SegmentedSummary.directory(second).map { it.substringBefore(" 索引") })
    }

    @Test fun legacySummaryIsKeptOnceAsBackground() {
        val parsed = SegmentedSummary.parse("==========对话摘要==========\n【核心任务状态】旧的")
        assertNotNull(parsed.legacy)
        val out = SegmentedSummary.render(parsed, listOf("§ 已结束｜新\n索引：chat_id=c from=1 to=2"))
        assertTrue(SegmentedSummary.isSegmented(out))
        assertEquals(2, SegmentedSummary.parse(out).closed.size)
    }
}