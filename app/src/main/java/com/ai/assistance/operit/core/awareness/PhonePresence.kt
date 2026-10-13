package com.ai.assistance.operit.core.awareness

/** Observed foreground intervals, not proof that a person was looking at the screen. */
class PhonePresence {
    data class Event(val kind: String, val at: Long, val app: String = "", val entered: Long? = null) {
        val durationMs: Long? get() = entered?.let { (at - it).coerceAtLeast(0) }
    }
    var foreground: String? = null
        private set
    private var entered: Long? = null
    private var pausedAt: Long? = null

    fun resume(app: String, at: Long): List<Event> {
        if (app.isBlank()) return emptyList()
        val result = mutableListOf<Event>()
        val pause = pausedAt
        if (foreground == app && (pause == null || at - pause <= 1000)) {
            pausedAt = null
            return result
        }
        result += close(pause ?: at)
        foreground = app
        entered = at
        result += Event("app_enter", at, app)
        return result
    }

    fun pause(app: String, at: Long) {
        if (app == foreground && pausedAt == null) pausedAt = at
    }

    fun flush(now: Long): List<Event> = pausedAt?.takeIf { now - it > 1000 }?.let { close(it) } ?: emptyList()

    fun close(at: Long): List<Event> {
        val result = foreground?.let { listOf(Event("app_leave", at, it, entered)) } ?: emptyList()
        foreground = null
        entered = null
        pausedAt = null
        return result
    }

    companion object {
        fun shouldWake(kind: String, recent: Boolean, duringAiWork: Boolean): Boolean =
            recent && !duringAiWork && kind in setOf("unlock", "app_enter")
    }
}
