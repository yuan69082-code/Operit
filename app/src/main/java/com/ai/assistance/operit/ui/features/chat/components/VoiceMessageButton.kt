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
fun rememberVoiceMessageGesture(viewModel: ChatViewModel, enabled: Boolean, onTap: () -> Unit = {}): Modifier {
    val context = LocalContext.current
    val chatId by viewModel.currentChatId.collectAsState()
    val tapAction by rememberUpdatedState(onTap)
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.showToast(if (granted) "麦克风已开启，请按住说话" else "发送语音需要麦克风权限")
    }
    if (!enabled) return Modifier
    return Modifier.pointerInput(chatId, enabled, foreground) {
                detectTapGestures(onPress = {
                    val target = chatId
                    if (!enabled || !foreground || target == null) return@detectTapGestures
                    // A tap no longer opens the voice page. Begin only after holding.
                    val earlyRelease = withTimeoutOrNull(300) { tryAwaitRelease() }
                    if (earlyRelease != null) { if (earlyRelease) tapAction(); return@detectTapGestures }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    try {
                        check(VoiceCallRuntime.controller == null && VoiceCallIncoming.pending == null) { "请先结束通话或处理来电" }
                        coroutineScope {
                            val released = AtomicBoolean(false)
                            val recorder = VoiceCallAudioRecorder(context.applicationContext)
                            var captured: java.io.File? = null
                            var handedOff = false
                            val audio = async { recorder.recordTurn(shouldSubmit = { released.get() }, manual = true,
                                onProgress = { rms, millis, _ -> viewModel.updateVoiceRecording(true, rms, millis) }).also { captured = it } }
                            try {
                            viewModel.updateVoiceRecording(true)
                            if (tryAwaitRelease()) {
                                released.set(true)
                                val file = audio.await()
                                viewModel.sendVoiceMessage(file, recorder.lastQuietIntervals, target)
                                handedOff = true
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
                        viewModel.updateVoiceRecording(false)
                    }
                })
            }
}
