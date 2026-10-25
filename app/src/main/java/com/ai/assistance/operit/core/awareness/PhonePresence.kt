package com.ai.assistance.operit.core.awareness

/** Observed foreground intervals, not proof that a person was looking at the screen. */
class PhonePresence {
    data class Event(val kind: String, val at: Long, val app: String = "", val entered: Long? = null) {
        val durationMs: Long? get() = entered?.let { (at - it).coerceAtLeast(0) }
    }
    var foreground: String? = null
        private set
    private var entered: Long? = null

    fun resume(app: String, at: Long): List<Event> {
        if (app.isBlank()) return emptyList()
        val result = mutableListOf<Event>()
        if (foreground == app) return result
        // Activity pauses also happen for dialogs. Only the next foreground app or
        // an explicit screen/lock boundary ends an observed visit.
        result += close(at)
        foreground = app
        entered = at
        result += Event("app_enter", at, app)
        return result
    }

    fun close(at: Long): List<Event> {
        val result = foreground?.let { listOf(Event("app_leave", at, it, entered)) } ?: emptyList()
        foreground = null
        entered = null
        return result
    }

    companion object {
        fun shouldWake(kind: String, recent: Boolean, duringAiWork: Boolean): Boolean =
            recent && !duringAiWork && kind in setOf("unlock", "app_enter")
    }
}
