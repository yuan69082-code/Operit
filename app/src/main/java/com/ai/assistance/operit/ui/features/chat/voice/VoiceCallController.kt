package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.speech.SpeechService
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.ai.assistance.operit.api.voice.VoiceService
import com.ai.assistance.operit.api.voice.QueuedVoiceService
import com.ai.assistance.operit.api.voice.VoiceServiceFactory
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.TtsCleaner
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.WaifuMessageProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Service-owned, same-conversation call. Recording and playback never overlap. */
class VoiceCallController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: ChatViewModel,
    val chatId: String,
    val nativeAudio: Boolean,
    val audioAnalysis: Boolean = false,
    private val callerRoleCardId: String? = null,
    private val incoming: Boolean = false,
    private val onStopped: () -> Unit,
) {
    enum class Phase { CONNECTING, LISTENING, RECOGNIZING, THINKING, SPEAKING, MUTED, ERROR, ENDED }

    var phase by mutableStateOf(Phase.CONNECTING)
        private set
    var transcript by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf("")
        private set
    var isRunning by mutableStateOf(false)
        private set
    private val muted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = muted.asStateFlow()
    private var sessionJob: Job? = null
    private var turnJob: Job? = null
    private var speech: SpeechService? = null
    private var voice: VoiceService? = null
    val startedAt = android.os.SystemClock.elapsedRealtime()
    private var roleCardId: String? = callerRoleCardId

    fun start() {
        if (sessionJob?.isActive == true) return
        errorMessage = ""
        phase = Phase.CONNECTING
        isRunning = true
        viewModel.setVoiceCallActive(true)
        sessionJob = scope.launch {
            try {
                check(!viewModel.activeStreamingChatIds.value.contains(chatId)) {
                    context.getString(R.string.voice_call_busy)
                }
                if (roleCardId == null) {
                    roleCardId = (com.ai.assistance.operit.data.preferences.ActivePromptManager
                        .getInstance(context).getActivePrompt() as? com.ai.assistance.operit.data.model.ActivePrompt.CharacterCard)?.id
                }
                val analyzer = VoiceCallAudioAnalysis(context)
                if (audioAnalysis) analyzer.requireConfigured()
                if (nativeAudio && !audioAnalysis) {
                    check(viewModel.activeChatModelConfig.value?.enableDirectAudioProcessing == true) {
                        context.getString(R.string.voice_call_audio_required)
                    }
                }
                AIForegroundService.setWakeListeningSuspendedForVoiceCall(context, true)
                delay(180)
                val recognizer = if (nativeAudio || audioAnalysis) null else SpeechServiceFactory.createSpeechService(context).also {
                    speech = it
                    check(it.initialize()) { context.getString(R.string.voice_call_stt_failed) }
                }
                val recorder = VoiceCallAudioRecorder(context)
                val speaker = VoiceServiceFactory.createVoiceService(context).also { voice = it }
                check(speaker.initialize()) { context.getString(R.string.voice_call_tts_failed) }
                val profiles = SpeechServiceProfilesPreferences(context)
                val cleanerRegexs = profiles.getCurrentTtsProfile().cleanerRegexs
                var greetIncoming = incoming
                while (isActive && phase != Phase.ERROR) {
                    if (muted.value) {
                        phase = Phase.MUTED
                        muted.first { !it }
                    }
                    coroutineScope {
                        turnJob = launch {
                            var audioFile: java.io.File? = null
                            try {
                                phase = Phase.LISTENING
                                transcript = ""
                                val text = if (greetIncoming) {
                                    greetIncoming = false
                                    "[通话事件] 用户已接听你主动发起的电话，现在已经接通。请先对用户开口，不复述事件说明。"
                                } else if (audioAnalysis) {
                                    val savedAudio = recorder.recordTurn()
                                    audioFile = savedAudio
                                    phase = Phase.RECOGNIZING
                                    try {
                                        "[语音通话转写] " + analyzer.analyze(savedAudio) + "\n【语音文件】${savedAudio.absolutePath}"
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        currentCoroutineContext().ensureActive()
                                        // User explicitly requested a path-only message on analysis failure.
                                        AppLogger.w("VoiceCall", "Audio analysis failed: ${error.javaClass.simpleName}")
                                        "[语音通话音频] 转写失败，音频已保存\n【语音文件】${savedAudio.absolutePath}"
                                    }
                                } else if (nativeAudio) {
                                    audioFile = recorder.recordTurn()
                                    context.getString(R.string.voice_call_audio_sent)
                                } else recognizeTurn(checkNotNull(recognizer))
                                if (text.isBlank()) return@launch
                                // Sound observations belong to chat context, not the spoken-word caption.
                                transcript = if (text.startsWith("[通话事件]")) "" else if (audioAnalysis && text.contains("【原话】")) {
                                    text.substringAfter("【原话】").substringBefore("【疑似听词】")
                                        .substringBefore("【声音】").substringBefore("【语音文件】").trim()
                                } else text
                                reply = ""
                                phase = Phase.THINKING
                                coroutineScope {
                                    val sentences = Channel<Pair<String, Deferred<Boolean>?>>(2)
                                    val generation = async {
                                        val publicText = VoiceCallPublicText.StreamCleaner()
                                        try {
                                            viewModel.sendVoiceCallTurn(text, chatId, if (audioAnalysis) null else audioFile?.absolutePath, roleCardId, audioAnalysis) { sentence ->
                                                // Display and speech share the same public text; protocol
                                                // metadata and thinking never enter the call captions.
                                                val cleaned = WaifuMessageProcessor.cleanContentForWaifu(
                                                    TtsCleaner.clean(
                                                        publicText.clean(ChatUtils.stripOpenAiResponsesProtocolMarkup(sentence)),
                                                        cleanerRegexs,
                                                    )
                                                )
                                                if (cleaned.isNotBlank()) {
                                                    val queued = (speaker as? QueuedVoiceService)?.enqueueSpeech(cleaned) {
                                                        withContext(Dispatchers.Main) {
                                                            if (phase != Phase.ENDED && phase != Phase.ERROR) {
                                                                phase = Phase.SPEAKING
                                                                reply = cleaned
                                                            }
                                                        }
                                                    }
                                                    sentences.send(cleaned to queued)
                                                }
                                            }
                                        } finally {
                                            sentences.close()
                                        }
                                    }
                                    for ((sentence, queued) in sentences) {
                                        val played = if (queued != null) queued.await() else {
                                            phase = Phase.SPEAKING
                                            reply = sentence
                                            speaker.speak(sentence, interrupt = false)
                                        }
                                        check(played) { context.getString(R.string.voice_call_tts_failed) }
                                        if (queued == null) speaker.speakingStateFlow.first { !it }
                                    }
                                    val completed = ChatUtils.stripOpenAiResponsesProtocolMarkup(
                                        ChatUtils.removeThinkingContent(generation.await())
                                    ).trim()
                                    if (completed.endsWith("<voice_call_end/>")) finish()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("VoiceCall", "Call turn failed", e)
                                viewModel.cancelMessage(chatId)
                                errorMessage = e.message.orEmpty()
                                phase = Phase.ERROR
                            } finally {
                                // Cancellation may arrive during synthesis or an STT network request.
                                // Join cleanup before allowing the next turn to acquire the microphone.
                                withContext(NonCancellable) {
                                    recognizer?.cancelRecognition()
                                    speaker.stop()
                                    // Keep analysis recordings: their paths are part of the persisted turn.
                                    if (!audioAnalysis) audioFile?.delete()
                                }
                            }
                        }
                        turnJob?.join()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("VoiceCall", "Call initialization failed", e)
                errorMessage = e.message.orEmpty()
                phase = Phase.ERROR
            } finally {
                withContext(NonCancellable) {
                    try {
                        speech?.cancelRecognition()
                        voice?.stop()
                    } finally {
                        speech?.shutdown()
                        voice?.shutdown()
                        speech = null
                        voice = null
                        viewModel.setVoiceCallActive(false)
                        try {
                            AIForegroundService.setWakeListeningSuspendedForVoiceCall(context, false)
                        } finally {
                            isRunning = false
                            onStopped()
                        }
                    }
                }
            }
        }
    }

    private suspend fun recognizeTurn(recognizer: SpeechService): String = coroutineScope {
        val result = CompletableDeferred<String>()
        // Ignore the replayed result from the previous turn; subscribe before starting recording.
        val results = launch(start = CoroutineStart.UNDISPATCHED) {
            recognizer.recognitionResultFlow.drop(1).collect {
                if (it.text.isNotBlank()) transcript = it.text
                if (it.isFinal) result.complete(it.text.trim())
            }
        }
        val errors = launch(start = CoroutineStart.UNDISPATCHED) {
            recognizer.recognitionErrorFlow.drop(1).collect {
                if (it.code != 0) result.completeExceptionally(IllegalStateException(it.message))
            }
        }
        val states = launch {
            recognizer.recognitionStateFlow.collect {
                if (it == SpeechService.RecognitionState.PROCESSING) phase = Phase.RECOGNIZING
            }
        }
        try {
            transcript = ""
            check(recognizer.startRecognition(
                continuousMode = false,
                partialResults = true,
                silenceDurationMs = 1200,
            )) { context.getString(R.string.voice_call_stt_failed) }
            result.await()
        } finally {
            results.cancel()
            errors.cancel()
            states.cancel()
        }
    }

    fun toggleMute() {
        muted.value = !muted.value
        if (muted.value && (phase == Phase.LISTENING || phase == Phase.RECOGNIZING)) {
            turnJob?.cancel()
        }
    }

    fun interrupt() {
        if (phase != Phase.THINKING && phase != Phase.SPEAKING) return
        muted.value = false
        viewModel.cancelMessage(chatId)
        turnJob?.cancel()
    }

    fun finish() {
        if (viewModel.activeStreamingChatIds.value.contains(chatId)) viewModel.cancelMessage(chatId)
        phase = Phase.ENDED
        sessionJob?.cancel()
    }
}
