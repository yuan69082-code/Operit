package com.ai.assistance.operit.core.tasks

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
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
        com.ai.assistance.operit.core.companion.AssistantWindowRouter.initialize(context)
        for (card in CharacterCardManager.getInstance(context).getAllCharacterCards()) {
            val board = TaskBoard(context, "role:${card.id}", false)
            val row = board.pending() ?: continue
            val chat = com.ai.assistance.operit.core.companion.AssistantWindowRouter.chat(context, card.id)
            if (chat == null) continue
            return Work(board, row, chat, card.id)
        }
        return null
    }
}
