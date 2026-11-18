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
class SubjectConcernsEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    private lateinit var second: String
    private lateinit var handler: AIToolHandler
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        CharacterCardManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
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
        for (name in listOf("sleep", "read_file", "companion_software", "companion_internal_state", "companion_life")) {
            CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", name, true)
            handler.getToolPermissionSystem().saveToolPermission(name, PermissionLevel.ALLOW)
        }
    }
    private suspend fun sections(window: String = chat, wake: Boolean = false) =
        InnerStructureContext.prepare(context, role, window, true, wake = wake, input = "继续自己的研究")
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

    private fun attention(brief: String = "把一个问题真正弄明白", pinned: Boolean = true, horizon: String = "long_term") =
        JSONObject().put("horizon", horizon).put("brief", brief).put("importance", "对自己的长期发展重要")
            .put("focus", "今天还惦记").put("pinned", pinned).put("protected", true).put("tags", JSONArray().put("目标").put("决定"))
    private fun life(f: InternalStateFrame, id: String = "interest", withAttention: Boolean = true) = JSONObject()
        .put("kind", "direction").put("id", id).put("expected_version", 0).put("text", "原本想弄懂这个问题，而不是只找到一个答案")
        .put("scope", "自己的研究").put("uncertainty", "方法还可能改变").put("status", "active")
        .put("evidence", JSONArray().put(f.eventIds.first())).apply { if (withAttention) put("attention", attention()) }
    private fun patch(f: InternalStateFrame, id: String = "interest", version: Int = 1) = JSONObject()
        .put("kind", "direction").put("id", id).put("expected_version", version).put("evidence", JSONArray().put(f.eventIds.first()))
    private fun saved(id: String = "interest") = InternalStateStore(context).inspectCategory(role, chat, "direction", id, 0).getJSONObject("item")
    private fun settings(version: Int = 0, long: Int = 2, short: Int = 2, old: Int = 1) = JSONObject()
        .put("expected_version", version).put("long_term", long).put("short_term", short).put("old", old).put("mode", "mixed")
    private fun material(f: InternalStateFrame, id: String = "interest", version: Int = 1) =
        JSONObject(requireNotNull(f.materialSources["material:direction:$id:$version"]))

    @Test fun standingOriginalSurvivesProgressAndReopenWithoutBeingPouredIntoContext(): Unit = runBlocking {
        val original = "完整本意不能变成一句空话。".repeat(500)
        val f = requireNotNull(sections().internalStateFrame)
        submit(f, JSONObject().put("life", JSONArray().put(life(f).put("text", original).put("purpose", "为什么要做"))))
        repeat(4) { round ->
            val s = sections(if (round % 2 == 0) second else chat, wake = round % 2 == 0)
            val current = requireNotNull(s.internalStateFrame)
            assertFalse(s.state.contains(original))
            assertTrue(s.instructions.contains("contract id=concerns"))
            assertEquals("STANDING_CONCERN", material(current, version = round + 1).getString("selection_reason"))
            assertFalse(material(current, version = round + 1).getBoolean("full_concern_read"))
            submit(current, JSONObject().put("life", JSONArray().put(patch(current, version = round + 1)
                .put("attention", JSONObject().put("progress", "第${round + 1}步已核对")))))
        }
        assertEquals(original, saved().getJSONObject("content").getString("text"))
        assertEquals("为什么要做", saved().getJSONObject("content").getString("purpose"))
        assertEquals("第4步已核对", saved().getJSONObject("content").getJSONObject("attention").getString("progress"))
        assertTrue(saved().getJSONObject("content").getJSONObject("attention").getBoolean("pinned"))
        assertEquals(5, InternalStateStore(context).inspectCategory(role, chat, "category_versions", "direction:interest", 0).getJSONArray("items").length())
        assertEquals(1, InternalStateStore(context).concernStatus(role).getJSONArray("items").length())
    }

    @Test fun explicitChangeNeedsRealDetailsAndCompletionReleasesThePosition(): Unit = runBlocking {
        val first = requireNotNull(sections().internalStateFrame)
        submit(first, JSONObject().put("life", JSONArray().put(life(first))))
        val s = sections(); val f = requireNotNull(s.internalStateFrame)
        try {
            submit(f, JSONObject().put("life", JSONArray().put(patch(f).put("text", "随意精简"))))
            fail("locked meaning")
        } catch (e: InternalStateReportIssue) { assertEquals("EXPLICIT_CONCERN_CHANGE", e.rule) }
        try {
            submit(f, JSONObject().put("life", JSONArray().put(patch(f).put("status", "ended").put("attention_change", "已经完成"))))
            fail("a compact card is not a full read")
        } catch (e: InternalStateReportIssue) { assertEquals("CONCERN_DETAIL_REQUIRED", e.rule) }
        val results = execute(f, listOf(invocation("companion_internal_state", mapOf("action" to "get", "section" to "concerns", "id" to "direction:interest")))).results
        assertTrue(results.single().error, results.single().success)
        assertTrue(results.single().result.toString().contains("原本想弄懂这个问题"))
        val read = delivered(s, results)
        assertTrue(material(read).getBoolean("full_concern_read"))
        submit(read, JSONObject().put("life", JSONArray().put(patch(read).put("status", "ended").put("attention_change", "核对结果后决定结束"))))
        repeat(3) {
            val next = requireNotNull(sections(wake = true).internalStateFrame)
            assertFalse(next.materialSources.containsKey("material:direction:interest:2"))
        }
        assertEquals("ended", saved().getJSONObject("content").getString("status"))
        assertEquals("inactive", InternalStateStore(context).concernStatus(role).getJSONArray("items").getJSONObject(0).getString("parent"))
    }

    @Test fun configurationAndFirstCommonSetupKeepSourceAndCannotOverwriteAgain(): Unit = runBlocking {
        val states = InternalStateStore(context)
        val configured = states.configureConcerns(role, settings(), JSONArray().put(JSONObject()
            .put("text", "我们初次共同写下的完整本意").put("attention", attention("我们共同确定的目标"))))
        val entry = configured.getJSONArray("items").getJSONObject(0)
        assertTrue(configured.getJSONObject("configuration").getBoolean("initialized"))
        assertEquals("user_initial_setting", saved(entry.getString("target")).getString("source"))
        reject { states.configureConcerns(role, settings(1), JSONArray()) }
        reject { states.configureConcerns(role, settings(1, long = 0)) }
        states.configureConcerns(role, settings(1, long = 3, short = 1, old = 2))
        val s = sections(); val f = requireNotNull(s.internalStateFrame)
        assertEquals("user_initial_setting", material(f, entry.getString("target")).getString("origin"))
        submit(f, JSONObject().put("life", JSONArray().put(patch(f, entry.getString("target"))
            .put("attention", JSONObject().put("progress", "开始尝试")))))
        assertEquals("user_initial_setting", material(requireNotNull(sections().internalStateFrame), entry.getString("target"), 2).getString("origin"))
    }

    @Test fun undeclaredInterestDoesNotBecomePinnedAndOverflowNeverEvictsAnExistingPin(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        submit(f, JSONObject().put("life", JSONArray().put(life(f, "unadopted-interest", false))))
        assertEquals(0, InternalStateStore(context).concernStatus(role).getJSONArray("items").length())
        val next = requireNotNull(sections().internalStateFrame)
        submit(next, JSONObject().put("attention_config", settings(long = 1)).put("life", JSONArray().put(life(next))))
        val last = requireNotNull(sections().internalStateFrame)
        try { submit(last, JSONObject().put("life", JSONArray().put(life(last, "another")))); fail("pin count") }
        catch (e: InternalStateReportIssue) { assertEquals("PINNED_SLOTS", e.rule) }
        assertTrue(InternalStateStore(context).inspectCategory(role, chat, "direction", "another", 0).isNull("item"))
        assertEquals(1, saved().getInt("version"))
        assertEquals("STANDING_CONCERN", material(requireNotNull(sections().internalStateFrame)).getString("selection_reason"))
    }

    @Test fun staleProgressCannotReplaceAnotherTurnAndSubjectsRemainIsolated(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        submit(f, JSONObject().put("life", JSONArray().put(life(f))))
        val first = requireNotNull(sections().internalStateFrame)
        val secondFrame = requireNotNull(sections(second).internalStateFrame)
        submit(first, JSONObject().put("life", JSONArray().put(patch(first).put("attention", JSONObject().put("progress", "真实新进度")))))
        reject { submit(secondFrame, JSONObject().put("life", JSONArray().put(patch(secondFrame).put("attention", JSONObject().put("progress", "旧轮次进度"))))) }
        assertEquals("真实新进度", saved().getJSONObject("content").getJSONObject("attention").getString("progress"))
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一位助手"))
        InternalStateStore(context).configure(other, true)
        assertEquals(0, InternalStateStore(context).concernStatus(other).getJSONArray("items").length())
        assertEquals(0, InternalStateStore(context).concernStatus(other).getJSONObject("configuration").getInt("version"))
    }

    @Test fun realToolDetailsContainRulesAndIndexDoesNotPretendToBeFullRead(): Unit = runBlocking {
        val f = requireNotNull(sections().internalStateFrame)
        submit(f, JSONObject().put("life", JSONArray().put(life(f))))
        val s = sections(); val next = requireNotNull(s.internalStateFrame)
        val contract = execute(next, listOf(invocation("companion_internal_state", mapOf("action" to "get", "section" to "contract", "id" to "concerns")))).results.single()
        assertTrue(contract.error, contract.success)
        assertTrue(contract.result.toString().contains("attention_change"))
        assertTrue(contract.result.toString().contains("action_feedback"))
        val results = execute(next, listOf(invocation("companion_internal_state", mapOf("action" to "get", "section" to "concerns"))), 1).results
        val read = delivered(s, results)
        assertFalse(material(read).getBoolean("full_concern_read"))
        assertFalse(results.single().result.toString().contains("原本想弄懂这个问题"))
    }

    @Test fun fiveRoundsConnectAttentionStateRealExecutionFeedbackAndLaterChoice(): Unit = runBlocking {
        // Model decisions below are simulated; reads, tool execution, receipts, persistence,
        // version adoption and later independent requests use production entry points.
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val first = requireNotNull(sections().internalStateFrame)
        val fatigue = JSONObject().put("op", "appraise").put("id", "fatigue").put("expected_version", 0)
            .put("category_ref", "foundation:feeling:fatigue").put("intensity", 0.7).put("importance", "当前影响投入")
            .put("long_term_impact", "不否定长期方向").put("attention", "foreground").put("status", "processing")
            .put("understanding", "想研究，但感到疲劳").put("basis", "input").put("reason", "当前体验")
            .put("evidence", JSONArray().put(first.eventIds.first()))
        submit(first, JSONObject().put("life", JSONArray().put(life(first))).put("episodes", JSONArray().put(fatigue)))

        val secondSections = sections(wake = true); val secondFrame = requireNotNull(secondSections.internalStateFrame)
        assertEquals(1, material(secondFrame).getJSONObject("locator").getInt("version"))
        val readings = execute(secondFrame, listOf(invocation("companion_internal_state", mapOf("action" to "get", "section" to "episode", "id" to "fatigue")))).results
        val secondRead = delivered(secondSections, readings)
        val refs = JSONArray().put(JSONObject().put("kind", "direction").put("id", "interest").put("version", 1))
            .put(JSONObject().put("kind", "episode").put("id", "fatigue").put("version", 1))
        val union = JSONObject().put("id", "research-tension").put("expected_version", 0).put("members", refs)
            .put("objects", JSONArray()).put("evidence", JSONArray().put(secondRead.eventIds.first()))
            .put("interpretation", "目标仍重要，但疲劳改变投入方式").put("decision", "先用一个小实验核对")
            .put("status", "active").put("effects", JSONArray()
                .put(JSONObject().put("from", "episode:fatigue").put("to", "direction:interest").put("meaning", "把投入分成较小步骤"))
                .put(JSONObject().put("from", "direction:interest").put("to", "episode:fatigue").put("meaning", "保留兴趣，同时认真承认疲劳")))
        submit(secondRead, JSONObject().put("life", JSONArray().put(patch(secondRead)
            .put("related", JSONArray().put(refs.getJSONObject(1))).put("attention", JSONObject().put("progress", "决定先做小实验"))))
            .put("unions", JSONArray().put(union)))

        val third = sections(second, wake = true); val thirdFrame = requireNotNull(third.internalStateFrame)
        assertEquals(2, material(thirdFrame, version = 2).getJSONObject("locator").getInt("version"))
        assertEquals(1, thirdFrame.interactionMembers["episode:fatigue"])
        assertTrue(third.state.contains("research-tension"))
        val operation = execute(thirdFrame, listOf(invocation("companion_software", mapOf("action" to "build", "id" to "small-experiment",
            "source" to "${'$'}{x} + 2", "__operit_action" to JSONObject().put("decision", "按当前投入决定试一个小方法")
                .put("intention_refs", JSONArray().put(JSONObject().put("kind", "direction").put("id", "interest").put("version", 2))).toString())))).results
        assertTrue(operation.single().error, operation.single().success)
        val thirdRead = delivered(third, operation)
        val source = thirdRead.materialSources.entries.first { JSONObject(it.value).optJSONObject("locator")?.optString("action_id") == operation.single().subjectActionId }.key
        val feedback = JSONObject().put("id", "experiment-result").put("expected_version", 0).put("action_id", operation.single().subjectActionId)
            .put("result_source", source).put("meaning", "模板已构建，但还没有运行，不能当作问题已经解决")
            .put("evidence", JSONArray().put(source)).put("intention_refs", JSONArray().put(JSONObject().put("kind", "direction").put("id", "interest").put("version", 2)))
        val updateFatigue = JSONObject(fatigue.toString()).put("expected_version", 1).put("intensity", 0.6)
            .put("understanding", "拆小实验后感受有所变化，仍需休息").put("evidence", JSONArray().put(source)).put("basis", "result")
        submit(thirdRead, JSONObject().put("action_feedback", JSONArray().put(feedback)).put("episodes", JSONArray().put(updateFatigue))
            .put("life", JSONArray().put(patch(thirdRead, version = 2).put("evidence", JSONArray().put(source))
                .put("attention", JSONObject().put("progress", "模板已构建，下一次先运行核对，尚未解决")))))

        val fourth = sections(wake = true); val fourthFrame = requireNotNull(fourth.internalStateFrame)
        assertTrue(fourth.state.contains("下一次先运行核对"))
        assertTrue(fourth.state.contains("不能当作问题已经解决"))
        assertEquals(2, fourthFrame.interactionMembers["episode:fatigue"])
        assertTrue(fourth.state.contains("current_member_versions"))
        // The later simulated choice reads the saved limitation and uses the actual built
        // version; it cannot silently count the earlier build as a completed experiment.
        val builtVersion = JSONObject(operation.single().result.toString()).getString("version")
        val detail = execute(fourthFrame, listOf(
            invocation("companion_internal_state", mapOf("action" to "get", "section" to "concerns", "id" to "direction:interest")),
            invocation("companion_software", mapOf("action" to "run", "version" to builtVersion, "inputs" to "{\"x\":3}",
                "__operit_action" to JSONObject().put("decision", "上次只构建未运行，这次按反馈先核对实际结果")
                    .put("evidence", JSONArray().put("material:feedback:experiment-result:1"))
                    .put("intention_refs", JSONArray().put(JSONObject().put("kind", "direction").put("id", "interest").put("version", 3))).toString())))).results
        assertTrue(detail.joinToString { it.error.orEmpty() }, detail.all { it.success })
        assertEquals(5.0, JSONObject(detail.last().result.toString()).getDouble("output"), 0.0)
        val fourthRead = delivered(fourth, detail)
        submit(fourthRead, JSONObject().put("life", JSONArray().put(patch(fourthRead, version = 3).put("status", "paused")
            .put("attention_change", "保留进展，先休息，之后是否继续另行决定"))))
        val fifth = sections(second, wake = true)
        assertFalse(requireNotNull(fifth.internalStateFrame).materialSources.containsKey("material:direction:interest:4"))
        assertEquals("paused", saved().getJSONObject("content").getString("status"))
        assertEquals(1, InternalStateStore(context).inspectCategory(role, chat, "feedback", "experiment-result", 0).getJSONObject("item").getInt("version"))
    }
}
