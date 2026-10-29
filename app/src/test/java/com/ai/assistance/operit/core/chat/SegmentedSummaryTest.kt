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

    @Test fun manyUnresolvedEventsAndLongSummaryRespectOneRequestBudget() {
        val blocks = (1..100).map { i ->
            "§ 悬着｜事件$i\n索引：chat_id=c from=${i * 1000L} to=${i * 1000L + 100}\n" +
                "内容：" + "未决问题。".repeat(30) + "\n我的感受：" + "需要想想。".repeat(20)
        }
        val body = "【核心任务状态】\n当前正在修上下文。\n" + "进展记录。".repeat(2000) +
            "\n【相处与交流】\n她刚纠正：不要删除经历。\n" + "过往交流。".repeat(1000) +
            "\n【关键信息与上下文】\n未决：还需要验证。\n" + "限制条件。".repeat(1000)
        val archive = body + "\n\n" + SegmentedSummary.render(emptyList(), blocks)
        val prompt = SegmentedSummary.forPrompt(archive)
        assertTrue(prompt.length <= SummaryPromptBudget.MAX_CHARS)
        assertTrue(prompt.contains("当前正在修上下文"))
        assertTrue(prompt.contains("她刚纠正：不要删除经历"))
        assertTrue(prompt.contains("未决：还需要验证"))
        assertTrue(prompt.contains("from=100000 to=100100"))
        assertTrue(prompt.contains("status=悬着"))
        assertTrue(prompt.contains("存档事件共100件"))
        assertEquals(100, SegmentedSummary.parse(archive).open.size)
        assertTrue(SegmentedSummary.parse(prompt).open.size < 100)
        assertTrue(SegmentedSummary.parse(prompt).open.all { SegmentedSummary.index(it) != null })
        assertEquals(prompt, SegmentedSummary.forPrompt(prompt))
    }

    @Test fun resumedOldEventIsRecentByItsEndNotItsStart() {
        val blocks = (1..30).map { i ->
            "§ 已结束｜事件$i\n索引：chat_id=c from=${i * 1000L} to=${i * 1000L + 100}\n内容：经过"
        } + "§ 已结束｜刚解决的旧问题\n索引：chat_id=c from=1 to=999999\n内容：最新纠正已落实"
        assertTrue(SegmentedSummary.forPrompt(SegmentedSummary.render(emptyList(), blocks)).contains("最新纠正已落实"))
    }

    @Test fun plainLegacySummaryIsBoundedOnlyInSummaryTurns() {
        val original = "【核心任务状态】\n当前问题。\n" + "旧的过程。".repeat(5000) + "\n最后的修正。"
        val turn = com.ai.assistance.operit.core.chat.hooks.PromptTurn(
            com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.SUMMARY, original)
        val projected = SegmentedSummary.promptTurns(listOf(turn)).single()
        assertTrue(projected.content.length <= SummaryPromptBudget.MAX_CHARS)
        assertTrue(projected.content.contains("当前问题"))
        assertTrue(projected.content.contains("最后的修正"))
        assertTrue(projected.content.contains("history_message"))
        assertEquals(original, turn.content)
        assertEquals(projected, SegmentedSummary.promptTurns(listOf(projected)).single())
    }

    @Test fun oversizedLegacyTitlesCannotEscapeEventBudget() {
        val archive = SegmentedSummary.render(emptyList(), listOf(
            "§ 悬着｜" + "旧标题".repeat(4000) + "\n索引：chat_id=c from=1 to=2\n内容：完整保存",
            "§ 悬着｜正常事件\n索引：chat_id=c from=3 to=4\n内容：仍在讨论"
        ))
        val prompt = SegmentedSummary.forPrompt(archive)
        assertTrue(prompt.length <= SummaryPromptBudget.EVENT_CHARS)
        assertTrue(prompt.contains("from=3 to=4"))
        assertTrue(prompt.contains("存档事件共2件"))
        assertEquals(2, SegmentedSummary.parse(archive).open.size)
    }

    @Test fun englishAndCustomHeadingsKeepTheirRecentCorrections() {
        val original = "[Core Task Status]\nCurrent topic.\n" + "Earlier details.\n".repeat(2000) +
            "\n[My custom section]\nKeep this agreement.\n" + "Old details.\n".repeat(2000) + "Latest correction."
        val prompt = SegmentedSummary.forPrompt(original)
        assertTrue(prompt.length <= SummaryPromptBudget.MAX_CHARS)
        assertTrue(prompt.contains("Current topic."))
        assertTrue(prompt.contains("Keep this agreement."))
        assertTrue(prompt.contains("Latest correction."))
        val small = "【核心任务状态】\n当前在聊天。"
        assertEquals(small, SegmentedSummary.forPrompt(small))
    }
}
