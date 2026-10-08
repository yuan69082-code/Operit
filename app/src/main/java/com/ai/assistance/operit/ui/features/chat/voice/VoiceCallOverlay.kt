package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import com.ai.assistance.operit.ui.features.chat.components.CallPortrait
import com.ai.assistance.operit.ui.features.chat.components.CallMicrophoneWave
import com.ai.assistance.operit.ui.features.chat.components.VoiceCallCameraControls
import com.ai.assistance.operit.ui.features.chat.components.VoiceCallCameraPreview
import com.ai.assistance.operit.ui.floating.FloatingWindowTheme

/** Only this window receives touches; the rest of the screen stays usable. */
class VoiceCallOverlay(private val context: Context, private val call: VoiceCallController) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val owner = ServiceLifecycleOwner()
    private var view: ComposeView? = null
    private var compact by mutableStateOf(false)
    private val density = context.resources.displayMetrics.density
    private val params = WindowManager.LayoutParams(
        (280 * density).toInt().coerceAtMost(context.resources.displayMetrics.widthPixels),
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END; x = (12 * density).toInt(); y = (80 * density).toInt() }

    fun show() {
        compact = false
        if (view != null) { resize(); return }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                FloatingWindowTheme {
                    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    params.x = (params.x - drag.x.toInt()).coerceIn(0, (context.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                                    params.y = (params.y + drag.y.toInt()).coerceIn(0, (context.resources.displayMetrics.heightPixels - (view?.height ?: 0)).coerceAtLeast(0))
                                    view?.let { manager.updateViewLayout(it, params) }
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (compact) "通话" else call.participantName, modifier = Modifier.weight(1f), maxLines = 1)
                                IconButton(onClick = { compact = !compact; resize() }, modifier = Modifier.size(40.dp)) {
                                    Icon(if (compact) Icons.Default.OpenInFull else Icons.Default.FullscreenExit, contentDescription = if (compact) "展开通话" else "收起通话")
                                }
                            }
                            if (!compact) {
                                if (call.phase == VoiceCallController.Phase.RINGING) Text("等待对方接听…")
                                if (call.cameraEnabled) {
                                    call.camera?.let { VoiceCallCameraPreview(it, Modifier.fillMaxWidth().height(260.dp)) }
                                } else Box(Modifier.height(110.dp), contentAlignment = Alignment.Center) {
                                    CallPortrait(call.participantAvatarUri, call.participantName, call.phase == VoiceCallController.Phase.SPEAKING, small = true)
                                }
                                CallMicrophoneWave(if (call.phase == VoiceCallController.Phase.LISTENING) call.microphoneLevel else 0f)
                                if (call.phase == VoiceCallController.Phase.SPEAKER_SETUP) {
                                    TextButton(onClick = call::recordMyVoice) { Text("确认我的声音") }
                                }
                                if (call.reply.isNotBlank()) Text(call.reply, maxLines = 3)
                                else if (call.transcript.isNotBlank()) Text(call.transcript, maxLines = 3)
                                if ((call.audioAnalysis || call.nativeAudio) && call.phase == VoiceCallController.Phase.LISTENING) {
                                    TextButton(onClick = call::sendRecordingNow) { Text("发送录音") }
                                }
                                VoiceCallCameraControls(call, showPreview = false)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    val muted by call.isMuted.collectAsState()
                                    IconButton(onClick = call::toggleMute, enabled = call.isConnected) { Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, "静音") }
                                    FilledIconButton(onClick = VoiceCallRuntime::hangUp, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.CallEnd, "挂断") }
                                    IconButton(onClick = call::interrupt, enabled = call.phase == VoiceCallController.Phase.SPEAKING || call.phase == VoiceCallController.Phase.THINKING) { Icon(Icons.Default.RecordVoiceOver, "打断说话") }
                                }
                            }
                        }
                    }
                }
            }
        }
        view = content
        try { manager.addView(content, params) }
        catch (error: Exception) {
            view = null
            content.disposeComposition()
            com.ai.assistance.operit.util.AppLogger.e("VoiceCallOverlay", "Could not display overlay", error)
        }
    }

    private fun resize() {
        params.width = ((if (compact) 120 else 280) * density).toInt().coerceAtMost(context.resources.displayMetrics.widthPixels)
        view?.let { manager.updateViewLayout(it, params) }
    }

    fun destroy() {
        view?.let { manager.removeView(it); it.disposeComposition() }
        view = null
        owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        owner.viewModelStore.clear()
    }
}
