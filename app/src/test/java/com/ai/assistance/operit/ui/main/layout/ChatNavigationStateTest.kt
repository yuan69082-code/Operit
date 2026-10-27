package com.ai.assistance.operit.ui.main.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigationStateTest {
    @Test fun directoryHidesConversationSurfaceOnPhone() {
        assertFalse(conversationSurfaceVisible(directoryVisible = true, floatingMode = false))
    }

    @Test fun openedConversationAndFloatingModeKeepConversationSurface() {
        assertTrue(conversationSurfaceVisible(directoryVisible = false, floatingMode = false))
        assertTrue(conversationSurfaceVisible(directoryVisible = true, floatingMode = true))
    }
}
