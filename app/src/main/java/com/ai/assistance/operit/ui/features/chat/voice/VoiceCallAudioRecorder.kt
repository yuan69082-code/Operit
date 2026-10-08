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
    var lastQuietIntervals: List<Pair<Long, Long>> = emptyList()
        private set
    @SuppressLint("MissingPermission") // The call can only be started after the microphone grant.
    suspend fun recordTurn(
        onProgress: suspend (rms: Float, elapsedMillis: Long, soundDetected: Boolean) -> Unit = { _, _, _ -> },
        shouldSubmit: () -> Boolean = { false },
    ): File = withContext(Dispatchers.IO) {
        lastQuietIntervals = emptyList()
        val rate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Microphone does not support 16 kHz PCM" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate * 2))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            val frame = ShortArray(320) // 20 ms frames; cancellation never waits for a long recording.
            val preRoll = ArrayDeque<ByteArray>()
            // Keep a bounded manual buffer so a quiet sentence can be submitted explicitly,
            // even when it never crosses the automatic sound threshold.
            val manualFrames = ArrayDeque<ByteArray>()
            val pcm = ByteArrayOutputStream()
            var recordingSound = false
            var quietFrames = 0
            var sampleCount = 0L
            var framesRead = 0
            val quietIntervals = mutableListOf<Pair<Long, Long>>()
            var quietStartMillis: Long? = null
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
                val rms = sqrt(energy / count).toFloat()
                val hasSound = rms >= 0.012f
                sampleCount += count
                framesRead++
                manualFrames.addLast(bytes.array())
                if (manualFrames.size > 600) manualFrames.removeFirst()
                if (framesRead % 5 == 0 || (hasSound && !recordingSound)) onProgress(rms, sampleCount * 1000 / rate, recordingSound || hasSound)
                if (shouldSubmit()) {
                    if (recordingSound) pcm.write(bytes.array())
                    else manualFrames.forEach { pcm.write(it) }
                    break
                }
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
                    val elapsedPcmMillis = pcm.size().toLong() * 1000 / (rate * 2)
                    if (!hasSound && quietStartMillis == null) quietStartMillis = elapsedPcmMillis - count * 1000 / rate
                    if (hasSound && quietStartMillis != null) {
                        val start = checkNotNull(quietStartMillis)
                        if (elapsedPcmMillis - start >= 120) quietIntervals.add(start to elapsedPcmMillis)
                        quietStartMillis = null
                    }
                    quietFrames = if (hasSound) 0 else quietFrames + 1
                    // 700 ms end-of-phrase silence; avoid an extra 1.2 s before analysis.
                    if (quietFrames >= 35 || pcm.size() >= rate * 2 * 12) break
                }
            }
            val data = pcm.toByteArray()
            quietStartMillis?.let { start ->
                val end = data.size.toLong() * 1000 / (rate * 2)
                if (end - start >= 120) quietIntervals.add(start to end)
            }
            lastQuietIntervals = quietIntervals.toList()
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
