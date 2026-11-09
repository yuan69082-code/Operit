package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.GeminiProvider
import com.ai.assistance.operit.api.chat.llmprovider.SingleApiKeyProvider
import com.ai.assistance.operit.api.chat.llmprovider.InvalidThinkingOptionException
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.JvmAppLogRule
import com.ai.assistance.operit.util.stream.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GeminiHttpFailureIntegrationTest {
    @get:Rule val appLog = JvmAppLogRule()

    @Test fun invalidPersistedLevelIsRejectedBeforeHttpWithoutRetry(): Unit = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            error("An invalid local option must not reach HTTP")
        }.build()
        val context = mock<Context>()
        whenever(context.getString(any<Int>())).thenReturn("status")
        val provider = GeminiProvider("https://example.test", SingleApiKeyProvider("test"),
            "gemini-3-pro-preview", client,
            thinkingConfigurations = ModelThinkingConfigDefaults.forProvider("GOOGLE"),
            thinkingOptionId = "MINIMAL")
        val retries = CopyOnWriteArrayList<String>()
        val failure = withTimeout(3_000) {
            MessageProcessingDelegate.collectResponseReaders {
                provider.sendMessage(context, listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                    emptyList(), false, true, null, false, { _, _, _ -> }, null,
                    { retries.add(it) }, true, false, null).collect { fail("No reply expected") }
            }
        }
        assertTrue(failure is InvalidThinkingOptionException)
        assertEquals(0, calls.get())
        assertTrue(retries.isEmpty())
    }

    @Test fun actualHttp400IsDeliveredToTurnWithoutCrashingHostOrRetrying(): Unit = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body("""{"error":{"code":400,"message":"Thinking level is unsupported: THINKING_LEVEL_MINIMAL","status":"INVALID_ARGUMENT"}}""".toResponseBody()).build()
        }.build()
        val context = mock<Context>()
        whenever(context.getString(any<Int>())).thenReturn("status")
        whenever(context.getString(any<Int>(), any(), any())).thenReturn("HTTP 400: THINKING_LEVEL_MINIMAL")
        val provider = GeminiProvider("https://example.test", SingleApiKeyProvider("test"), "gemini-test", client)
        val retries = CopyOnWriteArrayList<String>()
        try {
            // Real provider and request serializer, fake HTTP response only. The same sharing
            // and owning-reader boundary is used by AIMessageManager/MessageProcessingDelegate.
            val raw = provider.sendMessage(context, listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                emptyList(), false, true, null, false, { _, _, _ -> }, null, { retries.add(it) }, true, false, null)
            val shared = raw.shareRevisable(host, replay = Int.MAX_VALUE, propagateCompletionCause = false)
            val outcome = CompletableDeferred<Throwable?>()
            val owner = host.launch {
                outcome.complete(MessageProcessingDelegate.collectResponseReaders {
                    val revisions = launch { (shared as TextStreamEventCarrier).eventChannel.collect { } }
                    try {
                        shared.collect { fail("400 cannot produce a completed reply") }
                        MessageProcessingDelegate.requireSuccessfulResponse(shared)
                    } finally { revisions.cancelAndJoin() }
                })
            }
            val failure = withTimeout(10_000) { outcome.await() }
            owner.join()
            assertTrue(failure is GeminiProvider.HttpStatusException)
            assertEquals(400, (failure as GeminiProvider.HttpStatusException).statusCode)
            assertEquals(1, calls.get())
            assertTrue(retries.isEmpty())
            assertTrue(uncaught.toString(), uncaught.isEmpty())
            val next = CompletableDeferred<String>()
            host.launch { streamOf("next turn").share(host, replay = 1).collect { next.complete(it) } }.join()
            assertEquals("next turn", withTimeout(1_000) { next.await() })
            assertTrue(host.isActive)
        } finally { host.cancel() }
    }
}
