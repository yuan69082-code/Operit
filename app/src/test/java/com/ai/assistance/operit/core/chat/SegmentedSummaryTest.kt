package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class SegmentedSummaryTest {
    private val msgs = (1..6).map { ChatMessage(if (it % 2 == 1) "user" else "ai", "第" + it + "条", timestamp = 1_000L * it) }

    @Test fun modelNumbersBecomeTimestampIndexes() {
        val out = listOf(
            "§ 起#1 止#4｜已结束｜你和我文字做爱",
            "时间：10月16日 19:41 到 21:11",
            "内容：做了两轮。",
            "我的感受：模型乱写的",
            "§ 起#5 止#6｜悬着｜改压缩方案",
            "内容：在讨论。"
        ).joinToString("\n")
        val segs = SegmentedSummary.segments(out, "chat-1", msgs, emptyList())
        assertEquals(2, segs.size)
        assertTrue(segs[0].contains("索引：chat_id=chat-1 from=1000 to=4000"))
        assertFalse(segs[0].contains("模型乱写的"))
        assertTrue(segs[1].contains("from=5000 to=6000"))
    }

    @Test fun feelingsComeOnlyFromMyOwnLog() {
        val segs = SegmentedSummary.segments("§ 起#1 止#2｜已结束｜甲\n内容：a\n§ 起#3 止#6｜悬着｜乙\n内容：b", "c", msgs, emptyList())
        val out = SegmentedSummary.withFeelings(segs, listOf(SegmentedSummary.Feeling(4_500L, "有点酸")))
        assertTrue(out[0].endsWith("我的感受：（我没记）"))
        assertTrue(out[1].contains("有点酸"))
    }

    @Test fun closedSegmentsSurviveAndOriginalSummaryIsSeparable() {
        val first = SegmentedSummary.render(emptyList(),
            SegmentedSummary.segments("§ 起#1 止#2｜已结束｜甲\n内容：甲的原样内容\n§ 起#3 止#4｜悬着｜乙\n内容：乙进行中", "c", msgs, emptyList()))
        val whole = "==========对话摘要==========\n【核心任务状态】原摘要\n\n" + first
        assertEquals("==========对话摘要==========\n【核心任务状态】原摘要", SegmentedSummary.stripBlock(whole))
        val parsed = SegmentedSummary.parse(whole)
        assertEquals(1, parsed.closed.size)
        assertEquals(1, parsed.open.size)
        val next = SegmentedSummary.segments("§ 起#0 止#2｜已结束｜乙\n内容：乙结束了", "c", msgs.drop(4), parsed.open)
        assertTrue(next.single().contains("from=3000 to=6000"))
        val second = SegmentedSummary.render(parsed.closed, next)
        assertTrue(second.contains("甲的原样内容"))
        assertTrue(second.contains("乙结束了"))
        assertFalse(second.contains("乙进行中"))
        assertEquals(2, SegmentedSummary.directory(second).size)
    }

    @Test fun archivedEventsOutliveThePromptWindowAndUnresolvedEventsStayVisible() {
        val blocks = (1..30).map { i ->
            "§ ${if (i == 1) "悬着" else "已结束"}｜事件$i\n索引：chat_id=c from=${i * 1000L} to=${i * 1000L + 100}\n内容：第${i}件的精华\n我的感受：平静"
        }
        val archive = SegmentedSummary.render(emptyList(), blocks)
        val prompt = SegmentedSummary.forPrompt(archive)
        val saved = SegmentedSummary.parse(archive)
        val shown = SegmentedSummary.parse(prompt)
        assertEquals(30, saved.closed.size + saved.open.size)
        assertEquals(13, shown.closed.size + shown.open.size)
        assertTrue(prompt.contains("第1件的精华"))
        assertFalse(prompt.contains("第2件的精华"))
        assertTrue(archive.contains("第2件的精华"))
        assertEquals(archive, SegmentedSummary.render(emptyList(), blocks))
        assertEquals(prompt, SegmentedSummary.forPrompt(prompt))
        assertEquals("", SegmentedSummary.stripBlock(prompt))
    }

    @Test fun longLegacyEventIsBoundedAndCompactionIsStable() {
        val block = "§ 已结束｜旧事件\n索引：chat_id=c from=1000 to=2000\n内容：" + "经过".repeat(1000) +
            "\n我的感受：" + "我当时很难过。".repeat(100)
        val once = SegmentedSummary.compact(block)
        assertTrue(once.length < 400)
        assertTrue(once.contains("节选"))
        assertEquals(once, SegmentedSummary.compact(once))
        assertEquals(1000L, SegmentedSummary.index(once)!!.from)
        assertEquals(2000L, SegmentedSummary.index(once)!!.to)
    }

    @Test fun multipleOpenEventsUseTheirOwnContinuationIndex() {
        val open = listOf(
            "§ 悬着｜甲\n索引：chat_id=c from=1000 to=2000\n内容：甲未完成",
            "§ 悬着｜乙\n索引：chat_id=c from=3000 to=4000\n内容：乙未完成"
        )
        val fresh = SegmentedSummary.segments("§ 起#0 止#2 续接=3000｜已结束｜乙\n内容：乙解决了", "c", msgs.drop(4), open)
        assertEquals(3000L, SegmentedSummary.index(fresh.single())!!.from)
        assertEquals(6000L, SegmentedSummary.index(fresh.single())!!.to)
    }

    @Test fun recallResultsAreNeverCompactedAsIfTheyWereTheCurrentSummary() {
        val original = SegmentedSummary.render(emptyList(), listOf("§ 已结束｜工具里的摘要\n内容：完整原文"))
        val turn = com.ai.assistance.operit.core.chat.hooks.PromptTurn(
            com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.TOOL_RESULT, original)
        assertEquals(turn, SegmentedSummary.promptTurns(listOf(turn)).single())
    }
}
