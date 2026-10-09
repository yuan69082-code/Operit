package com.ai.assistance.operit.ui.features.chat.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCallSilencePolicyTest {
    @Test fun emptyRecognitionAndQuietObservationDoNotRestartSilence() {
        listOf(VoiceCallController.Phase.LISTENING, VoiceCallController.Phase.RECOGNIZING,
            VoiceCallController.Phase.THINKING).forEach { phase ->
            assertFalse(VoiceCallSilencePolicy.resetsClock(phase, true, false, false))
        }
    }

    @Test fun speechAndSuspendedCallStatesAlwaysResetTheClock() {
        assertTrue(VoiceCallSilencePolicy.resetsClock(VoiceCallController.Phase.LISTENING, true, false, true))
        assertTrue(VoiceCallSilencePolicy.resetsClock(VoiceCallController.Phase.LISTENING, false, false, false))
        assertTrue(VoiceCallSilencePolicy.resetsClock(VoiceCallController.Phase.LISTENING, true, true, false))
        listOf(VoiceCallController.Phase.SPEAKING, VoiceCallController.Phase.MUTED,
            VoiceCallController.Phase.ENDED, VoiceCallController.Phase.ERROR).forEach { phase ->
            assertTrue(VoiceCallSilencePolicy.resetsClock(phase, true, false, false))
        }
    }
}
