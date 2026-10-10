package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Capture never waits on HTTP; four pending windows bound latency and disk usage. */
class VoiceCallContinuousAudio(
    private val context: Context,
    private val analyzer: VoiceCallAudioAnalysis,
    private val isMuted: () -> Boolean,
    private val inputEpoch: () -> Int,
    private val playbackText: () -> String,
    private val referenceSnapshot: suspend () -> File?,
    private val shouldSubmit: () -> Boolean,
    private val manualMode: () -> Boolean = { false },
    private val captureAllowed: () -> Boolean = { true },
    private val onManualSubmitted: suspend () -> Unit = {},
    private val onEcho: suspend (Boolean) -> Unit,
    private val onProgress: suspend (Float, Long, Boolean) -> Unit,
    private val onSpeechState: suspend (Boolean) -> Unit,
    private val onSkipped: suspend (Int) -> Unit,
    private val onInvalidAnalysis: suspend () -> Unit,
    private val onAnalysis: suspend (VoiceCallAnalysisResult, VoiceCallAudioRecorder.Clip) -> Unit,
) {
    suspend fun run(): Unit = coroutineScope {
        val skipped = AtomicInteger()
        val capturingSpeech = AtomicBoolean()
        val speechPending = AtomicInteger()
        suspend fun publishSpeechState() {
            // Include in-flight speech analysis: old sound observations cannot reply first.
            onSpeechState(capturingSpeech.get() || speechPending.get() > 0)
        }
        val clips = Channel<VoiceCallAudioRecorder.Clip>(4, BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = {
                it.file.delete()
                if (it.speechTurn) speechPending.decrementAndGet()
                skipped.incrementAndGet()
            })
        val capture = launch {
            try {
                VoiceCallAudioRecorder(context).captureContinuous(
                    isMuted, inputEpoch, playbackText, shouldSubmit, onEcho, onProgress,
                    onSpeechState = { active -> capturingSpeech.set(active); publishSpeechState() },
                    manualMode = manualMode, captureAllowed = captureAllowed, onManualSubmitted = onManualSubmitted,
                ) { clip ->
                    if (clip.speechTurn) speechPending.incrementAndGet()
                    if (clips.trySend(clip).isFailure) {
                        if (clip.speechTurn) speechPending.decrementAndGet()
                        clip.file.delete()
                    }
                    publishSpeechState()
                }
            } finally { clips.close() }
        }
        try {
            for (clip in clips) {
                var reference: File? = null
                try {
                    reference = referenceSnapshot()
                    if (isMuted() || clip.inputEpoch != inputEpoch()) continue
                    val count = skipped.getAndSet(0)
                    if (count > 0) onSkipped(count)
                    val analysis = try {
                        analyzer.analyze(clip.file, reference, clip.quietIntervals,
                            continuous = true, playbackText = clip.playbackText)
                    } catch (error: VoiceCallAudioAnalysis.InvalidAnalysisResult) {
                        currentCoroutineContext().ensureActive()
                        AppLogger.e("VoiceCall", "Rejected incomplete audio analysis window", error)
                        // Reject only this result. Letting it escape cancels live capture and playback.
                        // Never invent missing speaker/sound fields or deliver partial text to the model.
                        if (!isMuted() && clip.inputEpoch == inputEpoch()) onInvalidAnalysis()
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isMuted() && clip.inputEpoch == inputEpoch()) onAnalysis(analysis, clip)
                } finally {
                    reference?.delete()
                    // The controller copies only recordings that enter persisted speech turns.
                    clip.file.delete()
                    if (clip.speechTurn) speechPending.decrementAndGet()
                    publishSpeechState()
                }
            }
        } finally {
            capture.cancelAndJoin()
            clips.cancel()
            onSpeechState(false)
        }
    }
}
