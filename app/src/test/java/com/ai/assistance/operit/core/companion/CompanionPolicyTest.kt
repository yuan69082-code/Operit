package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CompanionPolicyTest {
    @Test fun matchesChineseAndEnglishButReturnsOnlyThreeShortPreviews() {
        val entries = (1..8).map { JSONObject().put("id", "$it").put("title", "录音技巧 $it")
            .put("category", "声音").put("content", "环境噪声会影响录音。".repeat(100)) }
        val selected = CompanionPolicy.related(entries, "录音时遇到环境噪声", Random(42))
        assertEquals(3, selected.size)
        assertEquals(3, selected.map { it.getString("id") }.toSet().size)
        assertTrue(selected.all { it.getString("preview").length <= 180 && !it.has("content") })
        assertTrue(CompanionPolicy.related(entries, "今天吃什么").isEmpty())
    }

    @Test fun repeatedRepliesDoNotBecomeNewNotifications() {
        assertTrue(CompanionPolicy.duplicate("晚安！", listOf("晚安。")))
        assertTrue(CompanionPolicy.duplicate(" ", emptyList()))
        assertFalse(CompanionPolicy.duplicate("刚找到你要的那份资料", listOf("晚安。")))
    }

    @Test fun aiChoosesWakeTimeWithinExplicitBounds() {
        assertEquals(121000L, CompanionPolicy.wakeAt(1000L, 2))
        assertEquals(604801000L, CompanionPolicy.wakeAt(1000L, 10080))
        assertThrows(IllegalArgumentException::class.java) { CompanionPolicy.wakeAt(1000L, 0) }
        assertThrows(IllegalArgumentException::class.java) { CompanionPolicy.wakeAt(1000L, 10081) }
    }
}
