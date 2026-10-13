package com.ai.assistance.operit.core.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reserve before waiting so a new turn cannot overtake a queued compression. */
internal class SummaryTurnGate {
    private val reserved = ConcurrentHashMap.newKeySet<String>()
    private val startLock = Any()

    fun isReserved(chatId: String): Boolean = chatId in reserved

    fun startTurnIfAllowed(chatId: String, start: () -> Boolean): Boolean = synchronized(startLock) {
        if (isReserved(chatId)) false else start()
    }

    suspend fun <T> afterTurn(
        chatId: String,
        awaitTurn: suspend () -> Unit,
        summarize: suspend () -> T
    ): T {
        check(synchronized(startLock) { reserved.add(chatId) }) { "这个对话已在等待或执行压缩。" }
        try {
            awaitTurn()
            currentCoroutineContext().ensureActive()
            return summarize()
        } finally {
            reserved.remove(chatId)
        }
    }
}
