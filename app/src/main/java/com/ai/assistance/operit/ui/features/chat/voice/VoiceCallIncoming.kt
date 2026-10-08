package com.ai.assistance.operit.ui.features.chat.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject

/** Incoming calls request acceptance; they never acquire the microphone by themselves. */
object VoiceCallIncoming {
    data class Request(val id: String, val chatId: String, val roleCardId: String?, val reason: String, val expiresAt: Long, val callerName: String)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pending by mutableStateOf<Request?>(null)
        private set
    private var timeout: Job? = null
    private var ringtone: MediaPlayer? = null
    var ringtoneError by mutableStateOf("")
        private set
    private const val CHANNEL = "voice_call_incoming_v2"
    private const val NOTIFICATION = 304

    fun restore(context: Context) {
        if (pending != null) return
        val prefs = context.getSharedPreferences("voice_call_incoming", Context.MODE_PRIVATE)
        val stored = prefs.getString("request", null) ?: return
        val data = JSONObject(stored)
        val request = Request(data.getString("id"), data.getString("chat"),
            data.optString("role").takeIf { it.isNotBlank() }, data.getString("reason"), data.getLong("expires"), data.getString("caller"))
        pending = request
        if (request.expiresAt <= System.currentTimeMillis()) { finish(context, "ai", "无人接听，呼叫结束"); return }
        startRingtone(context, request)
        scheduleTimeout(context, request)
    }

    fun request(context: Context, chatId: String, roleCardId: String?, reason: String, callerName: String): String {
        restore(context)
        check(VoiceCallRuntime.controller == null && pending == null) { "已有通话或来电，请勿重复拨打" }
        val incoming = Request(java.util.UUID.randomUUID().toString(), chatId, roleCardId,
            reason.take(160), System.currentTimeMillis() + 60_000, callerName.take(80))
        val prefs = context.getSharedPreferences("voice_call_incoming", Context.MODE_PRIVATE)
        prefs.edit().putString("request", JSONObject().put("id", incoming.id).put("chat", chatId)
            .put("role", roleCardId.orEmpty()).put("reason", incoming.reason).put("expires", incoming.expiresAt).put("caller", incoming.callerName).toString()).apply()
        pending = incoming
        VoiceCallEvents.record(context, chatId, "ai", incoming.callerName, "拨打电话，等待接听")
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            // This channel is silent; the call-owned player is the only looping sound source.
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI 来电", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
            val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(context, 304, launcher, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val reject = PendingIntent.getBroadcast(context, 305,
                Intent(context, VoiceCallIncomingReceiver::class.java).putExtra("request_id", incoming.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("${incoming.callerName} 来电")
                .setContentText(incoming.reason).setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setDeleteIntent(reject)
                .setTimeoutAfter(60_000).addAction(android.R.drawable.sym_call_incoming, "查看并接听", open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒接", reject).build())
            scheduleTimeout(context, incoming)
            startRingtone(context, incoming)
            return if (manager.areNotificationsEnabled()) "来电请求已发出，等待用户接听；尚未接通，不要假装正在通话。" else
                "已显示应用内来电，系统通知权限未开启。等待用户接听；尚未接通。"
        } catch (error: Exception) {
            clear(context)
            throw error
        }
    }

    private fun scheduleTimeout(context: Context, request: Request) {
        timeout?.cancel()
        timeout = scope.launch {
            delay((request.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (pending?.id == request.id) finish(context, "ai", "无人接听，呼叫结束")
        }
    }

    fun clear(context: Context) {
        ringtone?.release()
        ringtone = null
        ringtoneError = ""
        timeout?.cancel()
        timeout = null
        pending = null
        context.getSharedPreferences("voice_call_incoming", Context.MODE_PRIVATE).edit().remove("request").apply()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    fun reject(context: Context) = finish(context, "user", "拒接来电，未接通")

    fun cancel(context: Context, chatId: String, roleCardId: String?, reason: String): String {
        restore(context)
        val request = checkNotNull(pending) { "当前没有等待接听的来电" }
        check(request.chatId == chatId && request.roleCardId == roleCardId) { "只能撤回本会话角色发起的来电" }
        finish(context, "ai", "取消呼叫，未接通" + if (reason.isBlank()) "" else "\n${reason.take(160)}")
        return "呼叫已撤回，来电铃声已停止，尚未接通。"
    }

    private fun finish(context: Context, sender: String, text: String) {
        val request = pending ?: return
        clear(context)
        VoiceCallEvents.record(context, request.chatId, sender,
            if (sender == "ai") request.callerName else context.getString(com.ai.assistance.operit.R.string.message_role_user), text)
    }

    private fun startRingtone(context: Context, request: Request) {
        ringtone?.release()
        ringtone = null
        ringtoneError = ""
        // Respect silent/vibrate mode rather than forcing audible playback.
        if (context.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val player = MediaPlayer()
        ringtone = player
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setDataSource(context, com.ai.assistance.operit.data.preferences.VoiceCallRingtonePreferences(context).playbackUri)
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.setOnPreparedListener {
                if (pending?.id == request.id && ringtone === it) {
                    try {
                        it.isLooping = true
                        it.start()
                    } catch (error: Exception) {
                        com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Could not start prepared ringtone", error)
                        it.release()
                        ringtone = null
                        ringtoneError = context.getString(com.ai.assistance.operit.R.string.voice_call_ringtone_play_error)
                    }
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Ringtone playback failed: $what/$extra")
                if (ringtone === failed) {
                    ringtone = null
                    ringtoneError = context.getString(com.ai.assistance.operit.R.string.voice_call_ringtone_play_error)
                }
                failed.release()
                true
            }
            player.prepareAsync()
        } catch (error: Exception) {
            com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Could not play call ringtone", error)
            player.release()
            ringtone = null
            ringtoneError = context.getString(com.ai.assistance.operit.R.string.voice_call_ringtone_play_error)
        }
    }
}

class VoiceCallIncomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VoiceCallIncoming.restore(context)
        if (VoiceCallIncoming.pending?.id == intent.getStringExtra("request_id")) VoiceCallIncoming.reject(context)
    }
}
