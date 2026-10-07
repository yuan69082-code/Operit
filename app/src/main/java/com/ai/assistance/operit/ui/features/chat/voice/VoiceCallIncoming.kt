package com.ai.assistance.operit.ui.features.chat.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
    private const val NOTIFICATION = 304

    fun restore(context: Context) {
        if (pending != null) return
        val prefs = context.getSharedPreferences("voice_call_incoming", Context.MODE_PRIVATE)
        val stored = prefs.getString("request", null) ?: return
        val data = JSONObject(stored)
        val request = Request(data.getString("id"), data.getString("chat"),
            data.optString("role").takeIf { it.isNotBlank() }, data.getString("reason"), data.getLong("expires"), data.getString("caller"))
        if (request.expiresAt <= System.currentTimeMillis()) { clear(context); return }
        pending = request
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
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("voice_call_incoming", "AI 来电", NotificationManager.IMPORTANCE_HIGH))
            val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(context, 304, launcher, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val reject = PendingIntent.getBroadcast(context, 305,
                Intent(context, VoiceCallIncomingReceiver::class.java).putExtra("request_id", incoming.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION, NotificationCompat.Builder(context, "voice_call_incoming")
                .setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("${incoming.callerName} 来电")
                .setContentText(incoming.reason).setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setDeleteIntent(reject)
                .setTimeoutAfter(60_000).addAction(android.R.drawable.sym_call_incoming, "查看并接听", open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒接", reject).build())
            scheduleTimeout(context, incoming)
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
            if (pending?.id == request.id) clear(context)
        }
    }

    fun clear(context: Context) {
        timeout?.cancel()
        timeout = null
        pending = null
        context.getSharedPreferences("voice_call_incoming", Context.MODE_PRIVATE).edit().remove("request").apply()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}

class VoiceCallIncomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VoiceCallIncoming.restore(context)
        if (VoiceCallIncoming.pending?.id == intent.getStringExtra("request_id")) VoiceCallIncoming.clear(context)
    }
}
