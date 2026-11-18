package com.ai.assistance.operit.data.repository

import org.junit.Assert.*
import org.junit.Test

class ChineseRecallTest {
    @Test fun explicitlyQuotedNegativeAnswerRemainsSearchable() {
        assertTrue("不行" in ChineseRecall.terms("她说“不行”的那次", listOf("不行")))
        assertFalse("不行" in ChineseRecall.terms("行不行", listOf("不行")))
    }
    @Test fun conversationalFragmentsCannotRecallUnrelatedMemories() {
        val terms = ChineseRecall.terms("我4点才吃完的晚饭，我又不是猪", listOf("晚饭", "不是"))
        assertFalse("我又" in terms)
        assertFalse("又不" in terms)
        assertFalse("不是" in terms)
        val rows = listOf(ChineseRecall.Document(1, "抖音", "我又不是故意的"),
            ChineseRecall.Document(2, "晚饭", "她四点吃完了馄饨"))
        assertEquals(listOf(2L), ChineseRecall.rank(rows, terms).map { it.id })
    }
    @Test fun unseenShortNameSurvivesWithoutFunctionWordFragments() {
        val terms = ChineseRecall.terms("你让我看祁煜咋了", listOf("看祁煜"))
        assertTrue("祁煜" in terms)
        assertFalse("你让" in terms)
    }
    @Test fun shortExcerptFindsTheSpecificEventRatherThanAnEarlierGenericWord() {
        val body = "晚饭" + "旧的日常记录。".repeat(80) + "周二四点吃完馄饨，已经吃饱。" + "其他事情。".repeat(80)
        val excerpt = ChineseRecall.excerpt(body, listOf("晚饭", "四点", "馄饨"), 218)
        assertTrue(excerpt.contains("四点吃完馄饨"))
        assertTrue(excerpt.length <= 220)
    }

    @Test fun shortChineseNameMatchesBodyWithAnUnrelatedTitle() {
        val docs = listOf(ChineseRecall.Document(1, "周二的聊天", "阿屿提议一起看天文摄影"), ChineseRecall.Document(2, "随记", "今天看了一本书"))
        val terms = ChineseRecall.terms("阿屿提议的天文摄影", listOf("提议", "天文摄影"))
        assertTrue("阿屿" in terms)
        assertEquals(1L, ChineseRecall.rank(docs, terms).first().id)
    }
    @Test fun confirmedAliasesExpandBothDirections() {
        val aliases = mapOf("阿屿" to "沈知屿")
        assertTrue("沈知屿" in ChineseRecall.terms("阿屿说了什么", emptyList(), aliases))
        assertTrue("阿屿" in ChineseRecall.terms("沈知屿的提议", emptyList(), aliases))
        assertFalse("沈知屿" in ChineseRecall.terms("克劳德的提议", emptyList(), aliases))
    }
    @Test fun rankNeverIntroducesAnOutOfScopeDocument() {
        val docs = listOf(ChineseRecall.Document(7, "自己的记录", "摄影"))
        assertEquals(listOf(7L), ChineseRecall.rank(docs, listOf("摄影")).map { it.id })
        assertTrue(ChineseRecall.rank(emptyList(), listOf("摄影")).isEmpty())
    }
    @Test fun rareClueOutranksRepeatedGenericClue() {
        val docs = (1L..20L).map { ChineseRecall.Document(it, "聊天", if (it == 8L) "克劳德商量摄影" else "我们商量吃饭") }
        assertEquals(8L, ChineseRecall.rank(docs, listOf("商量", "克劳德")).first().id)
    }
    @Test fun excerptIncludesMatchAwayFromBeginningAndStaysBounded() {
        val body = "开头".repeat(1000) + "克劳德在周二提出摄影计划" + "结尾".repeat(1000)
        val result = ChineseRecall.excerpt(body, listOf("克劳德"))
        assertTrue(result.contains("克劳德在周二")); assertTrue(result.length <= 702)
    }
    @Test fun exactNamesDoNotNeedSemanticEmbedding() {
        assertEquals(9L, ChineseRecall.rank(listOf(ChineseRecall.Document(9, "纪念", "与园园确认的事情")), listOf("园园")).single().id)
    }
    @Test fun quotedBookTitleIsRetained() {
        assertTrue("小王子" in ChineseRecall.terms("读过《小王子》吗", emptyList()))
    }
}
