package com.ai.assistance.operit.api.chat

import com.ai.assistance.operit.core.companion.InternalStateReportStream
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.TextStreamRevisionTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class NativeResponseRevisionBarrierTest {
    @Test fun lateSavepointAndFinalRollbackAreAppliedBeforeTextAndFinalReport() = runBlocking {
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val barrier = NativeResponseRevisionBarrier(events)
        val parser = InternalStateReportStream()
        val text = TextStreamRevisionTracker()
        val mutex = Mutex()
        val allowSavepoint = CompletableDeferred<Unit>()
        val allowRollback = CompletableDeferred<Unit>()
        val forwarded = mutableListOf<TextStreamEvent>()
        val savepoint = TextStreamEvent(TextStreamEventType.SAVEPOINT, "attempt")
        val rollback = TextStreamEvent(TextStreamEventType.ROLLBACK, "attempt")
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                events.collect { event ->
                    when (event.eventType) {
                        TextStreamEventType.SAVEPOINT -> allowSavepoint.await()
                        TextStreamEventType.ROLLBACK -> allowRollback.await()
                    }
                    mutex.withLock {
                        when (event.eventType) {
                            TextStreamEventType.SAVEPOINT -> {
                                parser.savepoint(event.id)
                                text.savepoint(event.id)
                            }
                            TextStreamEventType.ROLLBACK -> {
                                parser.rollback(event.id)
                                text.rollback(event.id)
                            }
                        }
                        forwarded.add(event)
                        barrier.acknowledge(event)
                    }
                }
            } finally {
                barrier.collectorFinished()
            }
        }
        try {
            events.emit(savepoint)
            val consumeChunk = async(start = CoroutineStart.UNDISPATCHED) {
                barrier.awaitPublished()
                mutex.withLock {
                    val visible = parser.append(
                        "<operit_state_report>{\"frame_id\":\"host\",\"updates\":[],\"create\":[]}</operit_state_report>正文"
                    )
                    text.append(visible)
                    visible
                }
            }
            assertFalse("text must wait for its previously published savepoint", consumeChunk.isCompleted)
            assertNull(parser.report)
            allowSavepoint.complete(Unit)
            assertEquals("正文", consumeChunk.await())
            assertNotNull(parser.report)

            // No text follows this event. The normal-completion barrier must still observe it.
            events.emit(rollback)
            val finishResponse = async(start = CoroutineStart.UNDISPATCHED) {
                barrier.awaitPublished()
                observer.cancelAndJoin()
                parser.finish()
                parser.report
            }
            assertFalse("finish must wait for the last published rollback", finishResponse.isCompleted)
            allowRollback.complete(Unit)
            assertNull(finishResponse.await())
            assertEquals("", text.currentContent().toString())
            assertEquals(listOf(savepoint, rollback), forwarded)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test fun anEndedObserverCannotLeaveAReplayWaitSuspended() = runBlocking {
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val barrier = NativeResponseRevisionBarrier(events)
        events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "unprocessed"))
        barrier.collectorFinished()
        try {
            barrier.awaitPublished()
            fail("an incomplete replay must fail explicitly")
        } catch (failure: NativeResponseRevisionBarrier.ProtocolException) {
            assertEquals(NativeResponseRevisionBarrier.ErrorCode.COLLECTOR_ENDED, failure.code)
            assertNull(failure.cause)
        }
    }

    @Test fun aClearedReplayIsRejectedInsteadOfBeingTreatedAsCaughtUp() = runBlocking {
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val barrier = NativeResponseRevisionBarrier(events)
        val event = TextStreamEvent(TextStreamEventType.SAVEPOINT, "saved")
        events.emit(event)
        barrier.acknowledge(event)
        barrier.awaitPublished()
        events.resetReplayCache()
        try {
            barrier.awaitPublished()
            fail("native response revision replay must remain complete")
        } catch (failure: NativeResponseRevisionBarrier.ProtocolException) {
            assertEquals(NativeResponseRevisionBarrier.ErrorCode.INCOMPLETE_REPLAY, failure.code)
        }
    }

    @Test fun anEventMissingFromFullReplayCannotBeAcknowledged() = runBlocking {
        val events = MutableSharedStream<TextStreamEvent>(replay = 0)
        val barrier = NativeResponseRevisionBarrier(events)
        val event = TextStreamEvent(TextStreamEventType.SAVEPOINT, "missing")
        events.emit(event)
        val failure = assertThrows(NativeResponseRevisionBarrier.ProtocolException::class.java) {
            barrier.acknowledge(event)
        }
        assertEquals(NativeResponseRevisionBarrier.ErrorCode.INCOMPLETE_REPLAY, failure.code)
    }

    @Test fun localBarrierFailureCrossesProviderAsNonRetryableCancellation() = runBlocking {
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val barrier = NativeResponseRevisionBarrier(events)
        events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "unprocessed"))
        barrier.collectorFinished()
        var attempts = 0
        suspend fun provider(consume: suspend () -> Unit) {
            repeat(5) {
                attempts++
                try {
                    consume()
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Native providers retry ordinary failures, including consumer exceptions.
                }
            }
        }
        try {
            try {
                provider { barrier.awaitBeforeText() }
            } catch (abort: NativeResponseRevisionBarrier.LocalAbort) {
                throw abort.protocolFailure
            }
            fail("a local barrier failure must be reported outside the provider")
        } catch (failure: NativeResponseRevisionBarrier.ProtocolException) {
            assertEquals(NativeResponseRevisionBarrier.ErrorCode.COLLECTOR_ENDED, failure.code)
        }
        assertEquals(1, attempts)
    }
}
