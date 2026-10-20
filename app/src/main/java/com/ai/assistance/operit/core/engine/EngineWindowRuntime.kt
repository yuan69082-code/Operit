package com.ai.assistance.operit.core.engine

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*

/** Only forwards platform records to the user's bridge. No Engine polling or model calls. */
object EngineWindowRuntime {
    private var running = false
    suspend fun run(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (running) return@withContext
        running = true
        val store = EngineWindow(context)
        try {
            while (isActive) {
                try {
                    if (store.hasPendingControls()) store.syncControls()
                    for (role in store.enabledRoles()) {
                        if (store.events(role).any { it.optString("status") == "local" }) store.sync(role)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLogger.e("EngineWindow", "Bridge sync deferred: ${failure.javaClass.simpleName}")
                }
                delay(30_000)
            }
        } finally { running = false }
    }
}
