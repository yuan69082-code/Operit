package com.ai.assistance.operit.core.awareness

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.core.companion.CompanionRuntime
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Owned by the existing foreground service. Metadata is local; only selected events wake AI. */
object PhoneAwarenessRuntime {
    @Volatile private var running = false
    private val toolsRunning = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastToolAt = -10_000L
    fun toolStarted() { toolsRunning.incrementAndGet(); lastToolAt = android.os.SystemClock.elapsedRealtime() }
    fun toolFinished() { lastToolAt = android.os.SystemClock.elapsedRealtime(); toolsRunning.decrementAndGet() }
    fun duringToolActivity() = toolsRunning.get() > 0 || android.os.SystemClock.elapsedRealtime() - lastToolAt < 5000
    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        val app = context.applicationContext
        val store = PhoneAwarenessStore(app)
        val signals = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val usage = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var presence = PhonePresence()
        var cursor = System.currentTimeMillis()
        var active = false
        var usageGranted = false
        var locked = keyguard.isKeyguardLocked
        var lastCapture = 0L
        var lastPrune = 0L
        var captureJob: Job? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (store.enabled) intent.action?.let { signals.trySend(it to System.currentTimeMillis()) }
            }
        }
        var registered = false
        val homePackage = runCatching {
            app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        fun record(event: PhonePresence.Event) {
            val toolActivity = duringToolActivity()
            val aiBusy = toolActivity || CompanionRuntime.hasActiveWork() || ChatRuntimeHolder.getInstance(app).activeConversationCount.value > 0
            // Generation alone is not evidence of self-induced UI changes: retain those events
            // and let the dispatcher defer them until the existing conversation is idle.
            // 回到桌面、切回本软件不算她去做了别的事：只记不叫
            val passThrough = event.kind == "app_enter" && (event.app == app.packageName || event.app == homePackage)
            val wake = !passThrough && PhonePresence.shouldWake(event.kind, System.currentTimeMillis() - event.at in 0..15_000, toolActivity)
            // 只有亮屏解锁立刻叫；切应用攒一会儿打包，不再一切就叫
            val isUrgent = event.kind == "unlock"
            store.record(event, PhoneAwareness.appName(app, event.app), wake, aiBusy, urgent = isUrgent)
            if (wake && store.wakeEnabled && event.app !in store.excluded) CompanionRuntime.signalPhoneEvent()
        }
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (!store.enabled) {
                        if (active) {
                            captureJob?.cancelAndJoin(); presence = PhonePresence(); store.discardPending()
                            store.status("手机感知已关闭")
                        }
                        active = false
                        while (signals.tryReceive().isSuccess) { }
                        delay(2000)
                        continue
                    }
                    if (!active) {
                        cursor = now; locked = keyguard.isKeyguardLocked; usageGranted = false
                        store.recover(); store.prune()
                        record(PhonePresence.Event("collection_started", now))
                        // The already-visible app has no known entry time. Only subsequent events create intervals.
                        active = true
                    }
                    val incoming = mutableListOf<Pair<String, Long>>()
                    while (true) incoming.add(signals.tryReceive().getOrNull() ?: break)
                    val hasUsage = PhoneAwareness.usageAccess(app)
                    if (hasUsage != usageGranted || now < cursor) {
                        presence.close(now).forEach { record(it) }
                        cursor = now
                        usageGranted = hasUsage
                        record(PhonePresence.Event(if (hasUsage) "usage_access_started" else "usage_access_unavailable", now))
                    }
                    val observed = mutableListOf<Triple<Long, String, String>>()
                    incoming.forEach { (kind, at) -> observed += Triple(at, kind, "") }
                    var usageError: String? = null
                    if (hasUsage) {
                        try {
                            val events = usage.queryEvents(cursor, now)
                            check(events != null) { "使用记录暂不可用，请先解锁设备" }
                            val item = UsageEvents.Event()
                            while (events.hasNextEvent()) {
                                events.getNextEvent(item)
                                if (item.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || item.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND)
                                    observed += Triple(item.timeStamp, if (item.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) "resume" else "pause", item.packageName.orEmpty())
                            }
                        } catch (failure: Exception) {
                            AppLogger.e("PhoneAwareness", "应用使用事件不可用", failure)
                            usageError = failure.message.orEmpty().take(180)
                            presence = PhonePresence()
                            // Display events are a separate source; a failed usage query must not discard them.
                            observed.removeAll { it.second == "resume" || it.second == "pause" }
                        }
                    }
                    cursor = now
                    observed.sortedBy { it.first }.forEach { (at, kind, pkg) ->
                        when (kind) {
                            "resume" -> presence.resume(pkg, at).forEach { record(it) }
                            "pause" -> presence.pause(pkg, at)
                            Intent.ACTION_SCREEN_OFF -> { presence.close(at).forEach { record(it) }; record(PhonePresence.Event("screen_off", at)) }
                            Intent.ACTION_SCREEN_ON -> record(PhonePresence.Event("screen_on", at))
                            Intent.ACTION_USER_PRESENT -> { locked = false; record(PhonePresence.Event("unlock", at)) }
                        }
                    }
                    presence.flush(now).forEach { record(it) }
                    if (keyguard.isKeyguardLocked != locked) {
                        locked = keyguard.isKeyguardLocked
                        record(PhonePresence.Event(if (locked) "lock" else "unlock", now))
                    }
                    store.status(when {
                        usageError != null -> "亮灭屏仍在记录；应用记录中断：$usageError"
                        hasUsage -> "正在记录亮灭屏、锁定和应用进出；仅在后台服务运行期间有效"
                        else -> "正在记录亮灭屏和锁定；请开通使用情况访问以记录应用进出"
                    })
                    val foreground = presence.foreground
                    if (foreground != null && foreground in store.contentApps && power.isInteractive && !locked &&
                        now - lastCapture >= 15_000 && captureJob?.isActive != true) {
                        lastCapture = now
                        captureJob = launch {
                            try { PhoneAwareness.captureVisible(app, foreground) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { AppLogger.e("PhoneAwareness", "页面缓存未完成", failure) }
                        }
                    }
                    if (now - lastPrune >= 60_000) { store.prune(); lastPrune = now }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLogger.e("PhoneAwareness", "手机观察未完成", failure)
                    store.status("观察中断：${failure.message.orEmpty().take(180)}")
                    // A service/query gap is not a measured visit. Do not fabricate its duration.
                    presence = PhonePresence(); cursor = System.currentTimeMillis()
                }
                delay(2000)
            }
        } finally {
            withContext(NonCancellable) {
                captureJob?.cancelAndJoin()
                store.status("观察服务已停止；停止期间的状态未采集")
                if (registered) app.unregisterReceiver(receiver)
                signals.close(); running = false
            }
        }
    }
}
