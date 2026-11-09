package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.util.stream.StreamCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Simulated model outputs; actual production composer, parser, executor, permissions,
 * SQLite receipts, report adoption and calculator runtime. No network or external sends. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectActionEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    private lateinit var second: String
    private lateinit var handler: AIToolHandler
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "主体", characterSetting = "初始参考"))
        InternalStateStore(context).configure(role, true)
        suspend fun window(): String {
            val c = ChatHistory(title = "操作测试", messages = emptyList(), characterCardId = role)
            ChatHistoryManager.getInstance(context).saveChatHistory(c); return c.id
        }
        chat = window(); second = window()
        // Plain Robolectric Application does not run OperitApplication's permission bootstrap.
        // Use the same initializer before the real default tool registrar.
        com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences(context)
        handler = AIToolHandler.getInstance(context)
        handler.registerDefaultTools()
        // Restore production delegates against this test Application, including companion reads.
        // Fault tests replace only the already-authorized local sleep/read_file executor.
        registerAllTools(handler, context)
        handler.getToolPermissionSystem().saveMasterSwitch(PermissionLevel.ALLOW)
        for (name in listOf("sleep", "read_file", "companion_software", "companion_internal_state")) {
            CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", name, true)
            handler.getToolPermissionSystem().saveToolPermission(name, PermissionLevel.ALLOW)
        }
    }
    private suspend fun sections(window: String = chat) =
        InnerStructureContext.prepare(context, role, window, true, input = "继续自己的研究")
    private fun invocation(name: String, args: Map<String, String> = emptyMap()) = ToolInvocation(
        AITool(name, args.map { ToolParameter(it.key, it.value) }), "", 0..0)
    private suspend fun execute(f: InternalStateFrame, calls: List<ToolInvocation>, round: Int = 0) =
        ToolExecutionManager.executeInvocations(calls, context, handler, handler.getOrCreatePackageManager(),
            object : StreamCollector<String> { override suspend fun emit(value: String) {} },
            callerCardId = role, callerChatId = f.chatId, subjectFrame = f, subjectRound = round)
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private suspend fun submit(f: InternalStateFrame, fields: JSONObject): JSONObject {
        val r = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
        fields.keys().forEach { r.put(it, fields.get(it)) }
        val parser = InternalStateReportStream()
        assertEquals("正文", parser.append("<operit_state_report>$r</operit_state_report>正文") + parser.finish())
        return InnerStructureContext.submit(context, f, requireNotNull(parser.report))
    }
    private fun delivered(sections: com.ai.assistance.operit.core.chat.RuntimeContextSections, results: List<ToolResult>): InternalStateFrame {
        val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "继续")), sections) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, ConversationMarkupManager.buildToolResultMessage(results), "tools")
        return requireNotNull(CurrentRequestContext.internalStateFrame(SubjectToolMaterials.attach(turns,
            System.currentTimeMillis(), results, context)))
    }

    @Test fun actualRequestAndReceiptSurviveFinalReportRejectionAndReparse(): Unit = runBlocking {
        val calls = AtomicInteger()
        handler.registerTool("sleep", executor = { _: AITool ->
            calls.incrementAndGet(); ToolResult("sleep", true, StringResultData("实际完成"))
        })
        val s = sections(); val f = requireNotNull(s.internalStateFrame)
        val first = execute(f, listOf(invocation("sleep"))).results.single()
        assertTrue(first.error, first.success)
        val row = SubjectActions.read(context, role, chat, first.subjectActionId, 0).getJSONObject("item")
        assertEquals("completed", row.getString("status"))
        assertTrue(row.isNull("declaration"))
        reject { submit(f, JSONObject().put("unexpected", true)) }
        // Re-parsed invocation has a fresh UUID, but same request frame and position.
        val repeated = execute(f, listOf(invocation("sleep"))).results.single()
        assertEquals(first.subjectActionId, repeated.subjectActionId)
        assertEquals(1, calls.get())
        val continuation = delivered(s, listOf(first))
        assertTrue(continuation.materialSources.values.any { JSONObject(it).optString("type") == "actual_operation" })
        val next = sections(second)
        // The budget may omit detail; normal native query still returns persisted exact content.
        val result = execute(requireNotNull(next.internalStateFrame), listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "action", "id" to requireNotNull(first.subjectActionId))))).results.single()
        assertTrue(result.error, result.success)
        assertTrue(result.result.toString().contains("实际完成"))
        assertEquals("实际完成", CompanionStore(context).actionRead(role, requireNotNull(first.subjectActionId))!!
            .getJSONObject("receipt").getString("text"))
    }

    @Test fun startRecordConflictDoesNotAbortOtherOperations(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        val secondId = InnerStructure.hash("${f.id}:0:1")
        val executions = AtomicInteger()
        handler.registerTool("sleep", executor = { _: AITool ->
            if (executions.incrementAndGet() == 1) {
                // Fault injection at the durable start boundary, after all requests were
                // claimed by the real executor. Do not fabricate a successful receipt.
                CompanionStore(context).actionWrite(role, secondId) { old ->
                    val row = requireNotNull(old)
                    row.put("status", "started").put("version", row.getInt("version") + 1)
                }
            }
            ToolResult("sleep", true, StringResultData("实际完成"))
        })
        val results = execute(f, List(3) { invocation("sleep") }).results
        assertEquals(3, results.size)
        assertTrue(results[0].success)
        assertFalse(results[1].success)
        assertEquals("denied", results[1].executionStatus)
        assertTrue(results[1].error!!.contains("未执行"))
        assertTrue(results[2].success)
        assertEquals(2, executions.get())
        assertEquals("completed", SubjectActions.read(context, role, chat, results[0].subjectActionId, 0)
            .getJSONObject("item").getString("status"))
    }

    @Test fun adoptedUnderstandingIsReadLaterAndNotImpliedBySuccess(): Unit = runBlocking {
        val s = sections(); val f = requireNotNull(s.internalStateFrame)
        val result = execute(f, listOf(invocation("sleep", mapOf("duration_ms" to "0")))).results.single()
        assertTrue(result.error, result.success)
        val bound = delivered(s, listOf(result))
        val source = bound.materialSources.entries.first { JSONObject(it.value).optString("type") == "actual_operation" }.key
        val state = InternalStateStore(context)
        assertEquals(0, state.inspectCategory(role, chat, "feedback", null, 0).getJSONArray("items").length())
        val feedback = JSONObject().put("id", "understanding").put("expected_version", 0)
            .put("action_id", result.subjectActionId).put("result_source", source).put("meaning", "操作已返回，但我还想换一种方法")
            .put("evidence", JSONArray().put(source)).put("intention_refs", JSONArray())
        val decision = JSONObject().put("id", "own-direction").put("expected_version", 0).put("status", "considering")
            .put("meaning", "想继续自己的兴趣，尚未决定具体操作").put("evidence", JSONArray().put(source))
        val fields = JSONObject().put("action_feedback", JSONArray().put(feedback)).put("decisions", JSONArray().put(decision))
        submit(bound, fields)
        submit(bound, fields) // The original report receipt handles exact retry.
        assertEquals(1, state.inspectCategory(role, chat, "feedback", "understanding", 0).getJSONObject("item").getInt("version"))
        val next = sections(second)
        assertTrue(ActionUnderstanding.materials(state.read(role)).any { it.getString("text").contains("换一种方法") })
        val queried = execute(requireNotNull(next.internalStateFrame), listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "feedback", "id" to "understanding")))).results.single()
        assertTrue(queried.error, queried.success)
        assertTrue(queried.result.toString().contains("换一种方法"))
        val readDecision = execute(requireNotNull(next.internalStateFrame), listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "decision", "id" to "own-direction"))), 1).results.single()
        assertTrue(readDecision.error, readDecision.success)
        assertEquals("considering", JSONObject(readDecision.result.toString()).getJSONObject("item").getJSONObject("content").getString("status"))
    }

    @Test fun permissionDenialIsPersistedWithoutExecution(): Unit = runBlocking {
        val count = AtomicInteger()
        handler.registerTool("sleep", executor = { _: AITool -> count.incrementAndGet(); ToolResult("sleep", true, StringResultData("wrong")) })
        handler.getToolPermissionSystem().saveToolPermission("sleep", PermissionLevel.FORBID)
        val f = requireNotNull(sections().internalStateFrame)
        val result = execute(f, listOf(invocation("sleep"))).results.single()
        assertFalse(result.success); assertEquals("denied", result.executionStatus); assertEquals(0, count.get())
        assertEquals("denied", SubjectActions.read(context, role, chat, result.subjectActionId, 0).getJSONObject("item").getString("status"))
    }

    @Test fun partialAndUnknownResultsAreNotPromotedToCompletion(): Unit = runBlocking {
        handler.registerTool("sleep", executor = object : ToolExecutor {
            override fun invoke(tool: AITool) = ToolResult("sleep", true, StringResultData("part"))
            override fun invokeAndStream(tool: AITool) = flow {
                emit(invoke(tool)); emit(ToolResult("sleep", false, StringResultData(""), "second part failed"))
            }
        })
        handler.registerTool("read_file", executor = { _: AITool -> throw IllegalStateException("transport lost after dispatch") })
        val f = requireNotNull(sections().internalStateFrame)
        val results = execute(f, listOf(invocation("sleep"), invocation("read_file"))).results
        assertEquals("partial", results[0].executionStatus)
        assertEquals("unknown", results[1].executionStatus)
    }

    @Test fun pendingRecoveryDoesNotReplayAndExplicitNewAttemptCanRun(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        val tool = AITool("sleep", listOf(ToolParameter("duration_ms", "0")))
        val id = InnerStructure.hash("${f.id}:0:0")
        val ledger = SubjectActions(context, f)
        assertTrue(ledger.claim(id, tool, null).execute)
        ledger.started(id) // Contract crash boundary: no execution result was observed.
        val duplicate = execute(f, listOf(ToolInvocation(tool, "", 0..0))).results.single()
        assertEquals("unknown", duplicate.executionStatus)
        val s = sections()
        val read = execute(requireNotNull(s.internalStateFrame), listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "action", "id" to id)))).results.single()
        val bound = delivered(s, listOf(read))
        val declaration = JSONObject().put("decision", "我明确再试一次").put("retry_of", id)
        val retry = execute(bound, listOf(invocation("sleep", mapOf("duration_ms" to "0",
            SubjectActions.DECLARATION to declaration.toString()))), 1).results.single()
        assertTrue(retry.error, retry.success); assertNotEquals(id, retry.subjectActionId)
    }

    @Test fun builtInAndReceiptReplayDoNotInitializeUnrelatedPackageCatalog(): Unit = runBlocking {
        val packages = org.mockito.Mockito.mock(com.ai.assistance.operit.core.tools.packTool.PackageManager::class.java)
        org.mockito.Mockito.doThrow(IllegalStateException("unrelated package catalog must not be loaded"))
            .`when`(packages).getAvailablePackages(org.mockito.Mockito.anyBoolean())
        org.mockito.Mockito.doThrow(IllegalStateException("unrelated enabled packages must not be loaded"))
            .`when`(packages).getEnabledPackageNames()
        val f = requireNotNull(sections().internalStateFrame)
        suspend fun normalRequest() = ToolExecutionManager.executeInvocations(
            listOf(invocation("sleep", mapOf("duration_ms" to "0"))), context, handler, packages,
            object : StreamCollector<String> { override suspend fun emit(value: String) {} },
            callerCardId = role, callerChatId = chat, subjectFrame = f).results.single()
        val first = normalRequest()
        assertTrue(first.error, first.success)
        val replay = normalRequest()
        assertTrue(replay.error, replay.success)
        assertEquals(first.subjectActionId, replay.subjectActionId)
        org.mockito.Mockito.verify(packages, org.mockito.Mockito.never()).getAvailablePackages(org.mockito.Mockito.anyBoolean())
        org.mockito.Mockito.verify(packages, org.mockito.Mockito.never()).getEnabledPackageNames()
    }

    @Test fun concurrentDuplicateAndSeparateRequestsPreserveFacts(): Unit = runBlocking {
        val count = AtomicInteger()
        handler.registerTool("sleep", executor = { _: AITool ->
            count.incrementAndGet(); Thread.sleep(40); ToolResult("sleep", true, StringResultData("once"))
        })
        val f = requireNotNull(sections().internalStateFrame)
        // Preserve a stalled concurrency site's evidence in CI artifacts. This does not
        // bypass assertions, interrupt workers or change the production execution path.
        val watchdog = java.util.Timer("subject-action-concurrency-diagnostics", true)
        watchdog.schedule(object : java.util.TimerTask() {
            override fun run() {
                val report = java.io.File("build/reports/subject-action-concurrency-threads.txt")
                report.parentFile.mkdirs()
                report.writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                    "${thread.name} ${thread.state}\n" + stack.joinToString("\n")
                })
            }
        }, 5000)
        val results = try {
            coroutineScope { (0..1).map { async(Dispatchers.IO) { execute(f, listOf(invocation("sleep"))).results.single() } }.awaitAll() }
        } finally { watchdog.cancel() }
        assertEquals(1, count.get()); assertTrue(results.any { it.success })
        val other = requireNotNull(sections(second).internalStateFrame)
        execute(other, listOf(invocation("sleep")))
        assertEquals(2, count.get())
        assertEquals(2, SubjectActions.read(context, role, chat, null, 0).getJSONArray("items").length())
    }

    @Test fun realCapabilityBuildRunAdjustAndFeedbackUseNormalTools(): Unit = runBlocking {
        val s = sections(); val f = requireNotNull(s.internalStateFrame)
        val build = execute(f, listOf(invocation("companion_software",
            mapOf("action" to "build", "id" to "reading_minutes", "source" to "${'$'}{pages}*2")))).results.single()
        assertTrue(build.error, build.success)
        val installed = JSONObject(build.result.toString())
        assertEquals("not_run", installed.getString("execution"))
        val version = installed.getString("version")
        val run = execute(f, listOf(invocation("companion_software", mapOf("action" to "run", "version" to version,
            "inputs" to "{\"pages\":7}"))), 1).results.single()
        assertTrue(run.error, run.success)
        assertEquals(14.0, JSONObject(run.result.toString()).getDouble("output"), 0.0)
        val bound = delivered(s, listOf(run))
        val source = bound.materialSources.keys.first { it.startsWith("material:action:") }
        submit(bound, JSONObject().put("action_feedback", JSONArray().put(JSONObject().put("id", "pace")
            .put("expected_version", 0).put("meaning", "这个估计偏紧，后面可以调整；现在没有自动改")
            .put("action_id", run.subjectActionId).put("result_source", source).put("evidence", JSONArray().put(source)))))
        // A later simulated model explicitly requests adjustment; no test callback writes a next decision.
        val nextSections = sections(second)
        val next = requireNotNull(nextSections.internalStateFrame)
        val feedback = execute(next, listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "feedback", "id" to "pace")))).results.single()
        assertTrue(feedback.result.toString().contains("偏紧"))
        val nextBound = delivered(nextSections, listOf(feedback))
        val adjustment = JSONObject().put("decision", "结合已读的上次理解，明确选择调整")
            .put("intention_refs", JSONArray().put(JSONObject().put("kind", "feedback").put("id", "pace").put("version", 1)))
            .put("capability", JSONObject().put("id", "reading_minutes").put("version", version).put("stage", "adjust").put("target", "local arithmetic template"))
        val adjusted = execute(nextBound, listOf(invocation("companion_software", mapOf("action" to "build",
            "id" to "reading_minutes", "source" to "${'$'}{pages}*3", "previous_version" to version,
            SubjectActions.DECLARATION to adjustment.toString()))), 1).results.single()
        assertTrue(adjusted.error, adjusted.success)
        val updated = JSONObject(adjusted.result.toString()).getString("version")
        val output = execute(next, listOf(invocation("companion_software", mapOf("action" to "run",
            "version" to updated, "inputs" to "{\"pages\":7}"))), 2).results.single()
        assertEquals(21.0, JSONObject(output.result.toString()).getDouble("output"), 0.0)
        assertEquals("${'$'}{pages}*2", SoftwareCapabilityArtifacts(context, role, second).get(version).getString("source"))
    }

    @Test fun interruptedBuildRecoversOnlyItsOwnImmutableArtifactWithoutExecution(): Unit = runBlocking {
        val real = requireNotNull(handler.getToolExecutor("companion_software"))
        val executions = AtomicInteger()
        handler.registerTool("companion_software", executor = { tool: AITool ->
            executions.incrementAndGet()
            val result = real.invoke(tool)
            check(result.success) { result.error.orEmpty() }
            throw CancellationException("fault after actual artifact commit, before receipt")
        })
        val f = requireNotNull(sections().internalStateFrame)
        val call = invocation("companion_software", mapOf("action" to "build", "id" to "local", "source" to "2*3"))
        try { execute(f, listOf(call)); fail("fault expected") } catch (_: CancellationException) { }
        val unknown = SubjectActions.read(context, role, chat, null, 0).getJSONArray("items").getJSONObject(0)
        assertEquals("unknown", unknown.getString("status"))
        val verified = execute(f, listOf(call)).results.single()
        assertTrue(verified.error, verified.success)
        assertEquals("completed", verified.executionStatus)
        assertEquals(1, executions.get())
        val version = JSONObject(verified.result.toString()).getString("version")
        val output = SoftwareCapabilityArtifacts(context, role, second).get(version)
        assertEquals(verified.subjectActionId, output.getString("build_request_id"))
        assertNull(SoftwareCapabilityArtifacts(context, role, second).recoverBuild("local", "2*3", null, "different-request"))
        assertEquals(verified.result.toString(), execute(f, listOf(call)).results.single().result.toString())
        handler.registerTool("companion_software", executor = real)
        val history = execute(requireNotNull(sections(second).internalStateFrame), listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "action_versions", "id" to requireNotNull(verified.subjectActionId))))).results.single()
        assertTrue(history.error, history.success)
        val versions = JSONObject(history.result.toString()).getJSONArray("items")
        assertEquals("completed", versions.getJSONObject(0).getString("status"))
        assertEquals("unknown", versions.getJSONObject(1).getString("status"))
    }

    @Test fun resultHeadsStayBoundedAndDetailsSurviveLargeReceipts(): Unit = runBlocking {
        handler.registerTool("sleep", executor = { _: AITool -> ToolResult("sleep", true, StringResultData("x".repeat(40000))) })
        val f = requireNotNull(sections().internalStateFrame)
        repeat(10) { execute(f, listOf(invocation("sleep")), it) }
        val page = SubjectActions.read(context, role, chat, null, 0)
        assertEquals(8, page.getJSONArray("items").length()); assertFalse(page.isNull("next_offset"))
        assertTrue(page.toString().length < 20000)
        val id = page.getJSONArray("items").getJSONObject(0).getString("id")
        assertEquals(40000, SubjectActions.read(context, role, second, id, 0).getJSONObject("item").getJSONObject("receipt").getString("text").length)
        assertEquals(2, SubjectActions.read(context, role, chat, null, 8).getJSONArray("items").length())
    }

    @Test fun privateResultsCannotBeReadByOtherAssistantsOrGroups(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        val result = execute(f, listOf(invocation("sleep", mapOf("duration_ms" to "0")))).results.single()
        assertTrue(result.error, result.success)
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一助手"))
        withContext(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(other)) {
            reject { SubjectActions.read(context, role, chat, result.subjectActionId, 0) }
        }
        val groupId = com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
            .createCharacterGroupCard(CharacterGroupCard("", "群", members = listOf(GroupMemberConfig(role), GroupMemberConfig(other))))
        val group = ChatHistory(title = "群", messages = emptyList(), characterGroupId = groupId)
        ChatHistoryManager.getInstance(context).saveChatHistory(group)
        val groupFrame = requireNotNull(sections(group.id).internalStateFrame)
        assertFalse(groupFrame.materialSources.values.any { it.contains(requireNotNull(result.subjectActionId)) })
        val denied = execute(groupFrame, listOf(invocation("companion_internal_state", mapOf("action" to "get",
            "section" to "action", "id" to requireNotNull(result.subjectActionId))))).results.single()
        assertFalse(denied.success)
        assertFalse(denied.result.toString().contains("15.0"))
        val shared = execute(groupFrame, listOf(invocation("sleep", mapOf("duration_ms" to "0"))), 1).results.single()
        assertTrue(shared.error, shared.success)
        val nextGroup = requireNotNull(sections(group.id).internalStateFrame)
        assertTrue(nextGroup.materialSources.values.any { it.contains(requireNotNull(shared.subjectActionId)) })
        assertFalse(nextGroup.materialSources.values.any { it.contains(requireNotNull(result.subjectActionId)) })
        reject { SubjectActions.read(context, role, second, shared.subjectActionId, 0) }
        assertEquals(0, CompanionStore(context).actionPage(other, "private", 0).size)
        reject { SoftwareCapabilityArtifacts(context, role, group.id) }
    }

    @Test fun invalidOperationDeclarationDoesNotAbortOtherToolsOrForgeEvidence(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        val bad = JSONObject().put("decision", "我的选择").put("evidence", JSONArray().put("never-provided"))
        val batch = execute(f, listOf(invocation("sleep", mapOf("duration_ms" to "0", SubjectActions.DECLARATION to bad.toString())),
            invocation("sleep", mapOf("duration_ms" to "0"))))
        assertFalse(batch.results[0].success)
        assertTrue(batch.results[1].error, batch.results[1].success)
        assertEquals(1, SubjectActions.read(context, role, chat, null, 0).getJSONArray("items").length())
        val malformed = execute(f, listOf(invocation("sleep", mapOf("duration_ms" to "0",
            SubjectActions.DECLARATION to "{\"evidence\":\"not-an-array\"}"))), 1).results.single()
        assertFalse(malformed.success)
        assertEquals(1, SubjectActions.read(context, role, chat, null, 0).getJSONArray("items").length())
        assertTrue(runCatching { submit(f, JSONObject().put("decisions", "not-an-array")) }.isFailure)
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "decision", null, 0).getJSONArray("items").length())
        reject { submit(f, JSONObject().put("action_feedback", JSONArray().put(JSONObject().put("id", "invented")
            .put("expected_version", 0).put("action_id", "none").put("result_source", "never-provided")
            .put("meaning", "没有实际读取的结果").put("evidence", JSONArray().put("never-provided"))))) }
    }

    @Test fun nativeReadReplayKeepsProofAndCancellationNeverReexecutes(): Unit = runBlocking {
        val count = AtomicInteger()
        handler.registerTool("sleep", executor = { _: AITool ->
            count.incrementAndGet(); throw CancellationException("cancel after dispatch")
        })
        val f = requireNotNull(sections().internalStateFrame)
        try { execute(f, listOf(invocation("sleep"))); fail("cancel expected") }
        catch (_: CancellationException) { }
        val retried = execute(f, listOf(invocation("sleep"))).results.single()
        assertEquals(1, count.get()); assertEquals("unknown", retried.executionStatus)
        val s = sections()
        val next = requireNotNull(s.internalStateFrame)
        val query = invocation("companion_internal_state", mapOf("action" to "get", "section" to "action", "id" to requireNotNull(retried.subjectActionId)))
        execute(next, listOf(query))
        val replay = execute(next, listOf(query)).results.single()
        assertTrue(replay.result is InternalStateReadResultData)
        assertTrue(delivered(s, listOf(replay)).materialSources.values.any {
            JSONObject(it).optJSONObject("locator")?.optString("action_id") == retried.subjectActionId
        })
    }

    @Test fun changedRequestsCannotReuseAnAttemptAndTechnicalFailureDoesNotCreateUnderstanding(): Unit = runBlocking {
        handler.registerTool("sleep", executor = { _: AITool -> ToolResult("sleep", true,
            TerminalCommandResultData("local", "partial output", -1, "session", timedOut = true)) })
        val f = requireNotNull(sections().internalStateFrame)
        val first = execute(f, listOf(invocation("sleep"))).results.single()
        assertEquals("unknown", first.executionStatus)
        val conflict = execute(f, listOf(invocation("sleep", mapOf("duration_ms" to "0")))).results.single()
        assertFalse(conflict.success)
        assertEquals("unknown", SubjectActions.read(context, role, second, first.subjectActionId, 0).getJSONObject("item").getString("status"))
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "feedback", null, 0).getJSONArray("items").length())
    }

    @Test fun parsedToolDeclarationAndSubjectFeedbackUseOriginalAtomicAdoption(): Unit = runBlocking {
        val initial = requireNotNull(sections().internalStateFrame)
        fun paused() = JSONObject().put("condition", "always").put("reason", "本测试没有时间变化")
            .put("interval_minutes", 1).put("amount", 0)
        val definition = JSONObject().put("id", "feeling").put("name", "感受").put("description", "主体明确报告")
            .put("kind", "emotion").put("min", 0).put("max", 100).put("baseline", 30).put("scope", "global")
            .put("private", true).put("share", false).put("participates", true).put("effects", JSONArray())
            .put("time_rules", JSONObject().put("increase", paused()).put("decrease", paused()).put("floor", 0))
        submit(initial, JSONObject().put("create", JSONArray().put(JSONObject().put("definition", definition)
            .put("value", 30).put("reason", "模拟模型的明确报告"))))
        val s = sections(); val f = requireNotNull(s.internalStateFrame)
        val declaration = JSONObject().put("decision", "想检验这个计算").put("evidence", JSONArray().put(f.eventIds.first()))
        val calls = ToolExecutionManager.extractToolInvocations("""
            <tool_A1b2 name="sleep">
            <param name="duration_ms">0</param>
            <param name="__operit_action">$declaration</param>
            </tool_A1b2>
        """.trimIndent())
        assertEquals(1, calls.size)
        val result = execute(f, calls).results.single()
        assertTrue(result.error, result.success)
        val state = InternalStateStore(context)
        fun value() = state.inspectCategory(role, chat, "state", "feeling", 0).getJSONObject("item").getDouble("value")
        assertEquals(30.0, value(), 0.0) // Technical success did not supply a subjective change.
        val fact = SubjectActions.read(context, role, chat, result.subjectActionId, 0).getJSONObject("item")
        assertEquals("想检验这个计算", fact.getJSONObject("declaration").getString("decision"))
        val bound = delivered(s, listOf(result))
        val source = bound.materialSources.keys.first { it.startsWith("material:action:") }
        val feedback = JSONObject().put("id", "post-result").put("expected_version", 0).put("meaning", "有了结果，我改变了理解")
            .put("action_id", result.subjectActionId).put("result_source", source).put("evidence", JSONArray().put(source))
        val update = JSONObject().put("id", "feeling").put("value", 20).put("reason", "模拟模型后续选择的变化，不是技术成功映射")
            .put("evidence", JSONArray().put(source))
        val fields = JSONObject().put("updates", JSONArray().put(update)).put("action_feedback", JSONArray().put(feedback))
        submit(bound, fields)
        assertEquals(20.0, value(), 0.0)
        reject { submit(bound, JSONObject(fields.toString()).put("updates", JSONArray().put(JSONObject(update.toString()).put("value", 90)))) }
        assertEquals(20.0, value(), 0.0)
        val next = sections(second)
        val query = execute(requireNotNull(next.internalStateFrame), listOf(invocation("companion_internal_state",
            mapOf("action" to "get", "section" to "feedback", "id" to "post-result")))).results.single()
        val later = delivered(next, listOf(query))
        val params = JSONObject().put("decision", "沿用已读理解做另一项核对")
            .put("intention_refs", JSONArray().put(JSONObject().put("kind", "feedback").put("id", "post-result").put("version", 1)))
        val continuation = execute(later, listOf(invocation("sleep", mapOf("duration_ms" to "0", SubjectActions.DECLARATION to params.toString()))), 1).results.single()
        assertTrue(continuation.error, continuation.success)
    }
}
