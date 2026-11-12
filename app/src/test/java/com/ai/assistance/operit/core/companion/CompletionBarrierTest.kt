package com.ai.assistance.operit.core.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompletionBarrierTest {
    @Test fun aCancelledInactiveJobStillBlocksTheNextTurnUntilItsCleanupFinishes() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var enteredCleanup = false
        val oldTurn = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    enteredCleanup = true
                    cleanup.await()
                }
            }
        }
        runCurrent()
        oldTurn.cancel()
        runCurrent()
        assertTrue(enteredCleanup)
        assertFalse(oldTurn.isActive)
        assertFalse(oldTurn.isCompleted)
        val barrier = requireNotNull(completionBarrier(oldTurn))
        val nextTurn = async { barrier.join(); "may acquire activity" }
        runCurrent()
        assertFalse(nextTurn.isCompleted)

        cleanup.complete(Unit)
        oldTurn.join()

        assertEquals("may acquire activity", nextTurn.await())
        assertTrue(barrier.isCompleted)
    }

    @Test fun allDistinctOwnersMustFinishAndCompletedJobsNeedNoWait() = runTest {
        val decision = CompletableDeferred<Unit>()
        val send = CompletableDeferred<Unit>()
        val completed = CompletableDeferred(Unit)
        val barrier = requireNotNull(completionBarrier(null, decision, decision, send, completed))
        decision.complete(Unit)
        assertFalse(barrier.isCompleted)

        send.complete(Unit)
        barrier.join()

        assertTrue(barrier.isCompleted)
        assertNull(completionBarrier(null, decision, send, completed))
    }

    @Test fun aSecondInterruptionAlsoWaitsForThePreviousCleanupBarrier() = runTest {
        val previousCleanup = CompletableDeferred<Unit>()
        val firstBarrier = requireNotNull(completionBarrier(previousCleanup))
        val newerSend = CompletableDeferred<Unit>()
        val secondBarrier = requireNotNull(completionBarrier(firstBarrier, newerSend))
        newerSend.complete(Unit)
        assertFalse(secondBarrier.isCompleted)

        previousCleanup.complete(Unit)
        secondBarrier.join()

        assertTrue(firstBarrier.isCompleted)
        assertTrue(secondBarrier.isCompleted)
    }
}
