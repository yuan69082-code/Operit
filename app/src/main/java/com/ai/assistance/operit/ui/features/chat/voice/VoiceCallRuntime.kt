package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Owns the call independently of any screen or Activity lifecycle. */
object VoiceCallRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var controller by mutableStateOf<VoiceCallController?>(null)
        private set
    val isActive: Boolean get() = controller?.isRunning == true

    fun open(context: Context, viewModel: ChatViewModel, chatId: String, nativeAudio: Boolean) {
        if (controller != null) return
        val appContext = context.applicationContext
        controller = VoiceCallController(appContext, scope, viewModel, chatId, nativeAudio) {
            appContext.stopService(Intent(appContext, VoiceCallService::class.java))
            if (controller?.phase == VoiceCallController.Phase.ENDED) controller = null
        }
        try {
            restart(appContext)
        } catch (error: Exception) {
            controller = null
            throw error
        }
    }

    fun restart(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java))
    }

    fun hangUp() {
        val call = controller ?: return
        call.finish()
        if (!call.isRunning) controller = null
    }
}
