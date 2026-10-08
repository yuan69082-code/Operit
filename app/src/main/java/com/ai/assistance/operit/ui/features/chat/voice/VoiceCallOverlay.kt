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
import com.ai.assistance.operit.ui.features.chat.components.VoiceCallTextInput
import com.ai.assistance.operit.ui.features.chat.components.CallPortrait
import com.ai.assistance.operit.ui.features.chat.components.CallMicrophoneWave
import com.ai.assistance.operit.ui.features.chat.components.VoiceCallCameraControls
import com.ai.assistance.operit.ui.features.chat.components.VoiceCallCameraPreview
import com.ai.assistance.operit.ui.floating.FloatingWindowTheme
import kotlin.math.roundToInt

/** Only this window receives touches; hiding it does not stop the service or call. */
class VoiceCallOverlay(private val context: Context, private val call: VoiceCallController) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var owner: ServiceLifecycleOwner? = null
    private var view: ComposeView? = null
    private var compact by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)
    private val density = context.resources.displayMetrics.density
    private var expandedWidth = 280 * density
    private var expandedHeight by mutableStateOf(520 * density)
    private val params = WindowManager.LayoutParams(
        expandedWidth.roundToInt(), expandedHeight.roundToInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = (context.resources.displayMetrics.widthPixels - expandedWidth - 12 * density).roundToInt().coerceAtLeast(0)
        y = (80 * density).roundToInt()
    }

    fun show() {
        compact = false
        resize()
        if (view != null) return
        // A destroyed lifecycle cannot be resumed when reopening the hidden window.
        val windowOwner = ServiceLifecycleOwner()
        owner = windowOwner
        windowOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        windowOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowOwner)
            setViewTreeViewModelStoreOwner(windowOwner)
            setViewTreeSavedStateRegistryOwner(windowOwner)
            setContent {
                FloatingWindowTheme {
                    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    params.x += drag.x.roundToInt()
                                    params.y += drag.y.roundToInt()
                                    updateWindow()
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (compact) "通话" else call.participantName, modifier = Modifier.weight(1f), maxLines = 1)
                                IconButton(onClick = {
                                    compact = false
                                    fullscreen = !fullscreen
                                    resize()
                                }, modifier = Modifier.size(40.dp)) {
                                    Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = if (fullscreen) "恢复小窗" else "恢复完整通话画面")
                                }
                                IconButton(onClick = { fullscreen = false; compact = !compact; resize() }, modifier = Modifier.size(40.dp)) {
                                    Icon(if (compact) Icons.Default.OpenInFull else Icons.Default.FullscreenExit, contentDescription = if (compact) "展开通话" else "收起通话")
                                }
                                IconButton(onClick = ::hide, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "临时隐藏，通话继续")
                                }
                            }
                            if (!compact) {
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (call.phase == VoiceCallController.Phase.RINGING) Text("等待对方接听…")
                                    if (call.cameraEnabled) {
                                        call.camera?.let {
                                            VoiceCallCameraPreview(it, Modifier.fillMaxWidth().height(((if (fullscreen) params.height.toFloat() else expandedHeight) / density * 0.5f).coerceAtLeast(140f).dp))
                                        }
                                    } else Box(Modifier.height(110.dp), contentAlignment = Alignment.Center) {
                                        CallPortrait(call.participantAvatarUri, call.participantName, call.phase == VoiceCallController.Phase.SPEAKING, small = true)
                                    }
                                    CallMicrophoneWave(if (call.phase == VoiceCallController.Phase.LISTENING) call.microphoneLevel else 0f)
                                    if (call.reply.isNotBlank()) Text(call.reply, maxLines = 3)
                                    else if (call.transcript.isNotBlank()) Text(call.transcript, maxLines = 3)
                                    if ((call.audioAnalysis || call.nativeAudio) && call.phase == VoiceCallController.Phase.LISTENING) {
                                        TextButton(onClick = call::sendRecordingNow) { Text("发送录音") }
                                    }
                                    VoiceCallCameraControls(call, showPreview = false)
                                }
                                VoiceCallTextInput(call, onEditingChanged = ::setEditing)
                                // Keep call controls reachable even when the preview or subtitles scroll.
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    val muted by call.isMuted.collectAsState()
                                    IconButton(onClick = call::toggleMute, enabled = call.isConnected) { Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, "静音") }
                                    FilledIconButton(onClick = VoiceCallRuntime::hangUp, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.CallEnd, "挂断") }
                                    IconButton(onClick = call::interrupt, enabled = call.phase == VoiceCallController.Phase.SPEAKING || call.phase == VoiceCallController.Phase.THINKING) { Icon(Icons.Default.RecordVoiceOver, "打断说话") }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("拖动右下角调整大小", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                    Icon(Icons.Default.OpenInFull, contentDescription = "拖动调整通话窗口大小", modifier = Modifier.size(28.dp).pointerInput(Unit) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            fullscreen = false
                                            expandedWidth += drag.x
                                            expandedHeight += drag.y
                                            resize()
                                        }
                                    })
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
            content.disposeComposition()
            view = null
            releaseOwner()
            com.ai.assistance.operit.util.AppLogger.e("VoiceCallOverlay", "Could not display overlay", error)
        }
    }

    private fun resize() {
        val metrics = context.resources.displayMetrics
        expandedWidth = expandedWidth.coerceIn((240 * density).coerceAtMost(metrics.widthPixels.toFloat()), metrics.widthPixels.toFloat())
        expandedHeight = expandedHeight.coerceIn((320 * density).coerceAtMost(metrics.heightPixels.toFloat()), metrics.heightPixels.toFloat())
        if (fullscreen) {
            // Restore the complete call size without opening the underlying Operit Activity.
            params.width = metrics.widthPixels
            params.height = metrics.heightPixels
            params.x = 0
            params.y = 0
            updateWindow()
            return
        }
        params.width = (if (compact) (160 * density).coerceAtMost(metrics.widthPixels.toFloat()) else expandedWidth).roundToInt()
        params.height = (if (compact) 52 * density else expandedHeight).roundToInt()
        updateWindow()
    }

    private fun updateWindow() {
        val metrics = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
        view?.let { manager.updateViewLayout(it, params) }
    }

    private fun setEditing(editing: Boolean) {
        // Non-focusable overlays cannot receive an IME. Only acquire focus while typing.
        params.flags = if (editing) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (editing) params.y = 0
        updateWindow()
    }

    fun hide() {
        // Clear the view reference before disposal, whose composer releases IME focus.
        val hidden = view
        view = null
        hidden?.let { manager.removeView(it); it.disposeComposition() }
        setEditing(false)
        releaseOwner()
    }

    private fun releaseOwner() {
        owner?.let {
            it.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            it.viewModelStore.clear()
        }
        owner = null
    }

    fun destroy() = hide()
}
