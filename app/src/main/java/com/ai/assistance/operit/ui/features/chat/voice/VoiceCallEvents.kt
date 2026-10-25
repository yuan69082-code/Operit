package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.VoiceCallUserOutcome
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.core.companion.CompanionRuntime
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Call outcomes belong to their original conversation, including notification-bar actions. */
object VoiceCallEvents {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A real text turn; its lifetime must not depend on an open chat screen. */
    fun sendUserOutcome(context: Context, chatId: String, roleCardId: String?, outcome: VoiceCallUserOutcome): Job {
        val app = context.applicationContext
        val core = ChatRuntimeHolder.getInstance(app).getCore(ChatRuntimeSlot.MAIN)
        return scope.launch {
            try {
                // An unanswered timer is not a user interaction and must not cancel unrelated work.
                val wakeCancellation = if (outcome == VoiceCallUserOutcome.UNANSWERED) null
                    else CompanionRuntime.noteUserInteraction()
                // Let the caller release ringtone/call resources before starting a text reply.
                yield()
                withTimeout(180_000) {
                    wakeCancellation?.join()
                    val chat = checkNotNull(ChatHistoryManager.getInstance(app).getChatMetadata(chatId)) {
                        "原通话会话已不存在"
                    }
                    val recipient = roleCardId?.takeIf { it.isNotBlank() } ?: chat.characterCardId
                    check(!recipient.isNullOrBlank()) { "原通话未绑定助手" }
                    if (chat.characterGroupId.isNullOrBlank()) {
                        check(chat.characterCardId == recipient) { "原通话会话的所属助手已改变" }
                    }
                    // AI may still be finishing the tool turn that initiated the incoming call.
                    // Do not silently drop rejection as 'busy', or cancel unrelated user work.
                    core.activeStreamingChatIds.first { chatId !in it }
                    core.sendUserMessage(
                        roleCardIdOverride = recipient,
                        chatIdOverride = chatId,
                        messageTextOverride = outcome.message,
                        turnOptions = ChatTurnOptions(voiceCallOutcome = outcome),
                    )
                }
            } catch (error: TimeoutCancellationException) {
                AppLogger.e("VoiceCall", "Call outcome delivery waited too long: $outcome", error)
                core.getUiStateDelegate().showToast("通话结果通知未发送：原会话仍在处理，请稍后在该会话告知助手")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e("VoiceCall", "Could not send user call outcome: $outcome", error)
                core.getUiStateDelegate().showToast("通话结果通知未发送：${error.message.orEmpty()}")
            }
        }
    }

    /** AI decisions and passive lifecycle events are recorded without another user turn. */
    fun record(context: Context, chatId: String, sender: String, roleName: String, text: String): Job {
        val message = ChatMessage(sender = sender, roleName = roleName, content = "[语音通话]\n$text")
        return scope.launch {
            try {
                ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN)
                    .getChatHistoryDelegate().addMessageToChat(message, chatId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e("VoiceCall", "Could not save call event", error)
            }
        }
    }
}
