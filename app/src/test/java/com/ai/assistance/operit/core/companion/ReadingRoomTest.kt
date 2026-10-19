package com.ai.assistance.operit.core.companion

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ReadingRoomTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context {
        val context = mock<Context>(); val prefs = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context); whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(prefs)
        return context
    }
    @Test fun userAnnotationsDoNotChangeAssistantNoteTimeOrSourceBlocks() {
        val context = context(); val room = ReadingRoom(context, "a")
        val id = room.add("旧记录", "https://example.com/old", "原摘要").getString("id")
        room.open(id, "summary", true)
        room.note(id, "实际随记", true)
        CompanionStore(context).update("reading-entry:a:$id") { it.put("ai_note_at", 1234L)
            .put("page_blocks", ReadingPage.extract("<article><p>原文</p><img src='/a.jpg'></article>", "https://example.com")) }
        room.note(id, "我的批注", false)
        val entry = room.entry(id)
        assertEquals(1234L, entry.getLong("ai_note_at"))
        assertTrue(entry.has("user_note_at"))
        assertEquals("实际随记", entry.getString("note"))
        assertEquals("原摘要", entry.getString("summary"))
        assertEquals(2, entry.getJSONArray("page_blocks").length())
        assertFalse(room.entries(true).single().has("page_blocks"))
    }
    @Test fun viewingByUserDoesNotClaimAssistantReadIt() {
        val context = context(); val room = ReadingRoom(context, "a")
        val id = room.add("星空", "https://example.com/sky", "来源摘要").getString("id")
        room.open(id, "summary", false)
        assertFalse(room.entry(id).has("delivered_at")); assertEquals(1, room.entries().size)
        assertThrows(IllegalStateException::class.java) { room.note(id, "没读就写", true) }
    }
    @Test fun readingSummaryCannotTurnIntoFullTextOrChangeSource() {
        val room = ReadingRoom(context(), "a")
        val id = room.add("星空", "https://example.com/sky", "来源摘要").getString("id")
        assertEquals("summary", room.open(id, "summary", true).getString("level"))
        room.note(id, "读了摘要后的想法", true)
        assertEquals("来源摘要", room.entry(id).getString("summary"))
        assertEquals("读了摘要后的想法", room.entry(id).getString("note"))
        assertThrows(IllegalStateException::class.java) { room.open(id, "body", true) }
        assertEquals(1, room.entries(true).size)
    }
    @Test fun duplicateFeedEntriesPreserveReadStateAndNotes() {
        val room = ReadingRoom(context(), "a")
        val first = room.add("星空", "https://example.com/sky#start", "来源摘要")
        room.open(first.getString("id"), "summary", true); room.note(first.getString("id"), "记住的东西", true)
        val repeated = room.add("新标题", "https://example.com/sky", "新摘要")
        assertEquals(first.getString("id"), repeated.getString("id")); assertEquals("记住的东西", repeated.getString("note"))
        assertTrue(room.entries().isEmpty()); assertEquals(1, room.entries(true).size)
    }
    @Test fun sameSourceDoesNotShareReadingBetweenAssistants() {
        val context = context(); val a = ReadingRoom(context, "a"); val b = ReadingRoom(context, "b")
        val id = a.add("星空", "https://example.com/sky", "摘要").getString("id")
        assertThrows(IllegalStateException::class.java) { b.entry(id) }
        b.add("星空", "https://example.com/sky", "摘要"); a.open(id, "summary", true)
        assertFalse(b.entry(id).has("delivered_at")); assertEquals(1, b.entries().size)
    }
    @Test fun expiredQueueDoesNotEraseReadHistory() {
        val context = context(); val room = ReadingRoom(context, "a")
        val id = room.add("星空", "https://example.com/sky", "摘要").getString("id")
        room.open(id, "summary", true)
        CompanionStore(context).update("reading:a") { it.getJSONArray("entries").getJSONObject(0).put("expires", 1L) }
        assertEquals(1, room.entries(true).size)
        assertThrows(IllegalStateException::class.java) { room.skip(id) }
    }
    @Test fun rereadingSummaryPreservesPreviouslyReadBodyAndBothAnnotations() {
        val context = context(); val room = ReadingRoom(context, "a")
        val id = room.add("星空", "https://example.com/sky", "摘要").getString("id")
        CompanionStore(context).update("reading-entry:a:$id") { it.put("body", "取得的部分正文").put("material", "partial") }
        room.open(id, "body", true)
        room.note(id, "助手的随记", true); room.note(id, "用户的批注", false)
        assertEquals("summary", room.open(id, "summary", true).getString("level"))
        val saved = room.entry(id)
        assertEquals("partial", saved.getString("delivered")); assertEquals("saved", saved.getString("state"))
        assertEquals("助手的随记", saved.getString("note")); assertEquals("用户的批注", saved.getString("user_note"))
        assertEquals("partial", room.entries(true).single().getString("delivered"))
    }
    @Test fun forumPostsExpireInTwoDaysOthersInSeven() {
        val room = ReadingRoom(context(), "a")
        val normal = room.add("普通", "https://example.com/a", "摘要")
        val forum = room.add("帖子", "https://example.com/b", "摘要", ReadingRoom.FORUM)
        val custom = room.add("自定", "https://example.com/c", "摘要", days = 1)
        fun span(o: org.json.JSONObject) = (o.getLong("expires") - o.getLong("added")) / 86_400_000L
        assertEquals(7, span(normal)); assertEquals(2, span(forum)); assertEquals(1, span(custom))
        assertThrows(IllegalArgumentException::class.java) { room.add("太久", "https://example.com/d", "摘要", days = 31) }
        assertEquals(47L, ReadingRoom.hoursLeft(forum, forum.getLong("added") + 3_600_000L))
        assertEquals(0L, ReadingRoom.hoursLeft(forum, forum.getLong("added") + 3 * 86_400_000L))
        assertNull(ReadingRoom.hoursLeft(org.json.JSONObject().put("state", "read")))
    }
    @Test fun hyThreadIdCountsAsForumWithoutUrl() {
        val room = ReadingRoom(context(), "a")
        val post = room.add("不想修掉的缺点", "hy:thread:12380", "岚的帖子")
        assertEquals("hy:thread:12380", post.getString("url")); assertEquals(ReadingRoom.FORUM, post.getString("source"))
        assertEquals(2, (post.getLong("expires") - post.getLong("added")) / 86_400_000L)
        assertThrows(IllegalArgumentException::class.java) { room.add("坏", "hy:thread:abc", "摘要") }
    }
}
