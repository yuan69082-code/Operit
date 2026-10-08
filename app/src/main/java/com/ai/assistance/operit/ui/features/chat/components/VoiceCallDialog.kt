package com.ai.assistance.operit.ui.features.chat.components

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallController
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime
import kotlinx.coroutines.delay

@Composable
fun VoiceCallDialog(controller: VoiceCallController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val muted by controller.isMuted.collectAsState()
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(controller) {
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - controller.startedAt) / 1000
            delay(1000)
        }
    }
    val status = when (controller.phase) {
        VoiceCallController.Phase.CONNECTING -> R.string.voice_call_connecting
        VoiceCallController.Phase.LISTENING -> R.string.voice_call_listening
        VoiceCallController.Phase.RECOGNIZING -> R.string.voice_call_recognizing
        VoiceCallController.Phase.THINKING -> R.string.voice_call_thinking
        VoiceCallController.Phase.SPEAKING -> R.string.voice_call_speaking
        VoiceCallController.Phase.MUTED -> R.string.voice_call_muted
        VoiceCallController.Phase.ERROR -> R.string.voice_call_error
        VoiceCallController.Phase.ENDED -> R.string.voice_call_ended
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.voice_call_title), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_call_minimize)) }
                Text("${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}")
                Text(stringResource(status), style = MaterialTheme.typography.titleMedium)
                if ((controller.nativeAudio || controller.audioAnalysis) && controller.phase == VoiceCallController.Phase.LISTENING) {
                    Text("正在录音 · ${controller.recordingMillis / 1000} 秒；停顿后开始转写，录音时不会逐字显示。")
                    LinearProgressIndicator(progress = { controller.microphoneLevel }, modifier = Modifier.fillMaxWidth())
                    Text(if (controller.soundDetected) "已检测到声音" else "等待声音，也可以手动发送最近最多12秒录音")
                    OutlinedButton(onClick = controller::sendRecordingNow, enabled = controller.recordingMillis > 0) {
                        Text("发送这段录音")
                    }
                }
                if (controller.analysisWarning.isNotBlank()) {
                    Text(controller.analysisWarning, color = MaterialTheme.colorScheme.error)
                    com.ai.assistance.operit.ui.features.settings.screens.VoiceCallAnalysisSettingsButton()
                }
                Text(stringResource(R.string.voice_call_hint), style = MaterialTheme.typography.bodySmall)
                if (controller.transcript.isNotBlank()) {
                    Text(stringResource(R.string.voice_call_you), style = MaterialTheme.typography.labelLarge)
                    Text(controller.transcript)
                }
                if (controller.reply.isNotBlank()) {
                    Text(stringResource(R.string.voice_call_reply), style = MaterialTheme.typography.labelLarge)
                    Text(controller.reply)
                }
                if (controller.phase == VoiceCallController.Phase.ERROR) {
                    Text(controller.errorMessage, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { VoiceCallRuntime.restart(context) }, enabled = !controller.isRunning) {
                        Text(stringResource(R.string.voice_call_retry))
                    }
                } else {
                    OutlinedButton(onClick = controller::toggleMute, enabled = controller.phase != VoiceCallController.Phase.CONNECTING) {
                        Text(stringResource(if (muted) R.string.voice_call_unmute else R.string.voice_call_mute))
                    }
                    Button(
                        onClick = controller::interrupt,
                        enabled = controller.phase == VoiceCallController.Phase.THINKING ||
                            controller.phase == VoiceCallController.Phase.SPEAKING,
                    ) { Text(stringResource(R.string.voice_call_interrupt)) }
                }
                Button(onClick = { VoiceCallRuntime.hangUp(); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.voice_call_hang_up))
                }
            }
        }
    }
}
