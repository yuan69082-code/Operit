package com.ai.assistance.operit.core.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.ai.assistance.operit.BuildConfig
import org.json.JSONObject

object CompanionContext {
    fun permissions(context: Context): JSONObject = JSONObject()
        .put("microphone_permission", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        .put("notifications_enabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
        .put("android_api", Build.VERSION.SDK_INT)
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("heart_rate_source", "not_connected")

    suspend fun compose(context: Context, chatId: String, roleId: String?, capabilities: JSONObject, recordSnapshot: Boolean): String {
        val store = CompanionStore(context)
        val facts = if (store.capabilitiesEnabled) permissions(context)
            .put("emotion_continuity", store.emotionEnabled)
            .put("proactive_contact", store.proactiveEnabled)
            .put("call_silence_callback", store.silenceEnabled)
            .put("model_and_tools", capabilities) else null
        val old = if (facts != null) store.read("capabilities:$chatId").optJSONObject("snapshot") else null
        val changed = old != null && facts != null && old.toString() != facts.toString()
        // Window estimation also composes prompts; it must not acknowledge a change before sending.
        if (recordSnapshot) {
            if (facts != null) store.update("capabilities:$chatId") { it.put("snapshot", facts) }
            CompanionRuntime.noteConversation(context, chatId, roleId)
        }
        val xc = XcEmotionBridge.prompt(context, chatId, roleId, actualSend = recordSnapshot)
        return buildString {
            append("\n<operit_state>\n")
            append("\n[软件状态，仅作为内部上下文，不复述或朗读字段名]\n")
            if (facts != null && store.capabilitiesEnabled) {
                append(if (changed) "能力状态与上次请求不同，请以本次快照为准。\n" else "本次能力快照：\n")
                append(facts.toString())
                append("\n能力列出不表示操作已执行；权限、网络和服务仍可能限制实际调用。未接入的传感器数据不可推测。\n")
            }
            append(xc)
            append("资料可用 companion_library 自行分类、保存、检索；skill 是能力笔记，不会自动安装或执行代码。\n")
            append("上下文压缩前可用 companion_memory pin 保存必要原文，draft 提供摘要草稿；过期内容用 unpin 撤销。\n")
            append(store.memoryContext(chatId))
            append("\n</operit_state>\n")
        }
    }
}
