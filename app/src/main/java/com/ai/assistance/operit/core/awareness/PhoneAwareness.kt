package com.ai.assistance.operit.core.awareness

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Process
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.ai.assistance.operit.core.tools.system.ShellIdentity
import com.ai.assistance.operit.core.tools.system.shell.DebuggerShellExecutor
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.services.notification.OperitNotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object PhoneAwareness {
    fun usageAccess(context: Context): Boolean = (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    /** Human-facing setup only. Do not grant app permissions from an AI tool. */
    suspend fun grantUsageWithShizuku(context: Context) {
        val shell = DebuggerShellExecutor(context)
        shell.initialize()
        check(shell.isAvailable() && shell.hasPermission().granted) { "Shizuku 未运行或未授权，请在系统设置中开通使用情况访问。" }
        require(context.packageName.matches(Regex("[A-Za-z0-9_.]+")))
        val result = shell.executeCommand("appops set ${context.packageName} GET_USAGE_STATS allow", ShellIdentity.DEFAULT)
        check(result.success && usageAccess(context)) { "使用情况访问未开通：${result.stderr.take(200)}" }
    }

    fun appName(context: Context, app: String): String {
        if (app.isEmpty()) return ""
        return try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(app, 0)).toString() }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { app }
    }

    suspend fun authorize(context: Context, chat: String, role: String?) {
        val store = PhoneAwarenessStore(context)
        check(store.enabled && role != null && store.role == role) { "手机感知未开启，或当前助手不是指定的观察助手。" }
        val metadata = ChatHistoryManager.getInstance(context).getChatMetadata(chat) ?: error("会话不存在")
        check(metadata.characterGroupId.isNullOrBlank() && metadata.characterCardId == role) { "手机观察记录只提供给指定助手的私聊。" }
        check(com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context).getAllCharacterCards().any { it.id == role }) { "观察助手已不存在" }
    }

    suspend fun query(context: Context, chat: String, role: String?, action: String, app: String?, offset: Int): JSONObject = withContext(Dispatchers.IO) {
        authorize(context, chat, role)
        require(offset in 0..1500) { "offset 须为0–1500" }
        val store = PhoneAwarenessStore(context)
        val result = when (action) {
            "status" -> JSONObject().put("enabled", store.enabled).put("event_wake", store.wakeEnabled)
                .put("usage_access", usageAccess(context)).put("collector", store.data().optString("status"))
                .put("notification_reader_connected", OperitNotificationStore.connected && context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context))
                .put("accessibility_reader_connected", UIHierarchyManager.isBound.value)
                .put("screen_on", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
                .put("locked", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
                .put("last_events", JSONArray(store.rows().takeLast(3)))
                .put("content_allowlist", JSONArray(store.contentApps.toList()))
                .put("retention", "本机最近7天、最多1500条；服务停止或权限不足时会有缺口。")
            "events" -> {
                val rows = store.rows().filter { app == null || it.optString("package") == app }.asReversed()
                JSONObject().put("total", rows.size).put("events", JSONArray(rows.drop(offset).take(20)))
                    .put("next_offset", if (rows.size > offset + 20) offset + 20 else JSONObject.NULL)
            }
            "usage" -> {
                val intervals = store.rows().filter { it.optString("kind") == "app_leave" && (app == null || it.optString("package") == app) }
                    .groupBy { it.optString("package") }.map { (pkg, rows) ->
                        JSONObject().put("package", pkg).put("app_name", rows.last().optString("app_name"))
                            .put("observed_ms", rows.sumOf { it.optLong("duration_ms") }).put("visits", rows.size)
                    }.sortedByDescending { it.optLong("observed_ms") }
                JSONObject().put("usage", JSONArray(intervals.drop(offset).take(20))).put("total", intervals.size)
                    .put("scope", "仅日志内已结束的前台观察区间，不含当前未结束区间、服务离线时间；不能据此推断注意力。")
            }
            "app_content" -> {
                val pkg = requireNotNull(app) { "请提供 package" }
                check(pkg in store.contentApps) { "用户尚未授权读取这个应用的通知和已见页面缓存。" }
                val notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
                val notifications = if (notificationAccess && OperitNotificationStore.connected) OperitNotificationStore.snapshot(200, true)
                    .filter { it.packageName == pkg }.take(10).map {
                        JSONObject().put("source", "active_notification").put("posted_at", it.timestamp).put("text", it.text.take(2000))
                    } else emptyList()
                val cached = store.page(pkg)
                    ?.takeIf { System.currentTimeMillis() - it.optLong("observed_at") <= 7 * 86_400_000L }
                JSONObject().put("package", pkg).put("notifications_available", notificationAccess && OperitNotificationStore.connected)
                    .put("notifications", JSONArray(notifications)).put("previously_seen_page", cached ?: JSONObject.NULL)
                    .put("limitation", "查询不会打开应用。通知不是完整应用内容；缓存只是注明时间的旧页面。未开放的私有数据库不可读取，完整后台数据需要该应用的授权 API/MCP 或导出的内容接口。")
            }
            else -> error("action 须为 status/events/usage/app_content")
        }
        // Recheck after I/O in case the human revoked the observer/content permission mid-query.
        authorize(context, chat, role)
        if (action == "app_content") check(app in store.contentApps) { "内容授权已撤销" }
        result
    }

    suspend fun captureVisible(context: Context, expectedApp: String) {
        val store = PhoneAwarenessStore(context)
        if (!store.enabled || expectedApp !in store.contentApps) return
        val clearRevision = store.preferences.getLong("phone_clear_revision", 0)
        if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        val xml = UIHierarchyManager.getUIHierarchy(context)
        if (xml.isBlank()) return
        if (store.preferences.getLong("phone_clear_revision", 0) != clearRevision ||
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        if (UIHierarchyManager.extractWindowInfo(xml).first != expectedApp) return
        // Never cache another window under the previous app's name after a fast switch.
        val text = visibleText(xml, expectedApp)
        if (text.isNotEmpty()) store.cache(expectedApp, text, System.currentTimeMillis(), clearRevision)
    }

    internal fun visibleText(xml: String, expectedApp: String): String {
        require(!xml.contains("<!DOCTYPE", true)) { "不接受带文档实体的页面数据" }
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val lines = linkedSetOf<String>()
        var blockedDepth: Int? = null
        var size = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT && size < 6000) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                if (blockedDepth == null && parser.getAttributeValue(null, "password") == "true") blockedDepth = parser.depth
                if (blockedDepth == null && parser.getAttributeValue(null, "package") == expectedApp) {
                    for (key in listOf("text", "content-desc")) {
                        val value = parser.getAttributeValue(null, key)?.trim().orEmpty().take(1000)
                        if (value.isNotBlank() && lines.add(value)) size += value.length
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.depth == blockedDepth) blockedDepth = null
            parser.next()
        }
        return lines.joinToString("\n").take(6000)
    }
}
