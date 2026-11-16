package com.ai.assistance.operit.ui.features.chat.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceCallCleanupTest {
    @Test fun failedRecorderAndPlayerStillReleaseFollowingResourcesAndReportFailures() = runBlocking {
        val steps = mutableListOf<String>()
        val errors = VoiceCallCleanup.release(
            { steps += "recorder"; error("recorder disconnected") },
            { steps += "player"; error("player unavailable") },
            { steps += "camera" },
            { steps += "idle" })
        assertEquals(listOf("recorder", "player", "camera", "idle"), steps)
        assertEquals(2, errors.size)
        assertEquals("recorder disconnected", errors[0].message)
        assertEquals(0, VoiceCallCleanup.release({ steps += "next call" }).size)
    }
}
