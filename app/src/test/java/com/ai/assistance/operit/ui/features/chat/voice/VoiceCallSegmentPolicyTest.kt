package com.ai.assistance.operit.ui.features.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallSegmentPolicy.Boundary.*

class VoiceCallSegmentPolicyTest {
    @Test fun manualModeDoesNotSubmitDuringThinkingPausesOrBackgroundNoise() {
        assertEquals(CONTINUE, VoiceCallSegmentPolicy.boundary(true, false, true, 5000, 12000))
        assertEquals(CONTINUE, VoiceCallSegmentPolicy.boundary(true, false, false, 0, 4000))
        assertEquals(CONTINUE, VoiceCallSegmentPolicy.boundary(true, false, true, 0, 35000))
    }
    @Test fun explicitSendWorksWithoutVadAndManualDurationIsBounded() {
        assertEquals(COMPLETE, VoiceCallSegmentPolicy.boundary(true, true, false, 0, 100))
        assertEquals(COMPLETE, VoiceCallSegmentPolicy.boundary(true, false, true, 0, 60000))
        assertEquals(CONTINUE, VoiceCallSegmentPolicy.boundary(true, false, true, 0, 59999))
    }
    @Test fun realtimeKeepsSpeechPauseAndNonSpeechWindowSeparate() {
        assertEquals(CONTINUE, VoiceCallSegmentPolicy.boundary(false, false, true, 1900, 6000))
        assertEquals(COMPLETE, VoiceCallSegmentPolicy.boundary(false, false, true, 2000, 6000))
        assertEquals(COMPLETE, VoiceCallSegmentPolicy.boundary(false, false, false, 0, 3000))
        assertEquals(CHUNK, VoiceCallSegmentPolicy.boundary(false, false, true, 0, 30000))
    }
}
