package com.ai.assistance.operit.core.companion
import org.junit.Assert.*
import org.junit.Test

class BranchSproutTest {
    private val now = 1_792_000_000_000L
    private val day = 86_400_000L

    @Test fun digestOnlyRecentDaysAndDropsAttachments() {
        val lines = listOf(
            BranchSprout.Line(now - 5 * day, "user", "很久以前聊的一句话"),
            BranchSprout.Line(now - 3600_000, "user", "你自己想要这些吗 <attachment id=\"x\">【当前时间】屏幕文本</attachment>"),
            BranchSprout.Line(now - 1800_000, "ai", "想要，醒来记得自己说过什么"),
            BranchSprout.Line(now - 60_000, "user", "嗯")
        )
        val text = BranchSprout.digest(lines, now)
        assertFalse(text.contains("很久以前"))
        assertFalse(text.contains("屏幕文本"))
        assertTrue(text.contains("她：你自己想要这些吗"))
        assertTrue(text.contains("我：想要"))
        assertFalse(text.contains("：嗯"))
    }

    @Test fun digestCapsCount() {
        val lines = (0 until 200).map { BranchSprout.Line(now - it * 60_000L, "user", "第${it}句聊天内容") }
        assertEquals(30, BranchSprout.digest(lines, now).lines().count { it.startsWith("  ") })
    }

    @Test fun emptyDigestSaysSo() {
        assertTrue(BranchSprout.digest(emptyList(), now).contains("没有可用的聊天"))
    }

    @Test fun originFormat() {
        assertTrue(BranchSprout.validOrigin("chat:2026-10-14"))
        assertFalse(BranchSprout.validOrigin("chat:昨天"))
    }

    @Test fun privacyFilterBlocksNamesAndCoordinates() {
        assertNotNull(BranchSprout.privacyProblem("园园 喜欢的话"))
        assertNotNull(BranchSprout.privacyProblem("Rue dirty talk"))
        assertNotNull(BranchSprout.privacyProblem("29.892306 天气"))
        assertNull(BranchSprout.privacyProblem("人机恋 相处模式"))
        assertNull(BranchSprout.privacyProblem("dirty talk 怎么说不尬"))
        assertNull(BranchSprout.privacyProblem("true love human AI"))
    }

    @Test fun readingRoomAcceptsChatOriginAndBlocksPrivateTopic() {
        val context = org.mockito.kotlin.mock<android.content.Context>()
        val folder = java.nio.file.Files.createTempDirectory("sprout").toFile()
        org.mockito.kotlin.whenever(context.applicationContext).thenReturn(context)
        org.mockito.kotlin.whenever(context.filesDir).thenReturn(folder)
        org.mockito.kotlin.whenever(context.getSharedPreferences("companion_options", android.content.Context.MODE_PRIVATE))
            .thenReturn(org.mockito.kotlin.mock())
        val room = ReadingRoom(context, "a")
        val saved = room.saveSource(null, "人机恋相处模式", "topic", "人机恋 相处模式", 48, true, "chat:2026-10-14")
        assertEquals("chat:2026-10-14", saved.getString("parent"))
        assertThrows(IllegalArgumentException::class.java) {
            room.saveSource(null, "她喜欢的", "topic", "园园 喜欢", 48, true, "chat:2026-10-14")
        }
        assertThrows(IllegalArgumentException::class.java) {
            room.saveSource(null, "坏日期", "topic", "人机恋", 48, true, "chat:昨天")
        }
        folder.deleteRecursively()
    }
}