package com.ai.assistance.operit.core.tasks

import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.core.companion.SubjectLife
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

object TaskEvents {
    private val pendingRecovery = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    data class Work(val board: TaskBoard, val row: JSONObject, val chat: String, val actor: String) {
        val id: String get() = row.getString("id")
        val event: String get() = row.getJSONObject("dispatch").getString("id")
    }
    suspend fun recoverPrivate(context: Context) {
        for (card in CharacterCardManager.getInstance(context).getAllCharacterCards()) {
            try {
                recover(TaskBoard(context, "role:${card.id}", false))
                pendingRecovery.remove(card.id)
                clearStorageFailure(context, card.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { pendingRecovery.add(card.id); storageFailure(context, card.id, failure) }
        }
    }
    fun recover(board: TaskBoard) {
        while (true) {
            val page = board.running()
            if (page.isEmpty()) break
            page.forEach {
                board.dispatchState(it.getString("id"), it.getJSONObject("dispatch").getString("id"),
                    "interrupted", "上次运行被中断，请查看实际进展后再提交，避免重复执行")
            }
        }
    }
    suspend fun nextPrivate(context: Context): Work? {
        com.ai.assistance.operit.core.companion.AssistantWindowRouter.initialize(context)
        for (card in CharacterCardManager.getInstance(context).getAllCharacterCards()) {
            val board = TaskBoard(context, "role:${card.id}", false)
            val row = try {
                if (card.id in pendingRecovery) {
                    recover(board)
                    pendingRecovery.remove(card.id)
                    clearStorageFailure(context, card.id)
                }
                board.pending()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { storageFailure(context, card.id, failure); null } ?: continue
            val chat = com.ai.assistance.operit.core.companion.AssistantWindowRouter.chat(context, card.id)
            if (chat == null) continue
            return Work(board, row, chat, card.id)
        }
        return null
    }
    // A malformed migration input is a visible per-assistant storage failure. It must
    // not escape the foreground service's launch or cancel other assistants' schedules.
    private const val STORAGE_FAILURE = "私人事项历史读取失败"
    private fun storageFailure(context: Context, role: String, failure: Exception) {
        if (SubjectLife.failure(context, role)?.startsWith(STORAGE_FAILURE) != true)
            SubjectLife.recordFailure(context, role, STORAGE_FAILURE, failure)
    }
    private fun clearStorageFailure(context: Context, role: String) {
        if (SubjectLife.failure(context, role)?.startsWith(STORAGE_FAILURE) == true) SubjectLife.clearFailure(context, role)
    }
}
