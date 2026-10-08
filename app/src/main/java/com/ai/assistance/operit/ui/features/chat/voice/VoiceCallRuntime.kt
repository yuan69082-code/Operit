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

    fun open(context: Context, viewModel: ChatViewModel, chatId: String, nativeAudio: Boolean, audioAnalysis: Boolean = false, callerRoleCardId: String? = null, incoming: Boolean = false) {
        check(controller == null) { "已有通话，请先挂断" }
        val appContext = context.applicationContext
        controller = VoiceCallController(appContext, scope, viewModel, chatId, nativeAudio, audioAnalysis, callerRoleCardId, incoming) {
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

    fun showWindow(context: Context) {
        check(android.provider.Settings.canDrawOverlays(context)) { "请先开启显示在其他应用上层权限" }
        context.startService(Intent(context, VoiceCallService::class.java).setAction(VoiceCallService.ACTION_SHOW_WINDOW))
    }

    fun enableCamera(context: Context, video: Boolean, interval: Int) {
        check(controller?.isRunning == true) { "请先接通语音电话" }
        check(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "请先在 Operit 通话界面授予摄像头权限" }
        context.startService(Intent(context, VoiceCallService::class.java).setAction(VoiceCallService.ACTION_CAMERA_ON)
            .putExtra("video", video).putExtra("interval", interval))
    }

    fun hangUp() {
        val call = controller ?: return
        call.finish()
        if (!call.isRunning) controller = null
    }
}
