package com.ai.assistance.operit.core.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Service-owned, one-shot AI schedules; use the normal permission/tool pipeline. */
object CompanionRuntime {
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var decision: Job? = null
    private data class OwnedTurn(val core: ChatServiceCore, val chatId: String)
    private val owned = AtomicReference<OwnedTurn?>()
    private val toolCount = AtomicInteger()
    private val events = Channel<String>(Channel.CONFLATED)
    @Volatile private var running = false

    fun noteConversation(context: Context, chatId: String, roleId: String?) {
        val store = CompanionStore(context)
        if (store.read("conversation:$chatId").optString("role_id") != roleId.orEmpty())
            store.update("conversation:$chatId") { it.put("role_id", roleId.orEmpty()) }
    }

    fun noteUserInteraction() {
        lastInteraction = SystemClock.elapsedRealtime()
        generation.incrementAndGet()
        // Cancel our own request before the user's next request starts, never in a late finally.
        owned.getAndSet(null)?.let { it.core.cancelMessage(it.chatId) }
        decision?.cancel()
    }

    fun beforeTool(context: Context, chatId: String?, tool: String) {
        if (chatId == null || owned.get()?.chatId != chatId) return
        check(tool == "companion_schedule" || toolCount.incrementAndGet() <= 12) { "本次主动活动已达到12次工具调用，请安排下次唤醒后结束。" }
        CompanionStore(context).log(chatId, "工具", tool)
    }

    fun afterTool(context: Context, chatId: String?, tool: String, success: Boolean) {
        if (chatId != null && owned.get()?.chatId == chatId)
            CompanionStore(context).log(chatId, "工具结果", tool + if (success) "：成功" else "：失败")
    }

    fun isWake(chatId: String) = owned.get()?.chatId == chatId

    fun publishEvent(context: Context, source: String, description: String) {
        if (!CompanionStore(context).proactiveEnabled || !running) return
        require(source.isNotBlank() && source.length <= 80 && description.length <= 2000)
        events.trySend("$source：$description")
    }

