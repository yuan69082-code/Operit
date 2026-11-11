package com.ai.assistance.operit.core.companion

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivityNotesLeaseTest {
    private fun ref() = ActivityNotes.Ref("role", "lease-test-${UUID.randomUUID()}", "activity-id")

    @Test fun cancellationWhilePersistingReleasesTheOwnedLease() = runTest {
        val ref = ref()
        val write = CompletableDeferred<Unit>()
        val job = launch { ActivityNotes.acquire(ref) { write.await() } }
        runCurrent()
        assertSame(ref, ActivityNotes.current(ref.chat))

        job.cancelAndJoin()

        assertNull(ActivityNotes.current(ref.chat))
    }

    @Test fun cancellationAtPersistenceReturnCannotLeakALeaseBeforeTheCallerReceivesIt() = runTest {
        val ref = ref()
        val write = CompletableDeferred<Unit>()
        var returnedToCaller = false
        val job = launch {
            ActivityNotes.acquire(ref) { withContext(NonCancellable) { write.await() } }
            returnedToCaller = true
        }
        runCurrent()
        assertSame(ref, ActivityNotes.current(ref.chat))
        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)

        write.complete(Unit)
        job.join()

        assertFalse(returnedToCaller)
        assertNull(ActivityNotes.current(ref.chat))
    }

    @Test fun persistenceFailureReleasesTheLeaseAndPreservesTheOriginalError() = runTest {
        val ref = ref()
        val error = IllegalStateException("write failed")
        val failure = runCatching { ActivityNotes.acquire(ref) { throw error } }.exceptionOrNull()

        assertSame(error, failure)
        assertNull(ActivityNotes.current(ref.chat))
    }

    @Test fun aLateReleaseCannotRemoveANewLeaseWithTheSameLogicalId() = runTest {
        val old = ref()
        val replacement = old.copy()
        assertEquals(old, replacement)
        assertNotSame(old, replacement)
        ActivityNotes.acquire(old) { }
        ActivityNotes.release(old)
        ActivityNotes.acquire(replacement) { }
        try {
            ActivityNotes.release(old)
            assertSame(replacement, ActivityNotes.current(old.chat))
        } finally {
            ActivityNotes.release(replacement)
        }
        assertNull(ActivityNotes.current(old.chat))
    }

    @Test fun aConflictingLeaseNeverRunsItsPersistenceOrReleasesTheCurrentOwner() = runTest {
        val owner = ref()
        ActivityNotes.acquire(owner) { }
        try {
            var persisted = false
            val failure = runCatching {
                ActivityNotes.acquire(owner.copy(id = "another-activity")) { persisted = true }
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(persisted)
            assertSame(owner, ActivityNotes.current(owner.chat))
        } finally {
            ActivityNotes.release(owner)
        }
    }
}
