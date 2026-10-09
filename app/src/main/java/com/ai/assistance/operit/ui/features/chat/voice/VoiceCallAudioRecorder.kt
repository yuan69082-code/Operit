package com.ai.assistance.operit.ui.features.chat.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import com.ai.assistance.operit.api.speech.OnnxSileroVad
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
                    // A natural thinking pause must not terminate the user's sentence.
                    if (quietFrames >= 100 || pcm.size() >= rate * 2 * 60) break
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

    data class Clip(
        val file: File,
        val quietIntervals: List<Pair<Long, Long>>,
        val beginMillis: Long,
        val endMillis: Long,
        val windowEnded: Boolean,
        val playbackText: String,
        val inputEpoch: Int,
        val speechTurn: Boolean,
    )

    /** One AudioRecord session stays alive while remote speech plays and analysis runs. */
    @SuppressLint("MissingPermission")
    suspend fun captureContinuous(
        isMuted: () -> Boolean,
        inputEpoch: () -> Int,
        playbackText: () -> String,
        shouldSubmit: () -> Boolean,
        onEcho: suspend (Boolean) -> Unit,
        onProgress: suspend (Float, Long, Boolean) -> Unit,
        onSpeechState: suspend (Boolean) -> Unit,
        onClip: suspend (Clip) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val rate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Microphone does not support 16 kHz PCM" }
        val manager = context.getSystemService(AudioManager::class.java)
        val previousMode = manager.mode
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate * 2))
        var echo: AcousticEchoCanceler? = null
        var vad: OnnxSileroVad? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(recorder.audioSessionId)
                echo?.setEnabled(true)
            }
            onEcho(echo?.enabled == true)
            val speechDetector = OnnxSileroVad(context, speechDurationMs = 64, silenceDurationMs = 0)
            vad = speechDetector
            val frame = ShortArray(512) // Silero requires 512 samples at 16 kHz.
            val vadFrame = ShortArray(512)
            var vadSamples = 0
            var speechFrame = false
            var speechTurnOpen = false
            var nonSpeechSamples = 0L
            val pcm = ByteArrayOutputStream()
            val preRoll = ArrayDeque<ByteArray>()
            val quietIntervals = mutableListOf<Pair<Long, Long>>()
            var quietStart: Long? = null
            var totalSamples = 0L
            var clipStartSamples = 0L
            var framesRead = 0
            var remoteText = ""
            var epoch = inputEpoch()
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(frame, 0, frame.size)
                check(count > 0) { "Microphone read failed: $count" }
                totalSamples += count
                framesRead++
                val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                var energy = 0.0
                for (i in 0 until count) {
                    bytes.putShort(frame[i])
                    val sample = frame[i] / 32768.0
                    energy += sample * sample
                }
                val currentEpoch = inputEpoch()
                val changedEpoch = currentEpoch != epoch
                epoch = currentEpoch
                val muted = isMuted()
                val rms = if (muted) 0f else sqrt(energy / count).toFloat()
                // A sound gate, not a speech gate: non-word sounds can open a window too.
                val hasSound = rms >= .004f
                if (framesRead % 5 == 0) onProgress(rms, totalSamples * 1000 / rate, hasSound)
                if (muted || changedEpoch) {
                    pcm.reset()
                    preRoll.clear()
                    quietIntervals.clear()
                    quietStart = null
                    remoteText = ""
                    speechDetector.reset()
                    vadSamples = 0
                    speechFrame = false
                    nonSpeechSamples = 0L
                    if (speechTurnOpen) onSpeechState(false)
                    speechTurnOpen = false
                    continue
                }
                // Accumulate exact VAD windows even if AudioRecord returns a short read.
                for (i in 0 until count) {
                    vadFrame[vadSamples++] = frame[i]
                    if (vadSamples == vadFrame.size) {
                        speechFrame = speechDetector.isSpeech(vadFrame)
                        vadSamples = 0
                    }
                }
                if (speechFrame) {
                    if (!speechTurnOpen) onSpeechState(true)
                    speechTurnOpen = true
                    nonSpeechSamples = 0L
                } else if (speechTurnOpen) nonSpeechSamples += count
                val manualSubmit = shouldSubmit()
                if (pcm.size() == 0) {
                    preRoll.addLast(bytes.array())
                    if (preRoll.size > 10) preRoll.removeFirst()
                    if (!hasSound && !speechTurnOpen && !manualSubmit) continue
                    clipStartSamples = totalSamples - preRoll.sumOf { it.size / 2 }
                    preRoll.forEach { pcm.write(it) }
                    preRoll.clear()
                } else pcm.write(bytes.array())
                val playing = playbackText()
                if (playing.isNotBlank()) remoteText = playing
                val elapsed = pcm.size().toLong() * 1000 / (rate * 2)
                if (!hasSound && quietStart == null) quietStart = elapsed - count * 1000 / rate
                if (hasSound && quietStart != null) {
                    val start = checkNotNull(quietStart)
                    if (elapsed - start >= 120) quietIntervals.add(start to elapsed)
                    quietStart = null
                }
                // Speech and non-word sounds have separate clocks. Breathing cannot reset
                // the speech-pause timer, and a 3 s sound window cannot cut a sentence.
                val speechFinished = speechTurnOpen && nonSpeechSamples >= rate * 2L
                val soundWindowFinished = !speechTurnOpen && pcm.size() >= rate * 2 * 3
                // Bound memory on exceptionally long speech; this chunk is not an end-of-turn.
                val windowEnded = speechTurnOpen && pcm.size() >= rate * 2 * 30 &&
                    !speechFinished && !manualSubmit
                if (windowEnded || speechFinished || soundWindowFinished || manualSubmit) {
                    quietStart?.let { if (elapsed - it >= 120) quietIntervals.add(it to elapsed) }
                    val data = pcm.toByteArray()
                    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                        .put("RIFF".toByteArray()).putInt(data.size + 36).put("WAVEfmt ".toByteArray())
                        .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                        .putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size).array()
                    val dir = File(checkNotNull(context.getExternalFilesDir(null)), "voice_recordings").apply { mkdirs() }
                    val file = File.createTempFile("live-call-", ".wav", dir)
                    try {
                        file.outputStream().use { it.write(header); it.write(data) }
                        onClip(Clip(file, quietIntervals.toList(), clipStartSamples * 1000 / rate,
                            totalSamples * 1000 / rate, windowEnded, remoteText, epoch, speechTurnOpen))
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                    pcm.reset()
                    quietIntervals.clear()
                    quietStart = null
                    remoteText = ""
                    if (!windowEnded) {
                        speechTurnOpen = false
                        nonSpeechSamples = 0L
                        speechFrame = false
                        vadSamples = 0
                        speechDetector.reset()
                        onSpeechState(false)
                    }
                }
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                try { vad?.close() } finally {
                    try { echo?.release() } finally {
                        try { recorder.release() } finally {
                            manager.mode = previousMode
                            onSpeechState(false)
                        }
                    }
                }
            }
        }
    }
}
