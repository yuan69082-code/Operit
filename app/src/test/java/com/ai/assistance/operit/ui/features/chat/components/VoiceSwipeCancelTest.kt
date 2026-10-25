package com.ai.assistance.operit.ui.features.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSwipeCancelTest {
    @Test fun sidewaysAndDownwardDragsNeverCancel() {
        assertFalse(isVoiceSwipeCancel(0f, 80f))
        assertFalse(isVoiceSwipeCancel(5f, 80f))
        assertFalse(isVoiceSwipeCancel(300f, 80f))
        assertFalse(isVoiceSwipeCancel(-79.9f, 80f))
    }

    @Test fun onlyUpwardBeyondThresholdCancels() {
        assertTrue(isVoiceSwipeCancel(-80f, 80f))
        assertTrue(isVoiceSwipeCancel(-105f, 80f))
        // A higher density changes the pixel threshold, not its 80dp meaning.
        assertFalse(isVoiceSwipeCancel(-120f, 160f))
        assertTrue(isVoiceSwipeCancel(-160f, 160f))
    }
}
