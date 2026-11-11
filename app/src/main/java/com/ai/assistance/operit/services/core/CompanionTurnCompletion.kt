package com.ai.assistance.operit.services.core

import java.util.concurrent.atomic.AtomicBoolean

/** A wake waiter must receive exactly one actual outcome, including cancellation before launch. */
internal class CompanionTurnCompletion(
    private val onComplete: ((String) -> Unit)?,
    private val onFailure: ((Throwable) -> Unit)?
) {
    private val delivered = AtomicBoolean()

    fun success(content: String) {
        if (delivered.compareAndSet(false, true)) onComplete?.invoke(content)
    }

    fun failure(cause: Throwable) {
        if (delivered.compareAndSet(false, true)) onFailure?.invoke(cause)
    }
}
