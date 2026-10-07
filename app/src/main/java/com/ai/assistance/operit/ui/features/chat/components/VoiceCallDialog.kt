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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallController
import kotlinx.coroutines.delay

@Composable
fun VoiceCallDialog(viewModel: ChatViewModel, chatId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(viewModel, chatId) {
        VoiceCallController(context.applicationContext, scope, viewModel, chatId)
    }
    val currentChatId by viewModel.currentChatId.collectAsState()
    val muted by controller.isMuted.collectAsState()
    val close by rememberUpdatedState(onDismiss)
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(controller) {
        controller.start()
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            delay(1000)
        }
    }
    LaunchedEffect(currentChatId) {
        if (currentChatId != chatId) close()
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // This first version is a foreground call: leaving the app hangs up and releases audio.
            if (event == Lifecycle.Event.ON_STOP) close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.finish()
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
                Text("${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}")
                Text(stringResource(status), style = MaterialTheme.typography.titleMedium)
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
                    Button(onClick = controller::start, enabled = !controller.isRunning) {
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
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.voice_call_hang_up))
                }
            }
        }
    }
}
