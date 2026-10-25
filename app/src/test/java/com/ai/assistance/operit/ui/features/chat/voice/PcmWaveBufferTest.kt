package com.ai.assistance.operit.ui.features.chat.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PcmWaveBufferTest {
    @Test fun longRecordingIsFinalizedWithoutSixtySecondCutoff() {
        val directory = Files.createTempDirectory("voice-test").toFile()
        try {
            val pcm = PcmWaveBuffer(directory)
            repeat(75) { pcm.write(ByteArray(32000) { 42 }) }
            assertEquals(75L * 32000, pcm.size())
            val wav = pcm.finish()
            pcm.reset() // Taking ownership must prevent reset from deleting the completed clip.
            assertTrue(wav.exists())
            assertEquals(44 + 75L * 32000, wav.length())
            val data = wav.readBytes()
            val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(75 * 32000, header.getInt(40))
            assertEquals(75 * 32000 + 36, header.getInt(4))
            assertEquals(42.toByte(), data.last())
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancelDiscardsOnlyUnsubmittedCapture() {
        val directory = Files.createTempDirectory("voice-cancel").toFile()
        try {
            val pcm = PcmWaveBuffer(directory)
            pcm.write(ByteArray(32000))
            pcm.reset()
            assertEquals(0L, pcm.size())
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
}
