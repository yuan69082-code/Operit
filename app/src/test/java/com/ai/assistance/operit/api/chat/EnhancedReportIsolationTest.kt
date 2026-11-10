package com.ai.assistance.operit.api.chat

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ConversationService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.services.core.MessageProcessingDelegate
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.util.stream.*
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
import java.util.concurrent.CopyOnWriteArrayList

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
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val warnings = CopyOnWriteArrayList<String>()
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) })

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
                    stream { output(index, frame) }
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            } }
            val model = ModelConfigData("simulated", "本地模拟", enableToolCall = false)
            val manager = Mockito.mock(MultiServiceManager::class.java) { call -> when (call.method.name) {
                "acquireServiceForFunction", "acquireServiceForConfig" -> MultiServiceManager.ServiceLease({}, provider, model, emptyList())
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
        }
        private fun field(name: String): Any = EnhancedAIService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        private fun set(name: String, value: Any) { EnhancedAIService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
        fun savedReport(frame: InternalStateFrame): JSONObject? =
            CompanionStore(app).archivedStateRow("internal-state:$role", "semantic_reports", frame.id)
                ?: InternalStateEngine.rows(InternalStateStore(app).read(role), "semantic_reports").firstOrNull { it.getString("frame_id") == frame.id }
        suspend fun send(): Pair<String, Throwable?> {
            val ref = ActivityNotes.begin(app, role, chat, "request:${calls + 1}", if (wake) "background" else "normal_input")
            try {
                val response = service.sendMessage(EnhancedAIService.SendMessageOptions(message = "继续", chatId = chat,
                    roleCardId = role, activityId = ref.id, isSubTask = true, enableMemoryAutoUpdate = false,
                    disableWarning = true, onNonFatalError = { warnings.add(it) })).share(host, replay = Int.MAX_VALUE, propagateCompletionCause = false)
                val text = StringBuilder()
                val error = withTimeout(30_000) { MessageProcessingDelegate.collectResponseReaders {
                    val observer = launch { response.collect { /* UI text-only observer */ } }
                    response.collect { text.append(it) }
                    observer.join()
                    // Match the production owning consumer: cancellation in a child launch
                    // alone does not cancel its parent or classify the send as interrupted.
                    MessageProcessingDelegate.requireSuccessfulResponse(response)
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
            host.coroutineContext[Job]!!.cancelAndJoin()
            (field("toolProcessingScope") as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            EnhancedAIService.releaseChatInstance(chat)
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
}
