package com.ai.assistance.operit.ui.features.chat.voice

/** Manual clips ignore VAD and noise windows; only explicit send ends them. */
internal object VoiceCallSegmentPolicy {
    enum class Boundary { CONTINUE, CHUNK, COMPLETE }

    fun boundary(manual: Boolean, submit: Boolean, speech: Boolean, quietMillis: Long, clipMillis: Long): Boundary = when {
        submit -> Boundary.COMPLETE
        manual -> Boundary.CONTINUE
        speech && quietMillis >= 2_000 -> Boundary.COMPLETE
        !speech && clipMillis >= 3_000 -> Boundary.COMPLETE
        speech && clipMillis >= 30_000 -> Boundary.CHUNK
        else -> Boundary.CONTINUE
    }
}
