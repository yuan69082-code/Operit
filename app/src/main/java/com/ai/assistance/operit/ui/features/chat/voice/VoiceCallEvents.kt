package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*

/** Local call events enter the bound conversation without generating another AI reply. */
object VoiceCallEvents {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
