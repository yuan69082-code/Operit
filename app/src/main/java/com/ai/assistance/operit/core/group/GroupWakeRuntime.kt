package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*

/** Owned by the existing foreground service; due work remains persisted across process death. */
object GroupWakeRuntime {
    private var running = false
    suspend fun run(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (running) return@withContext
        running = true
        val store = GroupStore(context)
        try {
            store.ids().forEach { chat ->
                if (store.read(chat).optString("status") == "running") {
                    store.update(chat) { it.put("status", "paused").remove("speaker") }
                    store.log(chat, "恢复", "上次运行被中断，已保留进度，点击继续")
                }
            }
            while (isActive) {
                delay(10_000)
                try {
                    val holder = ChatRuntimeHolder.getInstance(context)
                    val core = holder.getCore(ChatRuntimeSlot.MAIN)
                    val coordinator = core.getMessageCoordinationDelegate()
                    for (chatId in store.ids()) {
                        val state = store.read(chatId)
                        if (!state.optBoolean("proactive")) continue
                        val wakes = state.optJSONObject("wakes") ?: continue
                        val due = wakes.keys().asSequence().filter {
                            val wake = wakes.getJSONObject(it)
                            !wake.optBoolean("paused") && wake.optLong("at") <= System.currentTimeMillis()
                        }.toList()
                        if (due.isEmpty()) continue
                        if (holder.activeConversationCount.value > 0 || VoiceCallRuntime.controller != null ||
                            store.ids().any { coordinator.isGroupRunning(it) }) {
                            if (System.currentTimeMillis() - state.optLong("last_deferred") > 60_000) {
                                store.log(chatId, "延后", "正在对话或通话，等待空闲，避免重复回复")
                                store.update(chatId) { it.put("last_deferred", System.currentTimeMillis()) }
                            }
                            continue
                        }
                        val groupId = ChatHistoryManager.getInstance(context).getChatMetadata(chatId)?.characterGroupId
                        val members = groupId?.let { CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }?.members.orEmpty()
                        val actor = due.first()
                        val purpose = wakes.getJSONObject(actor).optString("purpose")
                        store.cancelWake(chatId, actor)
                        if (members.none { it.characterCardId == actor }) {
                            store.log(chatId, "取消", "助手已离群，取消其主动活动", actor)
                            continue
                        }
                        coordinator.startGroupDiscussion(chatId, actor,
                            "主动唤醒：$purpose。先看最新群消息，不重复前次内容，可安静。结束前用 group_chat schedule 安排下次或 cancel_schedule 暂停。")
                        break
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppLogger.e("GroupWake", "Group wake failed", failure) }
            }
        } finally { running = false }
    }
}
