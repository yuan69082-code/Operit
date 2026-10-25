package com.ai.assistance.operit.api.voice

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class QueuedTtsPlaybackTest {
    @Test fun emptyAudioMustNotCountAsSpoken() = runBlocking {
        val file = File.createTempFile("empty-tts", ".mp3")
        val queue = QueuedTtsPlayback("test") { file }
        try {
            withTimeout(5_000) {
                try {
                    queue.enqueue("你好").await()
                    fail("Empty audio must fail the call sentence")
                } catch (error: IllegalStateException) {
                    assertEquals("语音服务返回的音频为空或已失效", error.message)
                }
            }
        } finally {
            queue.shutdown()
            file.delete()
        }
    }

    @Test fun deletedAudioMustNotCountAsSpoken() = runBlocking {
        val file = File.createTempFile("deleted-tts", ".mp3")
        file.delete()
        val queue = QueuedTtsPlayback("test") { file }
        try {
            withTimeout(5_000) {
                try {
                    queue.enqueue("你好").await()
                    fail("Deleted audio must fail the call sentence")
                } catch (error: IllegalStateException) {
                    assertEquals("语音服务返回的音频为空或已失效", error.message)
                }
            }
        } finally {
            queue.shutdown()
        }
    }
}
