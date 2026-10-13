package com.ai.assistance.operit.core.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Reserve before waiting so a new turn cannot overtake a queued compression. */
internal class SummaryTurnGate {
    private val reserved = MutableStateFlow(emptySet<String>())
    private val startLock = Any()

    fun isReserved(chatId: String): Boolean = chatId in reserved.value

    suspend fun awaitAvailable(chatId: String) {
        reserved.first { chatId !in it }
    }

    fun startTurnIfAllowed(chatId: String, start: () -> Boolean): Boolean = synchronized(startLock) {
        if (isReserved(chatId)) false else start()
    }

    suspend fun <T> afterTurn(
        chatId: String,
        awaitTurn: suspend () -> Unit,
        summarize: suspend () -> T
    ): T {
        synchronized(startLock) {
            check(!isReserved(chatId)) { "这个对话已在等待或执行压缩。" }
            reserved.value = reserved.value + chatId
        }
        try {
            awaitTurn()
            currentCoroutineContext().ensureActive()
            return summarize()
        } finally {
            synchronized(startLock) { reserved.value = reserved.value - chatId }
        }
    }
}
