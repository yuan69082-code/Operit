package com.ai.assistance.operit.core.companion

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ExplorationStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyEnabledLearningDoesNotEnableAnyAssistant() {
        val context = context()
        val preferences = context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)
        whenever(preferences.getBoolean("proactive_enabled", false)).thenReturn(true)
        whenever(preferences.getStringSet("learning_choices", emptySet())).thenReturn(setOf("web", "mcp"))
        val exploration = ExplorationStore(context)
        assertFalse(exploration.enabled("a"))
        assertFalse(exploration.enabled("b"))
        assertFalse(exploration.anyEnabled())
    }

    @Test fun persistedExplorationAndLibrariesKeepAssistantOwnership() {
        val context = context()
        val storage = CompanionStore(context)
        storage.update("exploration:a") { ExplorationPolicy.configure(it, true, setOf("reading"), 4, 0) }
        storage.update("exploration:b") { ExplorationPolicy.configure(it, true, setOf("web"), 2, 0) }
        storage.saveEntry("role:a", null, "我的随记", "随记", "knowledge", "读过的那一页", null)
        storage.update("exploration:a") { ExplorationPolicy.configure(it, false, setOf("reading"), 4, 1) }
        val reopened = ExplorationStore(context)
        assertFalse(reopened.enabled("a"))
        assertTrue(reopened.enabled("b"))
        assertFalse(reopened.read("b").has("chat"))
        assertEquals(setOf("web"), ExplorationPolicy.choices(reopened.read("b")))
        assertEquals("读过的那一页", CompanionStore(context).entries("role:a").single().getString("content"))
        assertTrue(CompanionStore(context).entries("role:b").isEmpty())
    }

    private fun context(): Context {
        val context = mock<Context>()
        val preferences = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(temporary.newFolder())
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(preferences)
        return context
    }
}
