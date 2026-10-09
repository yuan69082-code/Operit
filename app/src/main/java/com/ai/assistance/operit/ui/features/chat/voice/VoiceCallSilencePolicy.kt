package com.ai.assistance.operit.ui.features.chat.voice

/** Background sound analysis and empty STT results do not count as a person breaking silence. */
internal object VoiceCallSilencePolicy {
    fun resetsClock(phase: VoiceCallController.Phase, enabled: Boolean, muted: Boolean, userInputPending: Boolean): Boolean =
        !enabled || muted || userInputPending || phase in setOf(
            VoiceCallController.Phase.CONNECTING, VoiceCallController.Phase.RINGING,
            VoiceCallController.Phase.SPEAKING, VoiceCallController.Phase.MUTED,
            VoiceCallController.Phase.ERROR, VoiceCallController.Phase.ENDED
        )
}
