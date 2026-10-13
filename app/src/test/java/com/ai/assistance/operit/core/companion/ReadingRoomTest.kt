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
}
