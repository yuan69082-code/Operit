package com.ai.assistance.operit.core.companion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class StudyStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store(): StudyStore {
        val context = mock<Context>(); val prefs = mock<SharedPreferences>(); val editor = mock<SharedPreferences.Editor>()
        whenever(context.applicationContext).thenReturn(context); whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(context.getSharedPreferences("study_runtime", Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(prefs.all).thenReturn(emptyMap<String, Any>()); whenever(prefs.edit()).thenReturn(editor); whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        return StudyStore(context, "assistant")
    }
    @Test fun editsKeepOldTextAndItsAnchoredNotesAndRejectConcurrentOverwrite() {
        val store = store(); val id = store.create("故事", "writing", "daily", "user").getString("id")
        val first = store.write(id, null, null, "第一章", "原文", JSONArray(), "user", false).getJSONArray("chapters").getJSONObject(0)
        store.annotate(id, first.getString("version"), 0, 2, "原文", "有趣", "hand", "#123456", JSONArray(), JSONArray(), "user", "note1")
        store.write(id, first.getString("id"), first.getString("version"), "第一章", "改稿", JSONArray(), "user", true)
        assertEquals("原文", store.chapter(id, first.getString("version")).getString("text"))
        assertEquals(first.getString("version"), store.book(id).getJSONArray("notes").getJSONObject(0).getString("version"))
        assertThrows(IllegalStateException::class.java) {
            store.write(id, first.getString("id"), first.getString("version"), "覆盖", "迟到的改稿", JSONArray(), "user", true)
        }
    }
    @Test fun duplicateAnnotationsDoNotDuplicateWakeAndNewEventsSurviveOldAcknowledgement() {
        val store = store(); val id = store.create("故事", "writing", "sex", "user").getString("id")
        repeat(2) { store.annotate(id, null, 0, 0, "", "读后感", "serif", "#123456", JSONArray(), JSONArray(), "user", "same") }
        assertEquals(1, store.book(id).getJSONArray("notes").length())
        val events = store.book(id).getJSONArray("events")
        assertEquals(1, events.length())
        store.userEvent(id, "继续读")
        store.finish(id, setOf(events.getJSONObject(0).getString("id")), "handled")
        val after = store.book(id).getJSONArray("events")
        assertEquals("handled", after.getJSONObject(0).getString("state"))
        assertEquals("pending", after.getJSONObject(1).getString("state"))
    }
    @Test fun draftsDoNotWakeAndReadingReturnsOnlyRequestedPage() {
        val store = store(); val id = store.create("长篇", "writing", "daily", "user").getString("id")
        val chapter = store.write(id, null, null, "第一章", "文".repeat(14000), JSONArray(), "user", false).getJSONArray("chapters").getJSONObject(0)
        assertEquals(0, store.book(id).getJSONArray("events").length())
        val read = store.readForAI(id, chapter.getString("version"), 6000)
        assertEquals(6000, read.getString("text").length)
        assertEquals(12000, read.getInt("next_offset"))
        assertEquals(14000, read.getInt("total_chars"))
        assertThrows(IllegalStateException::class.java) { store.chapter(id, "foreign-version") }
    }
}
