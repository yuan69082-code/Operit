package com.ai.assistance.operit.api.chat

import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.TextStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Orders native provider revision events before their following cold-stream text callbacks.
 *
 * Contract: OpenAI, Claude and Gemini publish events into an append-only replay=Int.MAX_VALUE
 * channel before invoking the next text callback, and publish their last event before collect
 * returns. The text producer waits for that callback; this is not a general hot-stream adapter.
 * Subscribe before starting the cold producer (UNDISPATCHED). The revision collector must
 * acknowledge only after applying and forwarding an event under the same mutex as text, and
 * must call [collectorFinished] from finally. The text consumer waits outside that mutex before
 * every chunk and once after normal completion.
 */
internal class NativeResponseRevisionBarrier(private val events: SharedStream<TextStreamEvent>) {
    enum class ErrorCode { INCOMPLETE_REPLAY, COLLECTOR_ENDED, EVENT_ORDER }

    class ProtocolException(val code: ErrorCode) :
        IllegalStateException("Native response revisions: ${code.name}")

    /** Native providers must not treat a local ordering failure as a retryable network error. */
    class LocalAbort(val protocolFailure: ProtocolException) :
        CancellationException("Native response revision processing aborted")

    private data class Progress(val processed: Int = 0, val finished: Boolean = false)

    private val progress = MutableStateFlow(Progress())
    // Only the serial text consumer updates this. The event collector owns Progress.
    private var lastPublishedCount = 0

    fun acknowledge(event: TextStreamEvent) {
        val current = progress.value
        if (current.finished) fail(ErrorCode.COLLECTOR_ENDED)
        val published = events.replayCache
        if (current.processed >= published.size) fail(ErrorCode.INCOMPLETE_REPLAY)
        if (published[current.processed] != event) fail(ErrorCode.EVENT_ORDER)
        progress.value = current.copy(processed = current.processed + 1)
    }

    fun collectorFinished() {
        progress.value = progress.value.copy(finished = true)
    }

    /** The caller unwraps [LocalAbort] immediately outside provider collect. */
    suspend fun awaitBeforeText() {
        try {
            awaitPublished()
        } catch (failure: ProtocolException) {
            throw LocalAbort(failure)
        }
    }

    suspend fun awaitPublished() {
        val publishedCount = events.replayCache.size
        if (publishedCount < lastPublishedCount || publishedCount < progress.value.processed) {
            fail(ErrorCode.INCOMPLETE_REPLAY)
        }
        lastPublishedCount = publishedCount
        val acknowledged = progress.first { it.processed >= publishedCount || it.finished }
        // A completed collector cannot deliver the outstanding replay. Fail instead of waiting
        // forever, or accepting a report whose final rollback was never applied.
        if (acknowledged.processed < publishedCount) fail(ErrorCode.COLLECTOR_ENDED)
        if (events.replayCache.size < publishedCount) fail(ErrorCode.INCOMPLETE_REPLAY)
    }

    private fun fail(code: ErrorCode): Nothing = throw ProtocolException(code)
}
