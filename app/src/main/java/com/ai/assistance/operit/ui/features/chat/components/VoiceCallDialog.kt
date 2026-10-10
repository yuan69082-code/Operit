package com.ai.assistance.operit.ui.features.chat.components

import android.os.SystemClock
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallController
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime
import kotlinx.coroutines.delay

@Composable
fun VoiceCallDialog(controller: VoiceCallController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var requestedVideo by remember { mutableStateOf(false) }
    var requestedInterval by remember { mutableStateOf(10) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceCallRuntime.enableCamera(context, requestedVideo, requestedInterval)
        else controller.reportCameraError("摄像头权限未开启，语音通话继续。")
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) { VoiceCallRuntime.showWindow(context); onDismiss() }
    }
    val muted by controller.isMuted.collectAsState()
    var elapsedSeconds by remember(controller) { mutableStateOf(0L) }
    LaunchedEffect(controller) {
        while (true) {
            elapsedSeconds = if (controller.isConnected) (SystemClock.elapsedRealtime() - controller.startedAt) / 1000 else 0
            delay(1000)
        }
    }
    val phase = controller.phase
    val speaking = phase == VoiceCallController.Phase.SPEAKING
    val listening = (controller.continuousListening || phase == VoiceCallController.Phase.LISTENING) && !muted
    val status = when (phase) {
        VoiceCallController.Phase.CONNECTING -> R.string.voice_call_connecting
        VoiceCallController.Phase.RINGING -> R.string.voice_call_ringing
        VoiceCallController.Phase.LISTENING -> R.string.voice_call_listening
        VoiceCallController.Phase.RECOGNIZING -> R.string.voice_call_recognizing
        VoiceCallController.Phase.THINKING -> R.string.voice_call_thinking
        VoiceCallController.Phase.SPEAKING -> R.string.voice_call_speaking
        VoiceCallController.Phase.MUTED -> R.string.voice_call_muted
        VoiceCallController.Phase.ERROR -> R.string.voice_call_error
        VoiceCallController.Phase.ENDED -> R.string.voice_call_ended
    }
    val video = controller.cameraEnabled
    val colors = if (video) darkColorScheme() else MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
            Box(Modifier.fillMaxSize()) {
                if (video) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                    controller.camera?.let { VoiceCallCameraPreview(it, Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, Color.Black.copy(alpha = .8f)))))
                }
            Column(
                Modifier.fillMaxSize()
                    .then(if (video) Modifier else Modifier.background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = .10f), colors.surface, colors.tertiary.copy(alpha = .08f)))))
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.voice_call_title), color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (Settings.canDrawOverlays(context)) { VoiceCallRuntime.showWindow(context); onDismiss() }
                        else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.voice_call_minimize))
                    }
                }
                // Keep controls anchored even with long captions or a small display.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = if (video) Alignment.BottomCenter else Alignment.Center) {
                    Column(
                        Modifier.fillMaxWidth().then(if (video) Modifier.heightIn(max = 260.dp) else Modifier)
                            .verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (!video) {
                            CallPortrait(controller.participantAvatarUri, controller.participantName, speaking)
                            Text(controller.participantName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        }
                        Text(stringResource(status), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        if (controller.isConnected) Text("${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium)
                        CallMicrophoneWave(if (listening) controller.microphoneLevel else 0f)
                        if (controller.continuousListening && !controller.manualMode) Text("持续收音 · 对方说话时也能听见", style = MaterialTheme.typography.labelSmall)
                        if (controller.continuousWarning.isNotBlank()) Text(controller.continuousWarning, style = MaterialTheme.typography.bodySmall, color = colors.error)
                        VoiceCallCameraControls(controller, showPreview = false) { video, interval ->
                            requestedVideo = video
                            requestedInterval = interval
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                VoiceCallRuntime.enableCamera(context, video, interval)
                            } else cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                        if (controller.transcript.isNotBlank() || controller.reply.isNotBlank()) {
                            Surface(color = colors.surface.copy(alpha = .75f), shape = RoundedCornerShape(24.dp)) {
                                Column(
                                    Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (controller.transcript.isNotBlank()) {
                                        Text(stringResource(R.string.voice_call_you), color = colors.primary, style = MaterialTheme.typography.labelMedium)
                                        Text(controller.transcript, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    if (controller.reply.isNotBlank()) {
                                        Text(controller.participantName, color = colors.primary, style = MaterialTheme.typography.labelMedium)
                                        Text(controller.reply, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                        if (controller.recordingNotice.isNotBlank()) {
                            Text(controller.recordingNotice, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !controller.manualMode, onClick = { controller.setManualInput(false) }, label = { Text("实时收音") },
                                enabled = !controller.manualRecording && phase in setOf(VoiceCallController.Phase.LISTENING, VoiceCallController.Phase.MUTED))
                            FilterChip(selected = controller.manualMode, onClick = { controller.setManualInput(true) }, label = { Text("分段发送") },
                                enabled = !controller.manualRecording && phase in setOf(VoiceCallController.Phase.LISTENING, VoiceCallController.Phase.MUTED))
                        }
                        if (controller.manualMode) Text("点开始录音，说完点发送。每段最长60秒，等待回复时暂停收音。", style = MaterialTheme.typography.bodySmall)
                        if (controller.manualMode && !controller.manualRecording && phase == VoiceCallController.Phase.LISTENING && !muted) {
                            Button(onClick = controller::startManualRecording) { Text("开始录音") }
                        }
                        if ((controller.nativeAudio || controller.audioAnalysis || controller.manualMode) && listening && (!controller.manualMode || controller.manualRecording)) {
                            TextButton(onClick = controller::sendRecordingNow, enabled = controller.recordingMillis > 0) {
                                Text("发送这段录音 · ${controller.recordingMillis / 1000} 秒")
                            }
                        }
                        if (controller.audioAnalysis && !controller.isConfirmingVoice &&
                            (listening || phase == VoiceCallController.Phase.MUTED)) {
                            TextButton(onClick = controller::reconfirmVoice) { Text("重新确认我的声音") }
                        }
                        if (controller.analysisWarning.isNotBlank()) {
                            Text(controller.analysisWarning, color = colors.error, textAlign = TextAlign.Center)
                            com.ai.assistance.operit.ui.features.settings.screens.VoiceCallAnalysisSettingsButton()
                        }
                        if (phase == VoiceCallController.Phase.ERROR) {
                            Text(controller.errorMessage, color = colors.error, textAlign = TextAlign.Center)
                            Button(onClick = { VoiceCallRuntime.restart(context) }, enabled = !controller.isRunning) {
                                Text(stringResource(R.string.voice_call_retry))
                            }
                        }
                    }
                }
                VoiceCallTextInput(controller)
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallControl(
                        if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                        stringResource(if (muted) R.string.voice_call_unmute else R.string.voice_call_mute),
                        enabled = controller.isConnected && phase != VoiceCallController.Phase.CONNECTING && phase != VoiceCallController.Phase.ERROR && phase != VoiceCallController.Phase.ENDED,
                        selected = muted, onClick = controller::toggleMute,
                    )
                    CallControl(Icons.Default.CallEnd, stringResource(R.string.voice_call_hang_up), hangUp = true,
                        onClick = { VoiceCallRuntime.hangUp(); onDismiss() })
                    CallControl(Icons.Default.RecordVoiceOver, "打断说话", enabled = speaking || phase == VoiceCallController.Phase.THINKING,
                        onClick = controller::interrupt)
                }
            }
            }
        }
        }
    }
}

@Composable
internal fun CallPortrait(avatarUri: String?, name: String, speaking: Boolean, small: Boolean = false) {
    // Waiting for the model is not speech: animate only during voice playback.
    val scale = if (speaking) {
        val transition = rememberInfiniteTransition(label = "call speaking")
        val value by transition.animateFloat(1f, 1.07f,
            animationSpec = infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "portrait pulse")
        value
    } else 1f
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(if (small) 110.dp else 220.dp), contentAlignment = Alignment.Center) {
        if (!small) {
        Box(Modifier.size(210.dp).scale(scale).border(1.dp, color.copy(alpha = .12f), CircleShape))
        Box(Modifier.size(190.dp).scale(scale).background(color.copy(alpha = .08f), CircleShape))
        }
        Surface(modifier = Modifier.size(if (small) 88.dp else 164.dp).scale(scale), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 8.dp) {
            if (!avatarUri.isNullOrBlank()) {
                AsyncImage(model = avatarUri, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = name, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
internal fun CallMicrophoneWave(level: Float) {
    val amplitude by animateFloatAsState(level.coerceIn(0f, 1f), tween(100), label = "microphone amplitude")
    val ripple = if (level > .04f) {
        val transition = rememberInfiniteTransition(label = "microphone ripple")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "ripple radius")
        value
    } else 0f
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(width = 160.dp, height = 44.dp)) {
        if (amplitude > .04f) {
            drawCircle(color.copy(alpha = amplitude * (1f - ripple) * .25f), radius = (8f + 13f * ripple) * density)
        }
        val weights = listOf(.25f, .48f, .72f, 1f, .72f, .48f, .25f)
        weights.forEachIndexed { index, weight ->
            val x = center.x + (index - 3) * 12.dp.toPx()
            val halfHeight = 2.dp.toPx() + amplitude * weight * 16.dp.toPx()
            drawLine(color.copy(alpha = .35f + amplitude * .65f), Offset(x, center.y - halfHeight), Offset(x, center.y + halfHeight), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun CallControl(icon: ImageVector, label: String, enabled: Boolean = true, selected: Boolean = false, hangUp: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        hangUp -> colors.error
        selected -> colors.primary
        else -> colors.surfaceVariant
    }
    val foreground = when {
        hangUp -> colors.onError
        selected -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledIconButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.size(if (hangUp) 72.dp else 60.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = background, contentColor = foreground),
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface.copy(alpha = if (enabled) 1f else .4f), textAlign = TextAlign.Center)
    }
}