    suspend fun run(context: Context): Unit = withContext(Dispatchers.Main.immediate) {
        if (running) return@withContext
        running = true
        val app = context.applicationContext
        val store = CompanionStore(app)
        var pendingEvent = ""
        var lastSkip = 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT)
                    publishEvent(app, "设备", "设备已解锁；不是用户新消息。")
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("proactive_enabled", "target_chat")) {
                noteUserInteraction()
                pendingEvent = ""
                events.trySend("主动联系设置已改变")
            }
        }
        var registered = false
        try {
            store.preferences.registerOnSharedPreferenceChangeListener(listener)
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            while (isActive) {
                val event = withTimeoutOrNull(10_000) { events.receive() }
                if (event != null) pendingEvent = event
                if (!store.proactiveEnabled) continue
                val target = store.preferences.getString("target_chat", null) ?: continue
                var schedule = store.read("schedule:$target")
                if (!schedule.has("at") && !schedule.optBoolean("paused")) {
                    schedule = store.update("schedule:$target") {
                        it.put("at", System.currentTimeMillis() + store.proactiveMinutes * 60_000L)
                            .put("purpose", "首次唤醒，了解情况并自行安排下次活动。").put("owner", "initial")
                    }
                    store.log(target, "安排", "首次唤醒已安排；此后由 AI 决定时间")
                }
                if (schedule.optBoolean("paused") || schedule.optLong("at") > System.currentTimeMillis()) continue
                val holder = ChatRuntimeHolder.getInstance(app)
                if (holder.activeConversationCount.value > 0 || VoiceCallRuntime.controller != null ||
                    VoiceCallIncoming.pending != null || SystemClock.elapsedRealtime() - lastInteraction < 60_000) {
                    if (SystemClock.elapsedRealtime() - lastSkip > 60_000) {
                        store.log(target, "延后", "正在对话、通话或刚收到用户消息；不插入重复回复，原计划等待空闲")
                        lastSkip = SystemClock.elapsedRealtime()
                    }
                    continue
                }
                val epoch = generation.get()
                val observation = schedule.optString("purpose") + "\n" + pendingEvent
                pendingEvent = ""
                store.update("schedule:$target") { it.put("paused", true).put("started", System.currentTimeMillis()) }
                decision = launch {
                    try {
                        withTimeout(300_000) { evaluate(app, target, observation, epoch) }
                    } catch (timeout: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (generation.get() == epoch) owned.getAndSet(null)?.let { it.core.cancelMessage(it.chatId) }
                        store.log(target, "超时", "主动活动超过5分钟，已停止；查看下次安排")
                    } catch (cancelled: CancellationException) {
                        store.log(target, "取消", "用户开始对话、关闭功能或服务停止")
                        throw cancelled
                    } catch (error: Exception) {
                        store.log(target, "失败", "主动活动未完成：" + error.javaClass.simpleName)
                    } finally {
                        if (generation.get() == epoch) owned.getAndSet(null)?.let { it.core.cancelMessage(it.chatId) }
                        if (store.read("schedule:$target").optBoolean("paused"))
                            store.log(target, "暂停", "本次未安排下一次；可在聊天中让 AI 安排，或点击一分钟后唤醒")
                    }
                }
                decision?.join()
                decision = null
            }
        } finally {
            noteUserInteraction()
            withContext(NonCancellable) {
                decision?.cancelAndJoin()
                running = false
                if (registered) app.unregisterReceiver(receiver)
                store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
                while (events.tryReceive().isSuccess) { }
            }
        }
    }

    private suspend fun evaluate(context: Context, chatId: String, event: String, epoch: Long) = coroutineScope {
        val store = CompanionStore(context)
        val core = ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
        val chat = core.chatHistories.value.firstOrNull { it.id == chatId } ?: error("目标会话不存在")
        check(chat.characterGroupId.isNullOrBlank()) { "主动活动只支持单角色会话" }
        val roleId = store.read("conversation:$chatId").optString("role_id").takeIf { it.isNotBlank() }
            ?: error("请先在目标会话与角色聊天")
        val card = CharacterCardManager.getInstance(context).getCharacterCard(roleId)
        val history = core.getChatHistoryDelegate().getRuntimeChatHistory(chatId).map { it.copy() }
        val latest = history.lastOrNull()
        val choices = store.preferences.getStringSet("learning_choices", emptySet()).orEmpty()
        val activityNames = mapOf("mcp" to "已有MCP（包括hy）", "web" to "上网探索", "reading" to "阅读和复习资料", "organize" to "整理软件资料和待办")
        val activities = choices.mapNotNull { activityNames[it] }.joinToString("、")
        val instruction = """
            [软件主动唤醒，不是用户新消息]
            当前时间：${java.time.ZonedDateTime.now()}。
            此次计划与事件：$event
            最近一条消息时间：${latest?.timestamp}；发送者：${latest?.sender}。完整可用聊天历史已随本轮提供。
            你拥有正常聊天中已授权的工具，可以查时间、当前应用、屏幕或其他内容，但必须实际调用工具后才知道结果。
            先检查聊天是否还在等她回复。不要重发上次的话，不把沉默当成危险；可选择安静或有新内容时跟进。
            允许的主动学习活动：${activities.ifBlank { "未开启，暂不自行展开学习" }}。可自行选题、规划，用资料库与待办记录实际进展。未授权的操作仍需按正常权限执行。
            通话可使用正常的 request_voice_call 工具，不重复拨打未被接听的同一轮来电。
            本轮最多12次工具调用、5分钟。必须在结束前用 companion_schedule 安排下次醒来的时间和目的，或主动 cancel 暂停。
            先使用所需工具，最后若要发消息，用 <companion_message>这里仅写给她的新消息</companion_message>；若安静则只输出 <companion_quiet/>。
            学习过程和内部状态写入资料或工具，不把计划、工具日志和控制标记直接发送给用户。确实发生互动或完成学习时主动回传 XC，不能编造她的回应。
        """.trimIndent()
        val result = CompletableDeferred<String>()
        store.log(chatId, "唤醒", event)
        toolCount.set(0)
        val turn = OwnedTurn(core, chatId)
        owned.set(turn)
        val started = async(start = CoroutineStart.UNDISPATCHED) { core.activeStreamingChatIds.first { chatId in it } }
        try {
            core.sendUserMessage(roleCardIdOverride = roleId, chatIdOverride = chatId, messageTextOverride = instruction,
                turnOptions = ChatTurnOptions(proactiveWake = true, persistTurn = false, hideUserMessage = true,
                    notifyReply = false, onCompanionComplete = { result.complete(it) }))
            withTimeout(30_000) { started.await() }
            val failure = async(start = CoroutineStart.UNDISPATCHED) {
                core.inputProcessingStateByChatId.first { it[chatId] is InputProcessingState.Error }[chatId] as InputProcessingState.Error
            }
            val raw = try {
                kotlinx.coroutines.selects.select<String> {
                    result.onAwait { it }
                    failure.onAwait { error(it.message) }
                }
            } finally { failure.cancel() }
            core.activeStreamingChatIds.first { chatId !in it }
            owned.compareAndSet(turn, null)
            currentCoroutineContext().ensureActive()
            if (!store.proactiveEnabled || epoch != generation.get() || store.preferences.getString("target_chat", null) != chatId) return@coroutineScope
            val current = core.getChatHistoryDelegate().getRuntimeChatHistory(chatId)
            if (current.lastOrNull()?.timestamp != latest?.timestamp || current.lastOrNull()?.content != latest?.content) {
                store.log(chatId, "跳过", "聊天已更新，本次不插入旧回复")
                return@coroutineScope
            }
            val cleaned = ChatUtils.removeThinkingContent(raw)
                .replace(Regex("<tool(?:_result)?\\b[\\s\\S]*?</tool(?:_result)?>"), "")
            val messages = Regex("<companion_message>([\\s\\S]*?)</companion_message>").findAll(cleaned).toList()
            if (messages.isEmpty()) {
                check(cleaned.contains("<companion_quiet/>")) { "没有明确的主动消息或安静决定" }
                store.log(chatId, "安静", "本次未发送消息；使用工具${toolCount.get()}次")
                return@coroutineScope
            }
            check(messages.size == 1) { "主动消息格式不明确" }
            val text = messages.single().groupValues[1].trim()
            require(text.isNotBlank() && text.length <= 4000) { "主动消息为空或过长" }
            if (CompanionPolicy.duplicate(text, current.filter { it.sender == "ai" }.takeLast(5).map { it.content })) {
                store.log(chatId, "拦截重复", "内容与近期已发送消息重复，本次未发送")
                return@coroutineScope
            }
            core.getChatHistoryDelegate().addMessageToChat(ChatMessage(sender = "ai", roleName = card.name, content = text), chatId)
            AIForegroundService.notifyReplyCompleted(context, chatId, card.name, text, null,
                notifyReplyOverride = true, notifyWhileForeground = true)
            store.log(chatId, "主动消息", text.take(240))
        } finally { started.cancel() }
    }
}
