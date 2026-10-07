package com.ai.assistance.operit.data.model

data class ChatTurnOptions(
    val persistTurn: Boolean = true,
    val notifyReply: Boolean? = null,
    val hideUserMessage: Boolean = false,
    val disableWarning: Boolean = false,
    val voiceCall: Boolean = false,
    val voiceCallAudioPath: String? = null,
    // Transient delivery callback; never persisted as conversation data.
    val onVoiceCallText: (suspend (String) -> Unit)? = null
)
