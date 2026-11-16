package com.ai.assistance.operit.ui.features.chat.voice

/** Run every release even when one device/network-backed resource fails. The caller must
 * surface returned failures; this does not convert a failed call into successful playback. */
internal object VoiceCallCleanup {
    suspend fun release(vararg steps: suspend () -> Unit): List<Exception> = buildList {
        for (step in steps) try { step() } catch (failure: Exception) { add(failure) }
    }
}
