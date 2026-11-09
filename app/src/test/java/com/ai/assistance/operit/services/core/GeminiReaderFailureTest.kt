package com.ai.assistance.operit.services.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class GeminiReaderFailureTest {
    @Test fun httpFailureInChildReaderIsReportedWithoutCrashingHost(): Unit = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> uncaught.add(failure) })
        val outcome = CompletableDeferred<Throwable?>()
        val expected = IOException("HTTP 400: at least one parts field")
        try {
            val job = host.launch {
                outcome.complete(MessageProcessingDelegate.collectResponseReaders {
                    launch { throw expected }
                })
            }
            val failure = withTimeout(10_000) { outcome.await() }
            withTimeout(10_000) { job.join() }
            assertEquals(expected.message, failure?.message)
            assertTrue(uncaught.isEmpty())
            assertTrue(host.isActive)
            assertNull(MessageProcessingDelegate.collectResponseReaders { launch { yield() } })
        } finally { host.cancel() }
    }
}
