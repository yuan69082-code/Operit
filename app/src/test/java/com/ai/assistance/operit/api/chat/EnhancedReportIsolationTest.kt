package com.ai.assistance.operit.api.chat

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ConversationService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.GeminiProvider
import com.ai.assistance.operit.api.chat.llmprovider.SingleApiKeyProvider
import com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.services.core.MessageProcessingDelegate
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.util.stream.*
import com.ai.assistance.operit.util.streamnative.NativeMarkdownSplitter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Real EnhancedAIService.sendMessage, shared stream, message-reader boundary, tool executor
 * and persisted state/results. Provider output is simulated; prompt preparation uses the real
 * subject composer in place of unrelated vision/system-prompt work. No external requests. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EnhancedReportIsolationTest {
    // A blocked JVM/fixture must fail with evidence rather than holding a release runner
    // indefinitely. This does not retry or waive any production-path assertion.
    @get:Rule val timeout = Timeout.builder().withTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .withLookingForStuckThread(true).build()
    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun mainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun restoreMainDispatcher() { Dispatchers.resetMain() }
    private class Fixture(val app: Context) {
        lateinit var role: String
        lateinit var chat: String
        lateinit var service: EnhancedAIService
        lateinit var handler: AIToolHandler
        var calls = 0
        var executions = 0
        var wake = false
        val frames = CopyOnWriteArrayList<InternalStateFrame>()
        var output: suspend StreamCollector<String>.(Int, InternalStateFrame) -> Unit = { _, f -> emit("下一条正常回复。" + report(f)) }
        var requestStream: ((List<PromptTurn>, Int) -> Stream<String>)? = null
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val warnings = CopyOnWriteArrayList<String>()
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        private val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        private var messageManagerJob: Job? = null

        suspend fun init() {
            ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
            CharacterCardManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
            role = CharacterCardManager.getInstance(app).createCharacterCard(CharacterCard("", "回报隔离"))
            val h = ChatHistory(title = "正常入口", messages = emptyList(), characterCardId = role)
            ChatHistoryManager.getInstance(app).saveChatHistory(h); chat = h.id
            InternalStateStore(app).configure(role, true)
            com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences(app)
            handler = AIToolHandler.getInstance(app)
            handler.registerDefaultTools(); registerAllTools(handler, app)
            handler.getToolPermissionSystem().saveMasterSwitch(PermissionLevel.ALLOW)
            handler.getToolPermissionSystem().saveToolPermission("sleep", PermissionLevel.ALLOW)
            CharacterCardManager.getInstance(app).setToolSelected(role, "builtin", "sleep", true)
            service = EnhancedAIService.getChatInstance(app, chat)
            val initScope = field("initScope") as CoroutineScope
            initScope.coroutineContext[Job]!!.cancelAndJoin()
            val provider = Mockito.mock(AIService::class.java) { call -> when (call.method.name) {
                "getProviderModel" -> "OPENAI:simulated"
                "calculateInputTokens" -> 100L
                "sendMessage" -> {
                    @Suppress("UNCHECKED_CAST")
                    val history = call.arguments[1] as List<PromptTurn>
                    val frame = requireNotNull(CurrentRequestContext.internalStateFrame(history))
                    frames.add(frame)
                    val index = ++calls
                    val suppliedStream = requestStream
                    if (suppliedStream != null) {
                        suppliedStream(history, index)
                    } else {
                        // Real native providers expose a separate revision channel. The old
                        // fixture skipped that collector entirely, including its failure path.
                        revisableOutput(index, frame)
                    }
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            } }
            val model = ModelConfigData("simulated", "本地模拟", enableToolCall = false)
            // The saved crash crosses TrackingRevisableStream before EnhancedAIService.
            // Exercise the production wrapper and its start/finish/cancellation lifecycle.
            val trackedProvider = TokenTrackingAIService(provider, app, model.id)
            val manager = Mockito.mock(MultiServiceManager::class.java) { call -> when (call.method.name) {
                "acquireServiceForFunction", "acquireServiceForConfig" -> MultiServiceManager.ServiceLease({}, trackedProvider, model, emptyList())
                "getServiceForFunction", "getServiceForConfig" -> trackedProvider
                "getModelConfigForFunction", "getModelConfigForConfig" -> model
                "getModelParametersForFunction", "getModelParametersForConfig" -> emptyList<ModelParameter<*>>()
                // Normal user turns query these suspend Boolean methods before preparing
                // history. Mockito sees Object and returns null unless explicitly stubbed.
                "hasImageRecognitionConfigured", "hasAudioRecognitionConfigured", "hasVideoRecognitionConfigured" -> false
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            } }
            val realComposer = field("conversationService") as ConversationService
            val composer = Mockito.mock(ConversationService::class.java) { call -> when (call.method.name) {
                "prepareConversationHistory" -> runBlocking {
                    val input = call.arguments[1] as String
                    CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, input)),
                        InnerStructureContext.prepare(app, role, chat, true, wake = wake, input = input))
                }
                "normalizeConversationHistoryForModel" -> {
                    // Tool continuation must retain the actual request frame and privacy
                    // projection, not Mockito's empty-list default for an unstubbed method.
                    @Suppress("UNCHECKED_CAST")
                    realComposer.normalizeConversationHistoryForModel(call.arguments[0] as List<PromptTurn>, call.arguments[1] as String?)
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            } }
            set("multiServiceManager", manager); set("conversationService", composer); set("isServiceManagerInitialized", true)
            handler.registerTool("sleep", executor = { _: AITool -> executions++; ToolResult("sleep", true, StringResultData("本地实际回执")) })
            // AIMessageManager and toolProcessingScope are independent of host. Observe the
            // same JVM boundary as the app's crash handler, not just our owning test scope.
            Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught.add(failure) }
        }
        private fun field(name: String): Any = EnhancedAIService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        private fun set(name: String, value: Any) { EnhancedAIService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
        fun failureDiagnostics(): String {
            val loggedFailures = listOf("EnhancedAIService", "MessageProcessingDelegate", "AIMessageManager")
                .flatMap { tag -> ShadowLog.getLogsForTag(tag).mapNotNull { item ->
                    item.throwable?.let { "$tag: ${item.msg}\n${it.stackTraceToString()}" }
                } }
            return "providerCalls=$calls; uncaught=${uncaught.map { it.stackTraceToString() }}\n" +
                loggedFailures.joinToString("\n")
        }
        fun revisableOutput(index: Int, frame: InternalStateFrame): Stream<String> {
            val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
            return stream<String> {
                events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "response:$index"))
                output(index, frame)
            }.withEventChannel(events)
        }
        fun savedReport(frame: InternalStateFrame): JSONObject? =
            CompanionStore(app).archivedStateRow("internal-state:$role", "semantic_reports", frame.id)
                ?: InternalStateEngine.rows(InternalStateStore(app).read(role), "semantic_reports").firstOrNull { it.getString("frame_id") == frame.id }
        suspend fun send(): Pair<String, Throwable?> {
            val ref = ActivityNotes.begin(app, role, chat, "request:${calls + 1}", if (wake) "background" else "normal_input")
            try {
                val response = service.sendMessage(EnhancedAIService.SendMessageOptions(message = "继续", chatId = chat,
                    roleCardId = role, activityId = ref.id, isSubTask = true, enableMemoryAutoUpdate = false,
                    disableWarning = true, onNonFatalError = { warnings.add(it) })).shareRevisable(host, replay = Int.MAX_VALUE, propagateCompletionCause = false)
                val text = StringBuilder()
                val error = withTimeout(30_000) { MessageProcessingDelegate.collectResponseReaders {
                    val observer = launch { response.collect { /* UI text-only observer */ } }
                    val revisions = launch { (response as TextStreamEventCarrier).eventChannel.collect { } }
                    try {
                        response.collect { text.append(it) }
                        observer.join()
                        // Match the production owning consumer: cancellation in a child launch
                        // alone does not cancel its parent or classify the send as interrupted.
                        MessageProcessingDelegate.requireSuccessfulResponse(response)
                    } finally {
                        revisions.cancelAndJoin()
                    }
                } }
                // Same owning consumer contract as a failed/partial UI message: confirmed
                // text remains, no successful timestamp is invented, no automatic resend.
                val saved = MessageProcessingDelegate.completeInterruptedMessage(ChatMessage(sender = "ai", contentStream = response),
                    text.toString(), null, if (error == null) System.currentTimeMillis() else 0)
                ChatHistoryManager.getInstance(app).addMessage(chat, saved)
                assertEquals(text.toString(), ChatHistoryManager.getInstance(app).loadChatMessages(chat).last().content)
                return text.toString() to error
            } finally { ActivityNotes.release(ref) }
        }
        suspend fun close() {
            try {
                host.coroutineContext[Job]!!.cancelAndJoin()
                (field("toolProcessingScope") as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
                // Shared revision collectors outlive the response text. Stop only this
                // fixture's manager children so later scenarios keep the singleton usable.
                messageManagerJob?.children?.toList()?.forEach { it.cancelAndJoin() }
                EnhancedAIService.releaseChatInstance(chat)
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
            }
        }
        suspend fun delegate(errors: MutableList<String>, completed: AtomicInteger): MessageProcessingDelegate {
            AIMessageManager.initialize(app)
            messageManagerJob = (AIMessageManager::class.java.getDeclaredField("scope")
                .apply { isAccessible = true }.get(null) as CoroutineScope).coroutineContext[Job]
            ModelConfigManager(app).saveModelConfig(ModelConfigData("simulated", "本地模拟", enableToolCall = false))
            WaifuPreferences.getInstance(app).saveEnableWaifuMode(false)
            val history = ChatHistoryManager.getInstance(app)
            // A pre-existing user turn prevents an unrelated asynchronous title request.
            history.addMessage(chat, ChatMessage(sender = "user", content = "上一条消息", timestamp = 1L))
            return MessageProcessingDelegate(
                context = app,
                coroutineScope = host,
                getEnhancedAiService = { service },
                getFullChatHistory = { history.loadChatMessages(it) },
                getRuntimeChatHistory = { history.loadChatMessages(it) },
                hasUserMessage = { id -> history.loadChatMessages(id).any { it.sender == "user" } },
                addMessageToChat = { id, message -> history.addMessage(id, message) },
                saveCurrentChat = {},
                showErrorMessage = { errors.add(it) },
                updateChatTitle = { _, _ -> fail("Existing conversations must not generate a title") },
                getChatTitle = { "正常入口" },
                onTurnComplete = { _, _, _, _ -> completed.incrementAndGet(); Unit },
                onTokenLimitExceeded = { _, _, _, _ -> fail("Summary is disabled for this fixture") },
                getIsAutoReadEnabled = { false },
                speakMessageHandler = { _, _ -> fail("Speech is disabled, but its native reader must still run") },
            )
        }
        suspend fun sendThroughDelegate(delegate: MessageProcessingDelegate, phase: String) {
            delegate.sendUserMessage(
                chatId = chat,
                roleCardId = role,
                messageTextOverride = "继续",
                prebuiltMessageContent = "继续",
                chatModelConfigIdOverride = "simulated",
                maxTokens = 131_072,
                tokenUsageThreshold = 0.9,
                enableMemoryAutoUpdate = false,
                enableSummary = false,
                turnOptions = ChatTurnOptions(notifyReply = false, disableWarning = true),
            )
            // This production gate joins the actual send job, including persistence and
            // runtime cleanup, instead of treating stream closure as a completed turn.
            try {
                withTimeout(30_000) { delegate.withSummaryAfterTurn(chat) {} }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$phase: delegate did not finish; " +
                    "loading=${delegate.isChatLoading(chat)}, state=${delegate.inputProcessingStateByChatId.value[chat]}, " +
                    failureDiagnostics(), timeout)
            }
        }
        companion object {
            fun report(f: InternalStateFrame) = "<operit_state_report>" + JSONObject().put("frame_id", f.id)
                .put("updates", JSONArray()).put("create", JSONArray()) + "</operit_state_report>"
        }
    }

    @Test fun finalReportsAfterProseAndBackgroundControlsCommitWithoutAnotherModelCall(): Unit = runBlocking {
        val f = Fixture(RuntimeEnvironment.getApplication()); f.init()
        try {
            for (wake in listOf(false, true)) {
                f.wake = wake
                f.output = { _, frame -> emit(if (wake) "<companion_note>私人随记</companion_note><activity_note>这次留下的随记。</activity_note><companion_quiet/>" + Fixture.report(frame)
                    else "正文在前。" + Fixture.report(frame)) }
                val (text, error) = f.send()
                assertNull(error); assertFalse(text.contains("operit_state_report")); assertTrue(f.warnings.isEmpty())
                assertNotNull("Successful parsing must actually reach normal report adoption", f.savedReport(f.frames.last()))
            }
            assertEquals(2, f.calls); assertEquals(0, f.executions); assertTrue(f.uncaught.isEmpty())
        } finally { f.close() }
    }

    @Test fun malformedFirstAndToolContinuationKeepFactsAndNextMessageStillRuns(): Unit = runBlocking {
        val f = Fixture(RuntimeEnvironment.getApplication()); f.init()
        try {
            f.output = { _, _ -> emit("已经生成的正文。<operit_state_report broken><activity_note>坏报告里的内容不能变成随记</activity_note></operit_state_report>") }
            val first = f.send()
            assertTrue(first.second is InternalStateReportStream.ProtocolException)
            assertTrue(first.first.contains("已经生成的正文。")); assertEquals(1, f.calls)
            assertNull(f.savedReport(f.frames.last()))
            val failedActivity = ActivityNotes.page(f.app, f.role, "activity", null, 0).getJSONArray("items").getJSONObject(0)
            val failedParts = ActivityNotes.page(f.app, f.role, "activity_part", failedActivity.getString("id"), 0).toString()
            assertFalse(failedParts.contains("坏报告里的内容不能变成随记"))
            assertTrue(failedParts.contains("report_failure"))
            f.output = { n, _ -> if (n == 2) emit("<tool name=\"sleep\"><param name=\"duration_ms\">0</param></tool>")
                else emit("已拿到工具结果。<operit_state_report>{") }
            val second = f.send()
            assertTrue(second.second?.stackTraceToString() ?: "No failure; provider calls=${f.calls}",
                second.second is InternalStateReportStream.ProtocolException)
            assertEquals(3, f.calls); assertEquals(1, f.executions)
            assertNull(f.savedReport(f.frames.last()))
            val actions = SubjectActions.read(f.app, f.role, f.chat, null, 0).getJSONArray("items")
            assertTrue((0 until actions.length()).any { actions.getJSONObject(it).optString("status") == "completed" })
            f.output = { _, frame -> emit("失败后的下一条仍可回复。" + Fixture.report(frame)) }
            assertNull(f.send().second); assertEquals(4, f.calls); assertEquals(1, f.executions)
            assertTrue(f.uncaught.isEmpty())
        } finally { f.close() }
    }

    @Test fun cancelledBackgroundKeepsCompletedNoteAndDoesNotAdoptPartialReport(): Unit = runBlocking {
        val f = Fixture(RuntimeEnvironment.getApplication()); f.init()
        try {
            f.wake = true
            f.output = { _, _ -> emit("<activity_note>中断前完整留下的随记。</activity_note><operit_state_report>{")
                throw CancellationException("模拟网络或用户中断") }
            try { f.send(); fail("cancellation") } catch (_: CancellationException) { }
            assertNull(f.savedReport(f.frames.last()))
            val activity = ActivityNotes.page(f.app, f.role, "activity", null, 0).getJSONArray("items").getJSONObject(0)
            assertTrue(ActivityNotes.page(f.app, f.role, "activity_part", activity.getString("id"), 0).toString().contains("中断前完整留下的随记"))
            f.output = { _, frame -> emit("中断后的下一次。" + Fixture.report(frame)) }
            assertNull(f.send().second); assertEquals(2, f.calls); assertTrue(f.uncaught.isEmpty())
        } finally { f.close() }
    }

    @Test fun nativeHttp400And402InFirstOrToolResponseRemainFailedTurns(): Unit = runBlocking {
        for (status in listOf(400, 402)) {
            for (afterTool in listOf(false, true)) {
                val scenario = "HTTP $status, " + if (afterTool) "tool continuation" else "first response"
                val f = Fixture(RuntimeEnvironment.getApplication()); f.init()
                val httpCalls = AtomicInteger()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    httpCalls.incrementAndGet()
                    val body = if (status == 400) {
                        """{"error":{"code":400,"message":"Thinking level is unsupported: THINKING_LEVEL_MINIMAL","status":"INVALID_ARGUMENT"}}"""
                    } else {
                        """[{"error":{"code":402,"message":"Your prepayment credits are depleted.","status":"RESOURCE_EXHAUSTED"}}]"""
                    }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(status).message("Request rejected").body(body.toResponseBody()).build()
                }.build()
                val provider = GeminiProvider("https://example.test", SingleApiKeyProvider("test"), "gemini-test", client)
                try {
                    f.output = { _, _ -> emit("<tool name=\"sleep\"><param name=\"duration_ms\">0</param></tool>") }
                    f.requestStream = { history, index ->
                        if (afterTool && index == 1) {
                            f.revisableOutput(index, f.frames.last())
                        } else {
                            // Fake only HTTP. The actual Gemini serializer, revision stream,
                            // EnhancedAIService first/continuation readers and owner all run.
                            runBlocking {
                                provider.sendMessage(f.app, history, emptyList(), false, true, null,
                                    false, { _, _, _ -> }, null, { f.warnings.add(it) }, true, false, null)
                            }
                        }
                    }
                    val (_, failure) = f.send()
                    assertTrue("$scenario: ${failure?.stackTraceToString()}", failure is GeminiProvider.HttpStatusException)
                    assertEquals(scenario, status, (failure as GeminiProvider.HttpStatusException).statusCode)
                    assertEquals(scenario, 1, httpCalls.get())
                    assertTrue("$scenario: must not be retried", f.warnings.isEmpty())
                    assertEquals(scenario, if (afterTool) 1 else 0, f.executions)
                    assertNull(scenario, f.savedReport(f.frames.last()))
                    assertTrue("$scenario: ${f.uncaught}", f.uncaught.isEmpty())
                    assertTrue(scenario, f.host.isActive)

                    // A later user turn succeeds, and a completed tool is not executed again.
                    f.requestStream = null
                    f.output = { _, frame -> emit("下一轮仍可回复。" + Fixture.report(frame)) }
                    assertNull("$scenario: next user turn", f.send().second)
                    assertEquals("$scenario: no tool replay", if (afterTool) 1 else 0, f.executions)
                    assertTrue("$scenario: ${f.uncaught}", f.uncaught.isEmpty())
                } finally { f.close() }
                assertTrue("$scenario after cleanup: ${f.uncaught.map { it.stackTraceToString() }}", f.uncaught.isEmpty())
            }
        }
    }

    @Test fun delegateHttp400ClearsFailedTurnAndAllowsNextSend(): Unit = runBlocking {
        assertDelegateHttpFailure(400)
    }

    @Test fun delegateHttp402ClearsFailedTurnAndAllowsNextSend(): Unit = runBlocking {
        assertDelegateHttpFailure(402)
    }

    private suspend fun assertDelegateHttpFailure(status: Int) {
        // Do not skip the actual autoRead/Waifu parser when JNI is unavailable. CI builds
        // the production streamnative sources for the host JVM before running this test.
        NativeMarkdownSplitter.createBlockSession().destroy()
        val f = Fixture(RuntimeEnvironment.getApplication()); f.init()
        val errors = CopyOnWriteArrayList<String>()
        val completed = AtomicInteger()
        val httpCalls = AtomicInteger()
        val scenario = "Delegate HTTP $status"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            httpCalls.incrementAndGet()
            val body = if (status == 400) {
                """{"error":{"code":400,"message":"Thinking level is unsupported: THINKING_LEVEL_MINIMAL","status":"INVALID_ARGUMENT"}}"""
            } else {
                """[{"error":{"code":402,"message":"Your prepayment credits are depleted.","status":"RESOURCE_EXHAUSTED"}}]"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Request rejected").body(body.toResponseBody()).build()
        }.build()
        val provider = GeminiProvider("https://example.test", SingleApiKeyProvider("test"), "gemini-test", client)
        try {
            val delegate = f.delegate(errors, completed)
            f.requestStream = { history, _ -> runBlocking {
                provider.sendMessage(f.app, history, emptyList(), false, true, null,
                    false, { _, _, _ -> }, null, { f.warnings.add(it) }, true, false, null)
            } }
            f.sendThroughDelegate(delegate, scenario)

            val failedState = delegate.inputProcessingStateByChatId.value[f.chat]
            val failureEvidence = "$scenario: $failedState; errors=$errors; HTTP calls=${httpCalls.get()}\n${f.failureDiagnostics()}"
            assertTrue(failureEvidence, failedState is InputProcessingState.Error)
            assertTrue(failureEvidence, (failedState as InputProcessingState.Error).message.contains(status.toString()))
            assertTrue("Error must reach the UI: $failureEvidence", errors.any { it.contains(status.toString()) })
            assertEquals("One HTTP request: $failureEvidence", 1, httpCalls.get())
            assertTrue("$scenario: must not be retried", f.warnings.isEmpty())
            assertEquals("$scenario: no successful callback", 0, completed.get())
            assertEquals("$scenario: no successful turn", 0L, delegate.getTurnCompleteCounter(f.chat))
            assertFalse("$scenario: chat loading", delegate.isChatLoading(f.chat))
            assertFalse("$scenario: global loading", delegate.isLoading.value)
            assertFalse("$scenario: active stream", f.chat in delegate.activeStreamingChatIds.value)
            assertNull("$scenario: detached response", delegate.getResponseStream(f.chat))
            assertTrue("$scenario: rejected request must not create an AI reply",
                ChatHistoryManager.getInstance(f.app).loadChatMessages(f.chat).none { it.sender == "ai" })
            assertTrue("$scenario: uncaught exceptions=${f.uncaught.map { it.stackTraceToString() }}", f.uncaught.isEmpty())
            assertTrue("$scenario: host remains active", f.host.isActive)

            errors.clear()
            f.requestStream = null
            f.output = { _, frame -> emit("后续消息正常回复。" + Fixture.report(frame)) }
            f.sendThroughDelegate(delegate, "$scenario next send")
            assertEquals("$scenario: next turn completed", InputProcessingState.Completed,
                delegate.inputProcessingStateByChatId.value[f.chat])
            assertEquals("$scenario: successful callback", 1, completed.get())
            assertEquals("$scenario: successful counter", 1L, delegate.getTurnCompleteCounter(f.chat))
            assertEquals("$scenario: no automatic resends", 1, httpCalls.get())
            assertEquals("$scenario: exactly two provider calls", 2, f.calls)
            assertFalse("$scenario: next turn loading", delegate.isChatLoading(f.chat))
            assertFalse("$scenario: next global loading", delegate.isLoading.value)
            assertNull("$scenario: next response detached", delegate.getResponseStream(f.chat))
            val reply = ChatHistoryManager.getInstance(f.app).loadChatMessages(f.chat).single { it.sender == "ai" }
            assertEquals("后续消息正常回复。", reply.content)
            assertTrue("$scenario: saved completion", reply.completedAt > 0L)
            assertNull("$scenario: saved stream detached", reply.contentStream)
            assertTrue("$scenario: next UI errors=$errors", errors.isEmpty())
            assertTrue("$scenario: uncaught exceptions=${f.uncaught.map { it.stackTraceToString() }}", f.uncaught.isEmpty())
        } finally { f.close() }
        assertTrue("$scenario after cleanup: ${f.uncaught.map { it.stackTraceToString() }}", f.uncaught.isEmpty())
    }
}
