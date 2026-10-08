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
import kotlinx.coroutines.selects.select
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
    enum class Phase { CONNECTING, SPEAKER_SETUP, LISTENING, RECOGNIZING, THINKING, SPEAKING, MUTED, ERROR, ENDED }

    var phase by mutableStateOf(Phase.CONNECTING)
        private set
    var transcript by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var participantName by mutableStateOf("Operit")
        private set
    var participantAvatarUri by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf("")
        private set
    var analysisWarning by mutableStateOf("")
        private set
    var microphoneLevel by mutableStateOf(0f)
        private set
    var recordingMillis by mutableStateOf(0L)
        private set
    var soundDetected by mutableStateOf(false)
        private set
    private val submitRecording = java.util.concurrent.atomic.AtomicBoolean(false)
    var isConfirmingVoice by mutableStateOf(false)
        private set
    var recordingNotice by mutableStateOf("")
        private set
    private var voiceReference: java.io.File? = null
    private var referenceRequested: CompletableDeferred<Unit>? = null
    private var recentBackgroundContext = ""
    var cameraEnabled by mutableStateOf(false)
        private set
    var cameraPreview by mutableStateOf<ByteArray?>(null)
        private set
    var cameraWarning by mutableStateOf("")
        private set
    var supportsCameraImages by mutableStateOf(false)
        private set
    var supportsCameraVideo by mutableStateOf(false)
        private set
    var cameraIntervalSeconds by mutableStateOf(10)
        private set
    var cameraVideoMode by mutableStateOf(false)
        private set
    private var camera: VoiceCallCamera? = null
    private var cameraGeneration = 0
    private data class Visual(val file: java.io.File, val video: Boolean)
    private var pendingVisual: Visual? = null
    private val visualReady = Channel<Unit>(Channel.CONFLATED)
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
        analysisWarning = ""
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
                roleCardId?.let { id ->
                    participantName = com.ai.assistance.operit.data.preferences.CharacterCardManager
                        .getInstance(context).getCharacterCard(id).name
                    participantAvatarUri = com.ai.assistance.operit.data.preferences.UserPreferencesManager
                        .getInstance(context).getAiAvatarForCharacterCardFlow(id).first()
                }
                val analyzer = VoiceCallAudioAnalysis(context)
                supportsCameraImages = viewModel.activeChatModelConfig.value?.enableDirectImageProcessing == true
                supportsCameraVideo = viewModel.activeChatModelConfig.value?.enableDirectVideoProcessing == true
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
                        coroutineScope {
                            val unmuted = async { muted.first { !it } }
                            try { select<Unit> {
                                unmuted.onAwait { }
                                visualReady.onReceive { }
                            } } finally { unmuted.cancel() }
                        }
                        if (muted.value && pendingVisual == null) continue
                    }
                    coroutineScope {
                        turnJob = launch {
                            var audioFile: java.io.File? = null
                            var visual: Visual? = null
                            var visualOnly = false
                            try {
                                if (audioAnalysis && voiceReference == null && !muted.value) confirmVoiceReference(recorder)
                                phase = if (muted.value) Phase.MUTED else Phase.LISTENING
                                transcript = ""
                                microphoneLevel = 0f
                                recordingMillis = 0
                                soundDetected = false
                                submitRecording.set(false)
                                val input = if (greetIncoming) null else if (muted.value) CallInput(visualOnly = true) else awaitCallInput(recorder, recognizer)
                                visualOnly = input?.visualOnly == true
                                val text = if (greetIncoming) {
                                    greetIncoming = false
                                    "[通话事件] 用户已接听你主动发起的电话，现在已经接通。请先对用户开口，不复述事件说明。"
                                } else if (visualOnly) {
                                    "[通话画面更新] 摄像头提供了新的画面；用户并没有开口。"
                                } else if (audioAnalysis) {
                                    val savedAudio = checkNotNull(input?.audio)
                                    audioFile = savedAudio
                                    phase = Phase.RECOGNIZING
                                    analysisWarning = ""
                                    try {
                                        val analysis = analyzer.analyze(savedAudio, checkNotNull(voiceReference), recorder.lastQuietIntervals)
                                        if (analysis.userText.isBlank()) {
                                            recordingNotice = if (analysis.speakerMatch == "uncertain")
                                                "无法确定是谁说的，没有记成你的发言。你可以重新确认声音。"
                                            else "这段没有检测到你的发言，其他声音作为背景保留。"
                                            recentBackgroundContext = "\n【上一片段背景观察】声源判断：${analysis.speakerMatch}；${analysis.environment}。不属于已确认的用户发言。"
                                            return@launch
                                        }
                                        recordingNotice = ""
                                        val contextText = analysis.toContext() + recentBackgroundContext
                                        recentBackgroundContext = ""
                                        contextText + "\n【语音文件】${savedAudio.absolutePath}"
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        currentCoroutineContext().ensureActive()
                                        // User explicitly requested a path-only message on analysis failure.
                                        AppLogger.w("VoiceCall", "Audio analysis failed: ${error.javaClass.simpleName}")
                                        analysisWarning = if (error is VoiceCallAudioAnalysis.AnalysisFailure) error.message.orEmpty()
                                            else "音频分析未完成，录音已保存。请检查分析配置；当前模型没有收到转写内容。"
                                        "[语音通话音频] 转写失败，音频已保存\n【语音文件】${savedAudio.absolutePath}"
                                    }
                                } else if (nativeAudio) {
                                    audioFile = checkNotNull(input?.audio)
                                    context.getString(R.string.voice_call_audio_sent)
                                } else input?.text.orEmpty()
                                if (text.isBlank()) return@launch
                                visual = pendingVisual
                                pendingVisual = null
                                if (visualOnly && visual == null) return@launch
                                // Sound observations belong to chat context, not the spoken-word caption.
                                transcript = if (visualOnly || text.startsWith("[通话事件]")) "" else
                                    com.ai.assistance.operit.util.VoiceCallMessageText.forDisplay(text).removePrefix("[语音通话]").trim()
                                reply = ""
                                phase = Phase.THINKING
                                coroutineScope {
                                    val sentences = Channel<Pair<String, Deferred<Boolean>?>>(2)
                                    val generation = async {
                                        val publicText = VoiceCallPublicText.StreamCleaner()
                                        try {
                                            viewModel.sendVoiceCallTurn(text, chatId, if (audioAnalysis) null else audioFile?.absolutePath, roleCardId, audioAnalysis,
                                                visual?.file?.absolutePath, visual?.video == true, visualOnly) { sentence ->
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
                                    visual?.file?.delete()
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
                        disableCamera()
                        voiceReference?.delete()
                        voiceReference = null
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

    private suspend fun confirmVoiceReference(recorder: VoiceCallAudioRecorder) {
        isConfirmingVoice = true
        val requested = CompletableDeferred<Unit>()
        referenceRequested = requested
        phase = Phase.SPEAKER_SETUP
        try {
            requested.await()
            phase = Phase.LISTENING
            voiceReference = recordAudioTurn(recorder)
            recordingNotice = "声音参考已记录；不确定的说话者不会记成你的发言。"
        } finally {
            referenceRequested = null
            isConfirmingVoice = false
        }
    }

    private data class CallInput(val audio: java.io.File? = null, val text: String = "", val visualOnly: Boolean = false)

    private suspend fun awaitCallInput(recorder: VoiceCallAudioRecorder, recognizer: SpeechService?): CallInput = coroutineScope {
        val recorded = async {
            if (audioAnalysis || nativeAudio) CallInput(audio = recordAudioTurn(recorder))
            else CallInput(text = recognizeTurn(checkNotNull(recognizer)))
        }
        select {
            recorded.onAwait { it }
            visualReady.onReceive {
                // Never discard a phrase already being spoken just to send a scheduled frame.
                if (soundDetected || pendingVisual == null || !cameraEnabled) recorded.await()
                else {
                    recorded.cancelAndJoin()
                    CallInput(visualOnly = true)
                }
            }
        }
    }

    fun enableCamera(video: Boolean, intervalSeconds: Int) {
        if (!isRunning) return
        check(if (video) supportsCameraVideo else supportsCameraImages) { "当前聊天模型没有启用对应的视觉能力" }
        check(intervalSeconds == 10 || intervalSeconds == 30) { "请选择10秒或30秒" }
        disableCamera()
        cameraEnabled = true
        cameraWarning = ""
        cameraVideoMode = video
        cameraIntervalSeconds = intervalSeconds
        val generation = cameraGeneration
        val capture = VoiceCallCamera(context, scope, video, intervalSeconds,
            onPreview = { if (cameraEnabled && generation == cameraGeneration) cameraPreview = it },
            onMedia = { file, isVideo ->
                if (!cameraEnabled || generation != cameraGeneration) file.delete() else {
                    pendingVisual?.file?.delete()
                    pendingVisual = Visual(file, isVideo)
                    // Do not queue an immediate visual reply behind TTS: leave time to speak.
                    if ((phase == Phase.LISTENING && !isConfirmingVoice) || phase == Phase.MUTED) visualReady.trySend(Unit)
                }
            },
            onError = { if (generation == cameraGeneration) { cameraWarning = it; disableCamera() } })
        camera = capture
        capture.start()
    }

    fun disableCamera() {
        cameraGeneration++
        cameraEnabled = false
        camera?.stop()
        camera = null
        cameraPreview = null
        pendingVisual?.file?.delete()
        pendingVisual = null
        while (visualReady.tryReceive().isSuccess) { /* Drop old camera wake-ups. */ }
    }

    fun reportCameraError(message: String) { cameraWarning = message; disableCamera() }

    fun recordMyVoice() {
        if (phase == Phase.SPEAKER_SETUP) referenceRequested?.complete(Unit)
    }

    fun reconfirmVoice() {
        if (!audioAnalysis || isConfirmingVoice || (phase != Phase.LISTENING && phase != Phase.MUTED)) return
        turnJob?.cancel()
        voiceReference?.delete()
        voiceReference = null
        recentBackgroundContext = ""
        recordingNotice = ""
    }

    private suspend fun recordAudioTurn(recorder: VoiceCallAudioRecorder): java.io.File {
        microphoneLevel = 0f
        recordingMillis = 0L
        soundDetected = false
        submitRecording.set(false)
        return recorder.recordTurn(
        onProgress = { rms, elapsedMillis, detected ->
            withContext(Dispatchers.Main) {
                // A waveform meter distinguishes capture from recognition, which starts later.
                microphoneLevel = (rms * 20f).coerceIn(0f, 1f)
                recordingMillis = elapsedMillis
                soundDetected = detected
            }
        },
        shouldSubmit = { submitRecording.get() },
        )
    }

    fun sendRecordingNow() {
        if ((nativeAudio || audioAnalysis) && phase == Phase.LISTENING) submitRecording.set(true)
    }

    private suspend fun recognizeTurn(recognizer: SpeechService): String = coroutineScope {
        val result = CompletableDeferred<String>()
        // Reuse the STT microphone meter; no second recorder is needed for animation.
        val levels = launch {
            recognizer.volumeLevelFlow.collect {
                microphoneLevel = it.coerceIn(0f, 1f)
                if (it > .12f) soundDetected = true
            }
        }
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
            withContext(NonCancellable) { recognizer.cancelRecognition() }
            levels.cancel()
            microphoneLevel = 0f
            results.cancel()
            errors.cancel()
            states.cancel()
        }
    }

    fun toggleMute() {
        muted.value = !muted.value
        if (muted.value && (phase == Phase.SPEAKER_SETUP || phase == Phase.LISTENING || phase == Phase.RECOGNIZING)) {
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
