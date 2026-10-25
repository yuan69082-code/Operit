package com.ai.assistance.operit.ui.features.chat.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.chat.voice.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun rememberVoiceMessageGesture(viewModel: ChatViewModel, enabled: Boolean): Modifier {
    val context = LocalContext.current
    val chatId by viewModel.currentChatId.collectAsState()
    val busy by viewModel.voiceMessageBusy.collectAsState()
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var recording by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.showToast(if (granted) "麦克风已开启，请按住说话" else "发送语音需要麦克风权限")
    }
    if (!enabled) return Modifier
    return Modifier            .pointerInput(chatId, enabled, busy, foreground) {
                detectTapGestures(onPress = {
                    val target = chatId
                    if (!enabled || busy || !foreground || target == null) return@detectTapGestures
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    // A tap no longer opens the voice page. Begin only after holding.
                    if (withTimeoutOrNull(300) { tryAwaitRelease() } != null) return@detectTapGestures
                    try {
                        check(VoiceCallRuntime.controller == null && VoiceCallIncoming.pending == null) { "请先结束通话或处理来电" }
                        VoiceCallAudioAnalysis(context).requireConfigured()
                        coroutineScope {
                            val released = AtomicBoolean(false)
                            val recorder = VoiceCallAudioRecorder(context.applicationContext)
                            var captured: java.io.File? = null
                            var handedOff = false
                            val audio = async { recorder.recordTurn(shouldSubmit = { released.get() }, manual = true).also { captured = it } }
                            try {
                            recording = true
                            viewModel.showToast("正在录音，松开发送，移出取消")
                            if (tryAwaitRelease()) {
                                released.set(true)
                                val file = audio.await()
                                if (file.length() < 44 + 16000) {
                                    file.delete()
                                    viewModel.showToast("说话时间太短，请重新按住说话")
                                } else {
                                    viewModel.sendVoiceMessage(file, recorder.lastQuietIntervals, target)
                                    handedOff = true
                                }
                            } else {
                                audio.cancelAndJoin()
                            }
                            } finally {
                                withContext(NonCancellable) { audio.cancelAndJoin() }
                                if (!handedOff) captured?.delete()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        com.ai.assistance.operit.util.AppLogger.e("VoiceMessage", "Recording failed", error)
                        viewModel.showToast(error.message.orEmpty())
                    } finally {
                        recording = false
                    }
                })
            }
}
