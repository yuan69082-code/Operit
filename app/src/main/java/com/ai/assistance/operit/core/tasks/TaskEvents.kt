package com.ai.assistance.operit.core.tasks

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import org.json.JSONObject

object TaskEvents {
    data class Work(val board: TaskBoard, val row: JSONObject, val chat: String, val actor: String) {
        val id: String get() = row.getString("id")
        val event: String get() = row.getJSONObject("dispatch").getString("id")
    }
    suspend fun recoverPrivate(context: Context) {
        for (card in CharacterCardManager.getInstance(context).getAllCharacterCards()) {
            recover(TaskBoard(context, "role:${card.id}", false))
        }
    }
    fun recover(board: TaskBoard) {
        board.list().filter { it.optJSONObject("dispatch")?.optString("state") == "running" }.forEach {
            board.dispatchState(it.getString("id"), it.getJSONObject("dispatch").getString("id"),
                "interrupted", "上次运行被中断，请查看实际进展后再提交，避免重复执行")
        }
    }
    suspend fun nextPrivate(context: Context): Work? {
        val repo = ChatHistoryManager.getInstance(context)
        val chats = repo.getAllChatMetadata()
        for (card in CharacterCardManager.getInstance(context).getAllCharacterCards()) {
            val board = TaskBoard(context, "role:${card.id}", false)
            val row = board.pending() ?: continue
            val chat = chats.filter { it.characterCardId == card.id && it.characterGroupId.isNullOrBlank() }.maxByOrNull { it.updatedAt }
            if (chat == null) {
                board.dispatchState(row.getString("id"), row.getJSONObject("dispatch").getString("id"), "failed", "助手尚无私聊窗口，请先建立自己的私聊")
                continue
            }
            return Work(board, row, chat.id, card.id)
        }
        return null
    }
}
