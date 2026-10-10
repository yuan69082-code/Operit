package com.ai.assistance.operit.data.model

data class ChatTurnOptions(
    val persistTurn: Boolean = true,
    val notifyReply: Boolean? = null,
    val hideUserMessage: Boolean = false,
    val disableWarning: Boolean = false,
    val voiceCall: Boolean = false,
    val voiceCallEnded: Boolean = false,
    val voiceCallAudioPath: String? = null,
    val voiceCallVisualPath: String? = null,
    val voiceCallVisualIsVideo: Boolean = false,
    val voiceCallVisualOnly: Boolean = false,
    val onVoiceCallVisualStored: ((String) -> Unit)? = null,
    val voiceCallAudioAnalyzed: Boolean = false,
    val voiceCallTyped: Boolean = false,
    val voiceCallContinuous: Boolean = false,
    val voiceCallObservation: Boolean = false,
    val voiceCallSilence: Boolean = false,
    val voiceCallEvent: Boolean = false,
    val voiceCallDecision: Boolean = false,
    // Transient delivery callback; never persisted as conversation data.
    val onVoiceCallText: (suspend (String) -> Unit)? = null,
    val onVoiceCallComplete: ((String) -> Unit)? = null,
    val proactiveWake: Boolean = false,
    val onCompanionComplete: ((String) -> Unit)? = null
)
