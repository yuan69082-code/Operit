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
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Owned by the existing Android foreground service; no wakeups after service destruction. */
object CompanionRuntime {
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var decision: Job? = null
    private val events = Channel<String>(Channel.CONFLATED)
    @Volatile private var running = false

    fun noteConversation(context: Context, chatId: String, roleId: String?) {
        CompanionStore(context).update("conversation:$chatId") {
            it.put("role_id", roleId.orEmpty())
        }
    }

    fun noteUserInteraction() {
        lastInteraction = SystemClock.elapsedRealtime()
        generation.incrementAndGet()
        decision?.cancel()
    }

    /** Local integration point for a future engine/device adapter. Source must describe real data. */
    fun publishEvent(context: Context, source: String, description: String) {
        if (!CompanionStore(context).proactiveEnabled || !running) return
        require(source.isNotBlank() && source.length <= 80 && description.length <= 2000)
        events.trySend(JSONObject().put("source", source).put("description", description).toString())
    }

    suspend fun run(context: Context): Unit = withContext(Dispatchers.Main.immediate) {
        if (running) return@withContext
        running = true
        val app = context.applicationContext
        val store = CompanionStore(app)
        var lastAttempt = SystemClock.elapsedRealtime()
        var pendingEvent: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    publishEvent(app, "android", "设备已解锁；这不表示用户发出了聊天请求。")
                }
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("proactive_enabled", "target_chat", "proactive_minutes")) {
                generation.incrementAndGet()
                decision?.cancel()
                lastAttempt = SystemClock.elapsedRealtime()
                pendingEvent = null
                while (events.tryReceive().isSuccess) { }
                if (store.proactiveEnabled) events.trySend("软件主动联系设置已改变。")
            }
        }
        var receiverRegistered = false
        try {
            store.preferences.registerOnSharedPreferenceChangeListener(listener)
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            while (isActive) {
                if (!store.proactiveEnabled) {
                    events.receive()
                    continue
                }
                // Runtime idle observation, independent of user-created scheduled workflows.
                val event = withTimeoutOrNull(10_000) { events.receive() }
                if (event != null) pendingEvent = event
                val now = SystemClock.elapsedRealtime()
                val interval = store.proactiveMinutes * 60_000L
                if (!store.proactiveEnabled || now - lastAttempt < interval || now - lastInteraction < 30_000) continue
                if (pendingEvent == null && now - lastInteraction < interval) continue
                val target = store.preferences.getString("target_chat", null) ?: continue
                val holder = ChatRuntimeHolder.getInstance(app)
                if (holder.activeConversationCount.value > 0 || VoiceCallRuntime.controller != null || VoiceCallIncoming.pending != null) continue
                lastAttempt = now
                val epoch = generation.get()
                val observation = pendingEvent ?: "用户一段时间未发送消息。可以决定保持安静，不要假定用户失落、危险或需要帮助。"
                pendingEvent = null
                decision = launch {
                    try {
                        withTimeout(120_000) {
                            evaluate(app, target, observation, epoch)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLogger.e("CompanionRuntime", "Proactive evaluation failed", error)
                        store.preferences.edit().putString("last_status", "主动判断未完成：" + error.javaClass.simpleName).apply()
                    }
                }
                decision?.join()
                decision = null
            }
        } finally {
            withContext(NonCancellable) {
                decision?.cancelAndJoin()
                decision = null
                running = false
                if (receiverRegistered) app.unregisterReceiver(receiver)
                store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
                while (events.tryReceive().isSuccess) { }
            }
        }
    }

    private suspend fun evaluate(context: Context, chatId: String, event: String, epoch: Long) {
        val store = CompanionStore(context)
        val core = ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
        val chat = core.chatHistories.value.firstOrNull { it.id == chatId } ?: return
        // Group turns need an explicit speaker planner; do not impersonate an arbitrary member.
        if (!chat.characterGroupId.isNullOrBlank()) return
        val known = store.read("conversation:$chatId")
        if (!known.has("role_id")) return
        val roleId = known.optString("role_id").takeIf { it.isNotBlank() }
        val manager = CharacterCardManager.getInstance(context)
        val card = roleId?.let { manager.getCharacterCard(it) }
        val persona = roleId?.let { manager.combinePrompts(it, promptFunctionType = PromptFunctionType.CHAT) }.orEmpty()
        val fullHistory = core.getChatHistoryDelegate().getRuntimeChatHistory(chatId)
        val history = fullHistory.takeLast(24)
        val revision = history.lastOrNull()?.timestamp
        val latestUserTurn = fullHistory.lastOrNull { it.sender == "user" && !it.content.startsWith("[语音通话]\n") }?.timestamp ?: 0L
        val historyText = JSONArray(history.map {
            JSONObject().put("speaker", it.roleName ?: it.sender).put("text", it.content.take(3000))
                .put("truncated", it.content.length > 3000)
        })
        val emotion = XcEmotionBridge.prompt(context, chatId, roleId, actualSend = true)
        val api = com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context)
        val packages = com.ai.assistance.operit.core.tools.packTool.PackageManager.getInstance(context,
            com.ai.assistance.operit.core.tools.AIToolHandler.getInstance(context))
        val access = com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver.getInstance(context)
            .resolve(roleCardId = roleId, packageManager = packages, globalToolVisibility = api.toolPromptVisibilityFlow.first())
        val canCall = api.enableToolsFlow.first() && access.isBuiltinToolAllowed("request_voice_call")
        val request = JSONObject().put("event", event).put("recent_history", historyText)
            .put("emotion_state", emotion)
            .put("may_request_call", canCall)
            .put("memory", store.memoryContext(chatId))
        if (store.capabilitiesEnabled) request.put("capabilities", CompanionContext.permissions(context))
        val system = persona + """
            
            你正在决定是否主动联系用户，这不是用户发言。根据历史和真实事件决定是否有值得主动说的话；没有则安静。
            这里提供最近聊天的文字节选，truncated 表示文本已截短。没有新的音频、画面或传感器数据，不要声称正在看或听用户。
            不要把无消息解释为危险，不要编造心率或身体数据，不因情绪词强迫用户回应。用户拒接后不要重复打。
            may_request_call 为 false 时不能选择 call。这轮无工具调用，只返回一个 JSON 对象：
            {"action":"quiet|message|call","text":"要发的自然语言消息或简短来电理由"}
            quiet 的 text 为空；message/call 的 text 不超过1000字。不要输出内部事件说明、字段名或代码围栏。
        """.trimIndent()
        val serviceKey = "companion-decision:$chatId"
        try {
            val output = EnhancedAIService.getChatInstance(context, serviceKey).callFunctionModel(
                FunctionType.CHAT,
                listOf(PromptTurn(kind = PromptTurnKind.SYSTEM, content = system),
                    PromptTurn(kind = PromptTurnKind.USER, content = request.toString())),
                chatModelConfigIdOverride = if (card?.chatModelBindingMode == CharacterCardChatModelBindingMode.FIXED_CONFIG) card.chatModelConfigId else null,
                chatModelIndexOverride = card?.chatModelIndex,
            )
            currentCoroutineContext().ensureActive()
            val parsed = JSONObject(ChatUtils.removeThinkingContent(output).trim())
            val action = parsed.getString("action")
            val text = parsed.getString("text").trim()
            require(action in setOf("quiet", "message", "call") && text.length <= 1000)
            require(action == "quiet" || text.isNotBlank())
            if (!store.proactiveEnabled || epoch != generation.get() || store.preferences.getString("target_chat", null) != chatId) return
            if (VoiceCallRuntime.controller != null || VoiceCallIncoming.pending != null ||
                ChatRuntimeHolder.getInstance(context).activeConversationCount.value > 0) return
            if (core.chatHistories.value.none { it.id == chatId }) return
            if (core.getChatHistoryDelegate().getRuntimeChatHistory(chatId).lastOrNull()?.timestamp != revision) return
            val name = card?.name ?: "Operit"
            when (action) {
                "message" -> {
                    core.getChatHistoryDelegate().addMessageToChat(ChatMessage(sender = "ai", roleName = name, content = text), chatId)
                    AIForegroundService.notifyReplyCompleted(context, chatId, name, text, null,
                        notifyReplyOverride = true, notifyWhileForeground = true)
                }
                "call" -> {
                    if (!canCall) return
                    // Tool permissions can change while the model is deciding.
                    val currentAccess = com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver.getInstance(context)
                        .resolve(roleCardId = roleId, packageManager = packages, globalToolVisibility = api.toolPromptVisibilityFlow.first())
                    if (!api.enableToolsFlow.first() || !currentAccess.isBuiltinToolAllowed("request_voice_call")) return
                    // Do not repeatedly ring after a rejection/timeout without a new user turn.
                    val previousCallerTurn = store.read("contact:$chatId").optLong("last_call_user_turn", Long.MIN_VALUE)
                    if (previousCallerTurn == latestUserTurn) return
                    VoiceCallIncoming.request(context, chatId, roleId, text, name)
                    store.update("contact:$chatId") { it.put("last_call_user_turn", latestUserTurn) }
                }
            }
            store.preferences.edit().putString("last_status", when (action) {
                "message" -> "已主动发出消息"
                "call" -> "已发起来电，等待接听"
                else -> "已判断，继续保持安静"
            }).apply()
        } finally {
            EnhancedAIService.releaseChatInstance(serviceKey)
        }
    }
}
