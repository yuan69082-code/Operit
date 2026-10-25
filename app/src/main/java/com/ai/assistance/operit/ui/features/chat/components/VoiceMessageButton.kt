package com.ai.assistance.operit.ui.features.chat.components

import android.Manifest
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.chat.voice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class VoiceHoldEnd { SEND, CANCEL_BY_SWIPE, INTERRUPTED }

private class VoiceHoldSession(val pointerId: Int, val startY: Float) {
    @Volatile var end: VoiceHoldEnd? = null
    @Volatile var started = false
    var cancelArmed = false
}

/**
 * Own the pointer from DOWN until UP/CANCEL, even if it moves far outside the 36dp button.
 * A finger leaving the button is not an Android ACTION_CANCEL.
 */
@Composable
fun rememberVoiceMessageGesture(viewModel: ChatViewModel, enabled: Boolean, onTap: () -> Unit = {}): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    val swipeCancelPx = with(LocalDensity.current) { 80.dp.toPx() }
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
    val activeSession = remember { mutableStateOf<VoiceHoldSession?>(null) }
    DisposableEffect(enabled, chatId) {
        onDispose {
            activeSession.value?.let { if (it.end == null) it.end = VoiceHoldEnd.INTERRUPTED }
            activeSession.value = null
            view.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }
    if (!enabled) return Modifier
    return Modifier.pointerInteropFilter { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = chatId
                if (!foreground || target == null) {
                    false
                } else {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val session = VoiceHoldSession(event.getPointerId(0), event.getY(0))
                    activeSession.value = session
                    // Keep recording outside the composable's gesture scope. A cancelled touch
                    // must request a clean finish, not cancel the AudioRecord coroutine.
                    viewModel.viewModelScope.launch {
                        delay(300)
                        if (session.end != null) return@launch
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                            return@launch
                        }
                        try {
                            check(VoiceCallRuntime.controller == null && VoiceCallIncoming.pending == null) {
                                "请先结束通话或处理来电"
                            }
                            session.started = true
                            val recorder = VoiceCallAudioRecorder(context.applicationContext)
                            viewModel.updateVoiceRecording(true)
                            val file = recorder.recordTurn(
                                shouldSubmit = { session.end != null },
                                manual = true,
                                onProgress = { rms, millis, _ ->
                                    viewModel.updateVoiceRecording(true, rms, millis)
                                }
                            )
                            when (session.end ?: VoiceHoldEnd.INTERRUPTED) {
                                VoiceHoldEnd.SEND -> viewModel.sendVoiceMessage(file, recorder.lastQuietIntervals, target)
                                VoiceHoldEnd.INTERRUPTED -> viewModel.saveInterruptedVoiceMessage(
                                    file, recorder.lastQuietIntervals, target
                                )
                                VoiceHoldEnd.CANCEL_BY_SWIPE -> file.delete()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            com.ai.assistance.operit.util.AppLogger.e("VoiceMessage", "Recording failed", error)
                            viewModel.showToast(error.message.orEmpty())
                        } finally {
                            viewModel.updateVoiceRecording(false)
                        }
                    }
                    true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val session = activeSession.value
                if (session != null && session.end == null) {
                    val index = event.findPointerIndex(session.pointerId)
                    if (index < 0) {
                        session.end = VoiceHoldEnd.INTERRUPTED
                        viewModel.updateVoiceCancelArmed(false)
                    } else {
                        val armed = event.getY(index) - session.startY <= -swipeCancelPx
                        if (session.cancelArmed != armed) {
                            session.cancelArmed = armed
                            viewModel.updateVoiceCancelArmed(armed)
                        }
                    }
                }
                session != null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val session = activeSession.value
                if (session != null) {
                    val matches = event.actionMasked == MotionEvent.ACTION_CANCEL ||
                        event.getPointerId(event.actionIndex) == session.pointerId
                    if (matches) {
                        val interrupted = event.actionMasked == MotionEvent.ACTION_CANCEL
                        if (session.end == null) {
                            session.end = when {
                                interrupted -> VoiceHoldEnd.INTERRUPTED
                                session.cancelArmed -> VoiceHoldEnd.CANCEL_BY_SWIPE
                                else -> VoiceHoldEnd.SEND
                            }
                        }
                        if (!session.started && !interrupted && !session.cancelArmed &&
                            event.eventTime - event.downTime < 300) {
                            tapAction()
                        }
                        activeSession.value = null
                        viewModel.updateVoiceCancelArmed(false)
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                session != null
            }
            else -> activeSession.value != null
        }
    }
}
