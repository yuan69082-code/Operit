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
        val manager = getSystemService(NotificationManager::class.java)
        ownedCall = controller
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_call_title), NotificationManager.IMPORTANCE_LOW))
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 301, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hangUp = PendingIntent.getService(this, 302, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.voice_call_title))
            .setContentText(getString(R.string.voice_call_background))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.voice_call_hang_up), hangUp)
            .build()
        ServiceCompat.startForeground(this, 7301, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Operit:VoiceCall")
                .apply { acquire() }
        }
        controller.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (VoiceCallRuntime.controller === ownedCall && VoiceCallRuntime.isActive) VoiceCallRuntime.hangUp()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice_calls"
        private const val ACTION_HANG_UP = "com.ai.assistance.operit.voice.HANG_UP"
    }
}
