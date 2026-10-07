package com.ai.assistance.operit.ui.features.chat.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Records the waveform, including non-speech sounds. No STT or noise filtering is applied here. */
class VoiceCallAudioRecorder(private val context: Context) {
    @SuppressLint("MissingPermission") // The call can only be started after the microphone grant.
    suspend fun recordTurn(): File = withContext(Dispatchers.IO) {
        val rate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Microphone does not support 16 kHz PCM" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate * 2))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            val frame = ShortArray(320) // 20 ms frames; cancellation never waits for a long recording.
            val preRoll = ArrayDeque<ByteArray>()
            val pcm = ByteArrayOutputStream()
            var recordingSound = false
            var quietFrames = 0
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(frame, 0, frame.size)
                check(count > 0) { "Microphone read failed: $count" }
                val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                var energy = 0.0
                for (i in 0 until count) {
                    bytes.putShort(frame[i])
                    val normalized = frame[i] / 32768.0
                    energy += normalized * normalized
                }
                val hasSound = sqrt(energy / count) >= 0.012
                if (!recordingSound) {
                    preRoll.addLast(bytes.array())
                    if (preRoll.size > 40) preRoll.removeFirst() // Keep 800 ms before the sound.
                    if (hasSound) {
                        recordingSound = true
                        preRoll.forEach { pcm.write(it) }
                        preRoll.clear()
                    }
                } else {
                    pcm.write(bytes.array())
                    quietFrames = if (hasSound) 0 else quietFrames + 1
                    if (quietFrames >= 60 || pcm.size() >= rate * 2 * 12) break
                }
            }
            val data = pcm.toByteArray()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(data.size + 36).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size).array()
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "voice_recordings").apply { mkdirs() }
            File.createTempFile("call-", ".wav", directory).also { file ->
                file.outputStream().use { it.write(header); it.write(data) }
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
    }
}
