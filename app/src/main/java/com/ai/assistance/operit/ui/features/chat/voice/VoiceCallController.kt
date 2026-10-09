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

/** Service-owned call. Audio analysis has independent continuous capture and playback. */
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
    enum class Phase { CONNECTING, RINGING, LISTENING, RECOGNIZING, THINKING, SPEAKING, MUTED, ERROR, ENDED }
    enum class EndBy { USER, AI, SYSTEM }

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
    private val microphoneEpoch = java.util.concurrent.atomic.AtomicInteger()
    val isConfirmingVoice: Boolean get() = audioAnalysis && voiceReference == null
    var recordingNotice by mutableStateOf("")
        private set
    private var voiceReference by mutableStateOf<java.io.File?>(null)
    var cameraEnabled by mutableStateOf(false)
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
    var camera by mutableStateOf<VoiceCallCamera?>(null)
        private set
    var cameraDelivery by mutableStateOf("")
        private set
    private var cameraGeneration = 0
    private data class Visual(val file: java.io.File, val video: Boolean)
    private var pendingVisual: Visual? = null
    // Standalone camera turns are transient; subsequent speech still needs the latest frame.
    private var latestVisual: Visual? = null
    private var activeVisual: Visual? = null
    private val visualReady = Channel<Unit>(Channel.CONFLATED)
    private val typedReady = Channel<Unit>(Channel.CONFLATED)
    private val silenceReady = Channel<Unit>(Channel.CONFLATED)
    private val companionOptions = com.ai.assistance.operit.core.companion.CompanionStore(context)
    private var lastSoundAt = android.os.SystemClock.elapsedRealtime()
    private var silenceJob: Job? = null
    private var silenceTurnActive = false
    private val pendingTyped = ArrayDeque<String>()
    var typedDraft by mutableStateOf("")
        private set
    var pendingTypedCount by mutableStateOf(0)
        private set

    fun updateTypedDraft(text: String) { typedDraft = text }

    fun sendTypedDraft(): Boolean {
        val text = typedDraft.trim()
        if (!isConnected || !isRunning || phase == Phase.ERROR || phase == Phase.ENDED || text.isEmpty()) return false
        if (pendingTyped.size >= 16) {
            viewModel.showToast("待发送文字较多，请等当前回复结束")
            return false
        }
        pendingTyped.addLast(text)
        pendingTypedCount = pendingTyped.size
        typedDraft = ""
        typedReady.trySend(Unit)
        return true
    }

    private fun takeTypedInput(): CallInput? {
        if (pendingTyped.isEmpty()) return null
        val text = pendingTyped.removeFirst()
        pendingTypedCount = pendingTyped.size
        if (pendingTyped.isEmpty()) typedReady.tryReceive()
        return CallInput(text = text, typed = true)
    }
    var isRunning by mutableStateOf(false)
        private set
    private val muted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = muted.asStateFlow()
    var continuousWarning by mutableStateOf("")
        private set
    val continuousListening: Boolean get() = audioAnalysis && isConnected && isRunning && !muted.value &&
        phase != Phase.ERROR && phase != Phase.ENDED
    private var continuousJob: Job? = null
    private val heardReady = Channel<Unit>(Channel.CONFLATED)
    private val pendingHeard = ArrayDeque<CallInput>()
    private var heardTurnReady = false
    private val speechTurnActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private var sessionJob: Job? = null
    private var turnJob: Job? = null
    private var speech: SpeechService? = null
    private var voice: VoiceService? = null
    var startedAt by mutableStateOf(0L)
        private set
    var isConnected by mutableStateOf(false)
        private set
    private var accepted = incoming
    private var dialRecorded = false
    private var endRecorded = false
    private var endedBy: EndBy? = null
    private var openingDelivered = false
    private var roleCardId: String? = callerRoleCardId

    fun start() {
        if (phase == Phase.ENDED) return
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
                if (!accepted) {
                    if (!dialRecorded) {
                        dialRecorded = true
                        VoiceCallEvents.record(context, chatId, "user", context.getString(R.string.message_role_user), "拨打电话，等待接听").join()
                    }
                    val callerName = com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
                        .getInstance(context).globalUserName.first().orEmpty().ifBlank { context.getString(R.string.message_role_user) }
                    while (!accepted) {
                        phase = Phase.RINGING
                        val raw = viewModel.sendVoiceCallTurn(
                            "$callerName 给你打电话了，是否选择接听？电话仍在等待，用户可以取消。",
                            chatId, null, roleCardId, decision = true,
                        ) { /* Do not speak or display model control data before acceptance. */ }
                        currentCoroutineContext().ensureActive()
                        val decision = VoiceCallDecision.parse(raw)
                        when (decision.action) {
                            VoiceCallDecision.Action.ACCEPT -> {
                                accepted = true
                                VoiceCallEvents.record(context, chatId, "ai", participantName, "已接听").join()
                            }
                            VoiceCallDecision.Action.REJECT -> {
                                VoiceCallEvents.record(context, chatId, "ai", participantName,
                                    "拒接" + if (decision.reason.isBlank()) "" else "\n${decision.reason}").join()
                                reply = decision.reason
                                viewModel.showToast("对方已拒接")
                                phase = Phase.ENDED
                                return@launch
                            }
                            VoiceCallDecision.Action.WAIT -> delay(decision.waitMillis)
                        }
                    }
                }
                if (!isConnected) {
                    isConnected = true
                    startedAt = android.os.SystemClock.elapsedRealtime()
                }
                phase = Phase.CONNECTING
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
                silenceJob = launch {
                    while (isActive) {
                        delay(1000)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!companionOptions.silenceEnabled || muted.value || phase != Phase.LISTENING ||
                            speechTurnActive.get() || pendingHeard.isNotEmpty() || pendingTyped.isNotEmpty()) {
                            lastSoundAt = now
                            silenceReady.tryReceive()
                        } else if (now - lastSoundAt >= companionOptions.silenceSeconds * 1000L) {
                            silenceReady.trySend(Unit)
                        }
                    }
                }
                if (audioAnalysis) {
                    continuousJob = launch {
                        try {
                        VoiceCallContinuousAudio(
                            context, analyzer,
                            isMuted = { muted.value },
                            inputEpoch = { microphoneEpoch.get() },
                            playbackText = { if (speaker.isSpeaking) reply else "" },
                            referenceSnapshot = {
                                // Snapshot on the owner thread so a voice reset cannot delete it mid-copy.
                                withContext(Dispatchers.Main) {
                                    voiceReference?.let { source ->
                                        java.io.File.createTempFile("live-reference-", ".wav", context.cacheDir)
                                            .also { source.copyTo(it, overwrite = true) }
                                    }
                                }
                            },
                            shouldSubmit = { submitRecording.getAndSet(false) },
                            onEcho = { enabled -> withContext(Dispatchers.Main) {
                                continuousWarning = if (enabled) "" else "设备未提供可控回声消除，扬声器声音可能被再次录入；建议使用耳机。"
                            } },
                            onProgress = { rms, elapsed, sound -> withContext(Dispatchers.Main) {
                                if (rms >= .004f) lastSoundAt = android.os.SystemClock.elapsedRealtime()
                                microphoneLevel = (rms * 30f).coerceIn(0f, 1f)
                                recordingMillis = elapsed
                                soundDetected = sound
                            } },
                            onSkipped = { count -> withContext(Dispatchers.Main) {
                                continuousWarning = "音频分析跟不上，已跳过 $count 个较旧片段，继续处理最近声音。"
                            } },
                            onInvalidAnalysis = { withContext(Dispatchers.Main) {
                                continuousWarning = "有一段声音分析结果不完整，未发送给AI；通话继续收音。"
                            } },
                            onSpeechState = { active -> withContext(Dispatchers.Main) {
                                speechTurnActive.set(active)
                                // A real utterance supersedes an in-flight silence response.
                                if (active && silenceTurnActive && phase == Phase.THINKING) {
                                    viewModel.cancelMessage(chatId)
                                    turnJob?.cancel()
                                }
                                // A queued observation must wait while a new sentence is being spoken.
                                if (!active) heardReady.trySend(Unit)
                            } },
                            onAnalysis = { analysis, clip -> withContext(Dispatchers.Main) {
                                if (analysis.userText.isNotBlank() || analysis.soundActivity) {
                                    if (voiceReference == null && analysis.userText.isNotBlank() &&
                                        analysis.speakerMatch == "matched" && analysis.utterances.all { it.speaker == "caller" } &&
                                        clip.playbackText.isBlank()) saveVoiceReference(clip.file)
                                    val path = if (analysis.userText.isBlank()) "" else withContext(Dispatchers.IO) {
                                        java.io.File.createTempFile("retained-live-", ".wav", clip.file.parentFile)
                                            .also { clip.file.copyTo(it, overwrite = true) }.absolutePath
                                    }
                                    if (muted.value || phase == Phase.ENDED || clip.inputEpoch != microphoneEpoch.get()) {
                                        if (path.isNotBlank()) java.io.File(path).delete()
                                        return@withContext
                                    }
                                    val observed = analysis.toContext() +
                                        "\n【采集片段】通话第 ${clip.beginMillis} 至 ${clip.endMillis} 毫秒；" +
                                        (if (clip.windowEnded) "长发言的中间片段，尚未轮到回复。" else "检测到约2秒无语音，或仅声音观察到期、用户手动提交；这是候选轮次边界，不保证语义说完。")
                                    if (pendingHeard.size >= 8) {
                                        pendingHeard.removeFirst().recordings.forEach { it.delete() }
                                        continuousWarning = "回复处理跟不上，已略过较旧的声音观察，保留最近片段。"
                                    }
                                    pendingHeard.addLast(CallInput(
                                        text = observed + (if (path.isBlank()) "" else "\n【语音文件】$path"),
                                        heard = true, observation = analysis.userText.isBlank(), caption = analysis.userText,
                                        recordings = if (path.isBlank()) emptyList() else listOf(java.io.File(path)),
                                    ))
                                    // Intermediate long-speech chunks accumulate without waking the main model.
                                    heardTurnReady = !clip.windowEnded
                                }
                                // The closing window can be all silence; it still releases earlier speech.
                                if (!clip.windowEnded && pendingHeard.isNotEmpty()) {
                                    heardTurnReady = true
                                    heardReady.trySend(Unit)
                                }
                            } },
                        ).run()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            AppLogger.e("VoiceCall", "Continuous capture or analysis failed", error)
                            errorMessage = "持续收音或音频分析失败：" + error.message.orEmpty()
                            phase = Phase.ERROR
                            viewModel.cancelMessage(chatId)
                            turnJob?.cancel()
                            // Wake a session waiting on mute/input so its finally releases the microphone.
                            sessionJob?.cancel()
                        }
                    }
                }
                var greetIncoming = !openingDelivered
                while (isActive && phase != Phase.ERROR) {
                    if (muted.value && pendingTyped.isEmpty()) {
                        phase = Phase.MUTED
                        coroutineScope {
                            val unmuted = async { muted.first { !it } }
                            try { select<Unit> {
                                unmuted.onAwait { }
                                visualReady.onReceive { }
                                typedReady.onReceive { }
                            } } finally { unmuted.cancel() }
                        }
                        if (muted.value && pendingVisual == null && pendingTyped.isEmpty()) continue
                    }
                    coroutineScope {
                        turnJob = launch {
                            var audioFile: java.io.File? = null
                            var visual: Visual? = null
                            var visualOnly = false
                            var sentTurn = false
                            try {
                                phase = if (muted.value) Phase.MUTED else Phase.LISTENING
                                transcript = ""
                                microphoneLevel = 0f
                                recordingMillis = 0
                                soundDetected = false
                                submitRecording.set(false)
                                val input = if (greetIncoming) null else {
                                    takeTypedInput() ?: takeHeardInput() ?: if (muted.value) CallInput(visualOnly = true) else awaitCallInput(recorder, recognizer)
                                }
                                visualOnly = input?.visualOnly == true
                                silenceTurnActive = input?.silence == true
                                val text = if (greetIncoming) {
                                    greetIncoming = false
                                    if (incoming) "[通话事件] 用户已接听你主动发起的电话，现在已经接通。请先对用户开口，不复述事件说明。"
                                    else "[通话事件] 你已选择接听用户打来的电话，现在已经接通。请先对用户开口，不必等用户说第一句话，不复述事件说明。"
                                } else if (visualOnly) {
                                    "[通话画面更新] 摄像头提供了新的画面；用户并没有开口。"
                                } else if (input?.typed == true) {
                                    // Typed words have no audio observations and never establish a voice reference.
                                    "[语音通话打字]\n${input.text}"
                                } else if (input?.silence == true) {
                                    input.text
                                } else if (input?.heard == true) {
                                    input.text
                                } else if (nativeAudio) {
                                    audioFile = checkNotNull(input?.audio)
                                    context.getString(R.string.voice_call_audio_sent)
                                } else input?.text.orEmpty()
                                if (text.isBlank()) return@launch
                                sentTurn = true
                                visual = pendingVisual ?: if (!visualOnly) latestVisual else null
                                pendingVisual = null
                                if (visualOnly && visual == null) return@launch
                                activeVisual = visual
                                if (visual != null) cameraDelivery = "正在向模型发送画面"
                                // Sound observations belong to chat context, not the spoken-word caption.
                                transcript = if (input?.heard == true) input.caption else if (input?.silence == true || visualOnly || text.startsWith("[通话事件]")) "" else
                                    com.ai.assistance.operit.util.VoiceCallMessageText.forDisplay(text).removePrefix("[语音通话]").trim()
                                reply = ""
                                phase = Phase.THINKING
                                coroutineScope {
                                    val sentences = Channel<Pair<String, Deferred<Boolean>?>>(2)
                                    val generation = async {
                                        val publicText = VoiceCallPublicText.StreamCleaner()
                                        try {
                                            viewModel.sendVoiceCallTurn(text, chatId, if (audioAnalysis) null else audioFile?.absolutePath, roleCardId, audioAnalysis && input?.typed != true,
                                                visual?.file?.absolutePath, visual?.video == true, visualOnly, typed = input?.typed == true,
                                                continuous = audioAnalysis, observation = input?.observation == true, silence = input?.silence == true) { sentence ->
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
                                    if (visual != null) cameraDelivery = "模型请求已完成（${if (visual?.video == true) "视频" else "图片"}）"
                                    openingDelivered = true
                                    if (input?.observation == true) {
                                        val spoken = VoiceCallPublicText.clean(WaifuMessageProcessor.cleanContentForWaifu(completed))
                                        if (spoken.isNotBlank()) VoiceCallEvents.record(context, chatId, "ai", participantName, spoken).join()
                                    }
                                    if (completed.endsWith("<voice_call_end/>")) finish(EndBy.AI)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("VoiceCall", "Call turn failed", e)
                                viewModel.cancelMessage(chatId)
                                errorMessage = e.message.orEmpty()
                                if (visual != null) cameraDelivery = "画面请求失败：${e.message.orEmpty()}"
                                phase = Phase.ERROR
                            } finally {
                                // Cancellation may arrive during synthesis or an STT network request.
                                // Join cleanup before allowing the next turn to acquire the microphone.
                                withContext(NonCancellable) {
                                    recognizer?.cancelRecognition()
                                    speaker.stop()
                                    // Keep analysis recordings: their paths are part of the persisted turn.
                                    if (!audioAnalysis) audioFile?.delete()
                                    if (visual !== latestVisual) visual?.file?.delete()
                                    activeVisual = null
                                    silenceTurnActive = false
                                    // Blank STT results must not restart the silence timer forever.
                                    if (sentTurn) lastSoundAt = android.os.SystemClock.elapsedRealtime()
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
                        continuousJob?.cancelAndJoin()
                        silenceJob?.cancelAndJoin()
                        silenceJob = null
                        while (silenceReady.tryReceive().isSuccess) { }
                        continuousJob = null
                        pendingHeard.forEach { it.recordings.forEach { file -> file.delete() } }
                        pendingHeard.clear()
                        heardTurnReady = false
                        speechTurnActive.set(false)
                        while (heardReady.tryReceive().isSuccess) { }
                        speech?.shutdown()
                        voice?.shutdown()
                        speech = null
                        voice = null
                        disableCamera()
                        voiceReference?.delete()
                        voiceReference = null
                        pendingTyped.clear()
                        pendingTypedCount = 0
                        while (typedReady.tryReceive().isSuccess) { /* No text survives a finished call. */ }
                        viewModel.setVoiceCallActive(false)
                        try {
                            AIForegroundService.setWakeListeningSuspendedForVoiceCall(context, false)
                        } finally {
                            isRunning = false
                            recordEndEvent()?.join()
                            onStopped()
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveVoiceReference(recording: java.io.File) {
        var copy: java.io.File? = null
        try {
            withContext(Dispatchers.IO) {
                val file = java.io.File.createTempFile("call-voice-reference-", ".wav", context.cacheDir)
                copy = file
                recording.copyTo(file, overwrite = true)
            }
            currentCoroutineContext().ensureActive()
            voiceReference = checkNotNull(copy)
        } finally {
            if (copy !== voiceReference) copy?.delete()
        }
    }

    private data class CallInput(val audio: java.io.File? = null, val text: String = "", val visualOnly: Boolean = false,
        val typed: Boolean = false, val heard: Boolean = false, val observation: Boolean = false, val caption: String = "", val silence: Boolean = false,
        val recordings: List<java.io.File> = emptyList())

    private fun takeSilenceInput(): CallInput? {
        val elapsed = android.os.SystemClock.elapsedRealtime() - lastSoundAt
        if (!companionOptions.silenceEnabled || muted.value || !isConnected || phase != Phase.LISTENING ||
            speechTurnActive.get() || pendingHeard.isNotEmpty() || pendingTyped.isNotEmpty() ||
            elapsed < companionOptions.silenceSeconds * 1000L) return null
        lastSoundAt = android.os.SystemClock.elapsedRealtime()
        return CallInput(text = "[通话静默] 约 ${elapsed / 1000} 秒未检测到新的说话活动；用户并未发送消息。你可以自然接话，或仅输出 <voice_call_quiet/> 继续陪伴。不要推断沉默的原因。",
            silence = true, observation = true)
    }

    private fun takeHeardInput(): CallInput? {
        if (pendingHeard.isEmpty() || muted.value || !heardTurnReady || speechTurnActive.get()) return null
        val clips = pendingHeard.toList()
        pendingHeard.clear()
        heardTurnReady = false
        heardReady.tryReceive()
        val original = clips.map { it.caption }.filter { it.isNotBlank() }.joinToString(" ")
        val header = if (original.isBlank()) "[通话声音观察]" else "[语音通话转写] 【原话】$original"
        return CallInput(text = header + "\n【声音】连续片段，按采集时间排序；不要补齐被截断的词句。\n" +
            clips.joinToString("\n") { it.text }, heard = true, observation = original.isBlank(), caption = original,
            recordings = clips.flatMap { it.recordings })
    }

    private suspend fun awaitCallInput(recorder: VoiceCallAudioRecorder, recognizer: SpeechService?): CallInput = coroutineScope {
        if (audioAnalysis) {
            while (true) {
                val input = select<CallInput?> {
                    heardReady.onReceive { takeHeardInput() }
                    typedReady.onReceive { takeTypedInput() }
                    silenceReady.onReceive { takeSilenceInput() }
                    visualReady.onReceive { CallInput(visualOnly = true) }
                }
                if (input != null) return@coroutineScope input
                // Null means the speaker is still talking or a stale wake-up was consumed.
            }
        }
        val recorded = async {
            if (nativeAudio) CallInput(audio = recordAudioTurn(recorder))
            else CallInput(text = recognizeTurn(checkNotNull(recognizer)))
        }
        var input: CallInput? = null
        while (input == null) {
            input = select<CallInput?> {
                recorded.onAwait { it }
                silenceReady.onReceive {
                    val silent = takeSilenceInput()
                    if (silent != null) recorded.cancelAndJoin()
                    silent
                }
                typedReady.onReceive {
                    // Sending text stops this listening attempt, never the call or an AI reply.
                    recorded.cancelAndJoin()
                    checkNotNull(takeTypedInput())
                }
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
        input
    }

    fun enableCamera(video: Boolean, intervalSeconds: Int) {
        if (!isRunning) return
        check(isConnected) { "请先等待对方接听" }
        check(if (video) supportsCameraVideo else supportsCameraImages) { "当前聊天模型没有启用对应的视觉能力" }
        check(intervalSeconds == 10 || intervalSeconds == 30) { "请选择10秒或30秒" }
        disableCamera()
        cameraEnabled = true
        cameraDelivery = "正在采集第一帧"
        cameraWarning = ""
        cameraVideoMode = video
        cameraIntervalSeconds = intervalSeconds
        val generation = cameraGeneration
        val capture = VoiceCallCamera(context, scope, video, intervalSeconds,
            onMedia = { file, isVideo ->
                if (!cameraEnabled || generation != cameraGeneration) file.delete() else {
                    if (latestVisual !== activeVisual) latestVisual?.file?.delete()
                    val captured = Visual(file, isVideo)
                    latestVisual = captured
                    pendingVisual = captured
                    cameraDelivery = "新画面已采集，等待发送"
                    // Do not queue an immediate visual reply behind TTS: leave time to speak.
                    if (phase == Phase.LISTENING || phase == Phase.MUTED) visualReady.trySend(Unit)
                }
            },
            onError = { if (generation == cameraGeneration) { cameraWarning = it; disableCamera() } })
        camera = capture
        capture.start()
    }

    fun disableCamera() {
        cameraGeneration++
        cameraEnabled = false
        cameraDelivery = ""
        camera?.stop()
        camera = null
        if (latestVisual !== activeVisual) latestVisual?.file?.delete()
        latestVisual = null
        pendingVisual = null
        while (visualReady.tryReceive().isSuccess) { /* Drop old camera wake-ups. */ }
    }

    fun reportCameraError(message: String) { cameraWarning = message; disableCamera() }

    fun reconfirmVoice() {
        if (!audioAnalysis || isConfirmingVoice || (phase != Phase.LISTENING && phase != Phase.MUTED)) return
        turnJob?.cancel()
        voiceReference?.delete()
        voiceReference = null
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
                if (rms >= .012f) lastSoundAt = android.os.SystemClock.elapsedRealtime()
            }
        },
        shouldSubmit = { submitRecording.get() },
        )
    }

    fun sendRecordingNow() {
        if (continuousListening || (nativeAudio && phase == Phase.LISTENING)) submitRecording.set(true)
    }

    private suspend fun recognizeTurn(recognizer: SpeechService): String = coroutineScope {
        val result = CompletableDeferred<String>()
        // Reuse the STT microphone meter; no second recorder is needed for animation.
        val levels = launch {
            recognizer.volumeLevelFlow.collect {
                microphoneLevel = it.coerceIn(0f, 1f)
                if (it > .12f) soundDetected = true
                if (it > .12f) lastSoundAt = android.os.SystemClock.elapsedRealtime()
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
                silenceDurationMs = 2000,
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
        if (!isConnected) return
        microphoneEpoch.incrementAndGet()
        muted.value = !muted.value
        if (muted.value) {
            pendingHeard.forEach { it.recordings.forEach { file -> file.delete() } }
            pendingHeard.clear()
            heardTurnReady = false
            while (heardReady.tryReceive().isSuccess) { }
        }
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

    fun finish(by: EndBy = EndBy.USER) {
        if (phase == Phase.ENDED) return
        endedBy = by
        if (viewModel.activeStreamingChatIds.value.contains(chatId)) viewModel.cancelMessage(chatId)
        phase = Phase.ENDED
        sessionJob?.cancel()
        // A paused call has already left the session's finally block.
        if (!isRunning) recordEndEvent()
    }

    private fun recordEndEvent(): Job? {
        val by = endedBy ?: return null
        if (endRecorded) return null
        endRecorded = true
        val sender = if (by == EndBy.AI) "ai" else "user"
        val name = if (by == EndBy.AI) participantName else context.getString(R.string.message_role_user)
        val event = if (by == EndBy.SYSTEM) "通话因服务停止而结束"
            else if (isConnected) "通话结束" else "已取消拨号，未接通"
        if (by == EndBy.USER && isConnected) {
            // Send once through the conversation pipeline; a history-only entry never wakes AI.
            viewModel.sendVoiceCallEnded(chatId, roleCardId)
            return null
        }
        return VoiceCallEvents.record(context, chatId, sender, name, event)
    }
}
