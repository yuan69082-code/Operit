package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.core.companion.InternalStateReportStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ResponseReaderIsolationTest {
    @Test fun malformedAndTruncatedReportsStayInsideOwningTurnAndCancelSiblingReaders(): Unit = runBlocking {
        for ((text, code) in listOf(
            "</operit_state_report>" to InternalStateReportStream.ErrorCode.MALFORMED_REPORT_TAG,
            "正文<operit_state_report>" to InternalStateReportStream.ErrorCode.TRUNCATED_REPORT
        )) {
            val uncaught = CopyOnWriteArrayList<Throwable>()
            val host = CoroutineScope(SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, failure -> uncaught.add(failure) })
            val outcome = CompletableDeferred<Throwable?>()
            val siblingStopped = CompletableDeferred<Unit>()
            try {
                val job = host.launch {
                    outcome.complete(MessageProcessingDelegate.collectResponseReaders {
                        val ready = CompletableDeferred<Unit>()
                        launch {
                            try { ready.complete(Unit); awaitCancellation() }
                            finally { siblingStopped.complete(Unit) }
                        }
                        launch {
                            ready.await()
                            val parser = InternalStateReportStream()
                            parser.appendDeferringFailure(text)
                            parser.finish()
                        }
                    })
                }
                val failure = withTimeout(10_000) { outcome.await() }
                withTimeout(10_000) { job.join(); siblingStopped.await() }
                assertEquals(code, (failure as InternalStateReportStream.ProtocolException).code)
                assertTrue(uncaught.isEmpty())
                assertTrue(host.isActive)
                // The next turn still runs; no automatic model/tool retry was requested.
                assertNull(MessageProcessingDelegate.collectResponseReaders { launch { yield() } })
            } finally { host.cancel() }
        }
    }

    @Test fun userCancellationRemainsCancellation(): Unit = runBlocking {
        val cancelled = CancellationException("用户停止")
        try {
            MessageProcessingDelegate.collectResponseReaders { throw cancelled }
            fail("cancellation must propagate")
        } catch (actual: CancellationException) {
            // Coroutine stack-trace recovery may copy the exception across a suspension point.
            // The contract is propagated cancellation, not referential identity of the Throwable.
            assertEquals(cancelled.message, actual.message)
        }
    }
}
