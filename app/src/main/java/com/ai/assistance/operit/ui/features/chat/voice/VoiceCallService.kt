package com.ai.assistance.operit.ui.features.chat.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ai.assistance.operit.R

class VoiceCallService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var ownedCall: VoiceCallController? = null
    private var overlay: VoiceCallOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            VoiceCallRuntime.hangUp()
            stopSelf()
            return START_NOT_STICKY
        }
        val controller = VoiceCallRuntime.controller
        if (controller == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW_WINDOW) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                if (overlay == null) overlay = VoiceCallOverlay(this, controller)
                overlay?.show()
            }
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        ownedCall = controller
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_call_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getService(this, 301, Intent(this, VoiceCallService::class.java).setAction(ACTION_SHOW_WINDOW), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hangUp = PendingIntent.getService(this, 302, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.voice_call_title))
            .setContentText(if (android.provider.Settings.canDrawOverlays(this)) "点击打开通话小窗" else "请在通话界面开启悬浮窗权限")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.voice_call_hang_up), hangUp)
            .build()
        try { ServiceCompat.startForeground(this, 7301, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                (if (intent?.action == ACTION_CAMERA_ON || controller.cameraEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) else 0)
        } catch (error: SecurityException) {
            if (intent?.action != ACTION_CAMERA_ON) throw error
            com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Camera foreground access rejected", error)
            controller.reportCameraError("请在 Operit 前台开启摄像头，再切到小窗使用。")
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Operit:VoiceCall")
                .apply { acquire() }
        }
        controller.start()
        if (intent?.action == ACTION_CAMERA_ON) {
            try { controller.enableCamera(intent.getBooleanExtra("video", false), intent.getIntExtra("interval", 10)) }
            catch (error: Exception) {
                com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Could not enable camera", error)
                controller.reportCameraError(error.message.orEmpty())
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlay?.destroy()
        overlay = null
        if (VoiceCallRuntime.controller === ownedCall && VoiceCallRuntime.isActive) VoiceCallRuntime.serviceStopped()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice_calls"
        private const val ACTION_HANG_UP = "com.ai.assistance.operit.voice.HANG_UP"
        const val ACTION_SHOW_WINDOW = "com.ai.assistance.operit.voice.SHOW_WINDOW"
        const val ACTION_CAMERA_ON = "com.ai.assistance.operit.voice.CAMERA_ON"
    }
}
