package com.ai.assistance.operit.util.stream

import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HotStreamTest {

    private var previousSystemLogEnabled = true

    @Before
    fun disableAndroidSystemLogForJvmTests() {
        previousSystemLogEnabled = AppLogger.enableSystemLog
        AppLogger.enableSystemLog = false
    }

    @After
    fun restoreAndroidSystemLog() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
    }

    @Test
    fun shareForwardsUpstreamFailureAfterReplayingPartialContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = IOException("network interrupted")
        val shared =
            stream<String> {
                emit("partial")
                throw expected
            }.share(
                scope = scope,
                replay = Int.MAX_VALUE,
            )
        val received = StringBuilder()

        try {
            shared.collect { chunk -> received.append(chunk) }
            fail("shared stream must propagate the upstream failure")
        } catch (actual: IOException) {
            assertSame(expected, actual)
        } finally {
            scope.cancel()
        }

        assertEquals("partial", received.toString())
    }

    @Test
    fun shareCanRecordUpstreamFailureWithoutFailingObservers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val expected = IOException("network interrupted")
        val shared =
            stream<String> {
                emit("partial")
                throw expected
            }.share(
                scope = scope,
                replay = Int.MAX_VALUE,
                propagateCompletionCause = false,
            )
        val received = StringBuilder()

        shared.collect { chunk -> received.append(chunk) }

        assertEquals("partial", received.toString())
        assertSame(expected, shared.completionCause)
        scope.cancel()
    }

    @Test fun producerChildFailureIsOwnedBeforePublishingCompletion(): Unit = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val release = CompletableDeferred<Unit>()
        val childStarted = CompletableDeferred<Unit>()
        val expected = IOException("HTTP 400 from producer child")
        val shared = stream<String> {
            CoroutineScope(currentCoroutineContext()).launch {
                childStarted.complete(Unit)
                release.await()
                throw expected
            }
        }.share(scope, replay = 1, propagateCompletionCause = false)
        try {
            withTimeout(3_000) { childStarted.await() }
            release.complete(Unit)
            withTimeout(3_000) { shared.collect { fail("No response expected") } }
            assertSame(expected, shared.completionCause)
            assertTrue(uncaught.toString(), uncaught.isEmpty())
            assertTrue(scope.isActive)
        } finally { scope.cancel() }
    }

    @Test fun cleanupFailureIsNotPublishedAsSuccessOrUncaught(): Unit = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val expected = IOException("cleanup failed")
        val shared = streamOf("partial").share(scope, replay = 1, propagateCompletionCause = false,
            onComplete = { throw expected })
        try {
            val text = StringBuilder()
            withTimeout(3_000) { shared.collect { text.append(it) } }
            assertEquals("partial", text.toString())
            assertSame(expected, shared.completionCause)
            assertTrue(uncaught.toString(), uncaught.isEmpty())
        } finally { scope.cancel() }
    }
}
