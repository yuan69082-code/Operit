package com.ai.assistance.operit.core.companion

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** Completes only after cancelled work has also finished its suspending cleanup. */
internal fun completionBarrier(vararg jobs: Job?): Job? {
    val pending = jobs.filterNotNull().distinct().filterNot { it.isCompleted }
    if (pending.isEmpty()) return null
    val barrier = CompletableDeferred<Unit>()
    val remaining = AtomicInteger(pending.size)
    pending.forEach { job ->
        job.invokeOnCompletion {
            if (remaining.decrementAndGet() == 0) barrier.complete(Unit)
        }
    }
    return barrier
}
