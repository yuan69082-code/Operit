package com.ai.assistance.operit.core.companion

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.util.UserInnerThought
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class HumanThoughtAccessTest {
    private val preferences = mock<SharedPreferences>()
    private val editor = mock<SharedPreferences.Editor>()
    private fun access(): HumanThoughtAccess {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences("human_thought_access", Context.MODE_PRIVATE)).thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putBoolean(any(), any())).thenReturn(editor)
        whenever(editor.remove(any())).thenReturn(editor)
        return HumanThoughtAccess(context)
    }
    @Test fun assistantCannotEnableWithoutHumanAuthorization() {
        val access = access()
        assertThrows(IllegalStateException::class.java) { access.choose("a", true) }
        verify(preferences, never()).edit()
    }
    @Test fun revocationClearsEveryAssistantChoice() {
        val access = access()
        whenever(preferences.all).thenReturn(mapOf("enabled:a" to true, "enabled:b" to true, "name" to "园园"))
        access.authorize(false)
        verify(editor).putBoolean("allowed", false)
        verify(editor).remove("enabled:a")
        verify(editor).remove("enabled:b")
        verify(editor, never()).remove("name")
        verify(editor).apply()
    }
    @Test fun actorsDoNotShareEnabledStateAndHiddenThoughtsAreNotProjected() {
        val access = access()
        whenever(preferences.getBoolean("allowed", false)).thenReturn(true)
        whenever(preferences.getBoolean("enabled:a", false)).thenReturn(true)
        val text = UserInnerThought.encode("心里话", "正文")
        assertEquals(text, access.project(text, "a"))
        assertEquals("正文", access.project(text, "b"))
        assertEquals("正文", access.project(text, null))
    }
    @Test fun spokenTextRequiredButThoughtIsOptional() {
        val access = access()
        assertThrows(IllegalArgumentException::class.java) { access.submit(UserInnerThought.encode("想法", ""), true) }
        assertTrue(access.submit("正文", true).contains(UserInnerThought.EMPTY))
        assertEquals("正文", access.submit(UserInnerThought.encode("想法", "正文"), false))
    }
}
