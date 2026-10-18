package com.ai.assistance.operit.core.companion

import java.util.concurrent.atomic.AtomicInteger

/** Expected wake limits are admission decisions, not application failures. */
internal class CompanionToolBudget(private val limit: Int = 12) {
    private val count = AtomicInteger()
    val used: Int get() = count.get()

    fun reset() { count.set(0) }

    fun admit(tool: String, action: String?): Boolean {
        // Exploration must use its own scheduling switch and interval policy.
        // It cannot be forced to use companion_schedule just because its work quota ran out.
        if (tool == "companion_schedule" || tool == "companion_explore" && action in setOf("schedule", "pause")) return true
        while (true) {
            val current = count.get()
            if (current >= limit) return false
            if (count.compareAndSet(current, current + 1)) return true
        }
    }
}
