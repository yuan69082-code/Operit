package com.ai.assistance.operit.core.companion

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AssistantDiaryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context {
        val context = mock<Context>(); val prefs = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(prefs)
        return context
    }
    @Test fun diaryOffByDefaultAndPrivateAcrossAssistants() = runBlocking {
        val context = context(); val a = AssistantRoom(context, "a"); val b = AssistantRoom(context, "b")
        assertThrows(IllegalStateException::class.java) { runBlocking { a.saveDiary(null, "2026-10-14", "今天", "一个想法", "a-chat", true, null) } }
        CompanionStore(context).update("room:a") { it.put("ai_diary", true) }
        val saved = a.saveDiary(null, "2026-10-14", "今天", "读了摘要之后的想法", "a-chat", true, null)
        assertEquals("a", saved.getString("owner")); assertEquals("a-chat", saved.getString("source_chat"))
        assertTrue(b.diaries().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { b.diary(saved.getString("id")) }
    }
    @Test fun userEditsPreserveOriginalAuthorSourceAndRevision() = runBlocking {
        val context = context(); val a = AssistantRoom(context, "a")
        CompanionStore(context).update("room:a") { it.put("ai_diary", true) }
        val first = a.saveDiary(null, "2026-10-14", "散步的念头", "听园园说起散步", "source-chat", true, null)
        val id = first.getString("id")
        val edited = a.saveDiary(id, "2026-10-14", "散步的念头", "补充：这是转述", "other-chat", false, 1)
        assertEquals("a", edited.getString("author")); assertEquals("user", edited.getString("edited_by"))
        assertEquals("source-chat", edited.getString("source_chat"))
        assertEquals("听园园说起散步", CompanionStore(context).read("diary:a:$id:v1").getString("text"))
        assertThrows(IllegalStateException::class.java) { runBlocking { a.saveDiary(id, "2026-10-14", "旧稿覆盖", "不应保存", "source-chat", false, 1) } }
        assertEquals(2L, a.diary(id).getLong("version"))
    }
    @Test fun revokingDiaryGrantPreventsFurtherAssistantWrites() = runBlocking {
        val context = context(); val a = AssistantRoom(context, "a"); val store = CompanionStore(context)
        store.update("room:a") { it.put("ai_diary", true) }
        a.saveDiary(null, "2026-10-14", "念头", "以后继续看看", "a-chat", true, null)
        store.update("room:a") { it.put("ai_diary", false) }
        assertThrows(IllegalStateException::class.java) { runBlocking { a.saveDiary(null, "2026-10-15", "以后", "未授权", "a-chat", true, null) } }
        assertEquals(1, a.diaries().size)
    }
}
