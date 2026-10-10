package com.ai.assistance.operit.ui.features.chat.voice

/** Manual clips ignore VAD and noise windows; only explicit send or the duration cap ends them. */
internal object VoiceCallSegmentPolicy {
    enum class Boundary { CONTINUE, CHUNK, COMPLETE }

    fun boundary(manual: Boolean, submit: Boolean, speech: Boolean, quietMillis: Long, clipMillis: Long): Boundary = when {
        submit -> Boundary.COMPLETE
        manual -> if (clipMillis >= 60_000) Boundary.COMPLETE else Boundary.CONTINUE
        speech && quietMillis >= 2_000 -> Boundary.COMPLETE
        !speech && clipMillis >= 3_000 -> Boundary.COMPLETE
        speech && clipMillis >= 30_000 -> Boundary.CHUNK
        else -> Boundary.CONTINUE
    }
}
