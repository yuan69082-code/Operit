package com.ai.assistance.operit.api.chat

import android.app.Application
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.core.tasks.TaskBoard
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Five independently triggered business turns through EnhancedAIService, its shared
 * stream/readers, MessageProcessingDelegate, production tool execution, scheduling,
 * persisted reports and subsequent material composition. Model outputs and the due
 * clock are controlled. The fixture's prepare adapter invokes production
 * InnerStructureContext; image handling and general prompt assembly are not exercised.
 * This is wiring evidence, NOT evidence of model growth.
 */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectGrowthFlowTest {
    @get:Rule val timeout = Timeout.seconds(180)
    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun start() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun stop() { Dispatchers.resetMain() }

    private fun tool(name: String, vararg args: Pair<String, String>) =
        "<tool name=\"" + name + "\">" + args.joinToString("") { (key, value) ->
            "<param name=\"" + key + "\">" + value + "</param>"
        } + "</tool>"
    private fun report(frame: InternalStateFrame) = JSONObject().put("frame_id", frame.id)
        .put("updates", JSONArray()).put("create", JSONArray())
    private fun tagged(value: JSONObject) = "<operit_state_report>" + value + "</operit_state_report>"
    private fun event(frame: InternalStateFrame) = frame.eventIds.single { it == "input:" + frame.id.removePrefix("state-frame:") }
    private fun life(frame: InternalStateFrame, kind: String, id: String, text: String, refs: List<String>) =
        JSONObject().put("kind", kind).put("id", id).put("expected_version", 0).put("text", text)
            .put("scope", "带异常点的测量比较").put("uncertainty", "方法适用性仍需验证")
            .put("status", "active").put("evidence", JSONArray(refs))
    private fun decision(frame: InternalStateFrame, version: Int, state: String, text: String) =
        JSONObject().put("id", "research").put("expected_version", version).put("status", state)
            .put("meaning", text).put("evidence", JSONArray().put(event(frame)))

    @Test fun fiveRealBusinessTurnsUseActualResultsReviseAndTransferWithoutAutomaticRescheduling(): Unit = runBlocking {
        val f = EnhancedReportIsolationTest.Fixture(RuntimeEnvironment.getApplication())
        f.init()
        try {
            InternalStateStore(f.app).materialBudget(f.role, f.chat, 16384)
            val cards = CharacterCardManager.getInstance(f.app)
            for (name in listOf("companion_todo", "companion_explore", "calculate", "companion_internal_state")) {
                f.handler.getToolPermissionSystem().saveToolPermission(name, PermissionLevel.ALLOW)
                cards.setToolSelected(f.role, "builtin", name, true)
            }
            AssistantWindowRouter.selected(f.app, f.role, f.chat)
            ExplorationStore(f.app).configure(f.role, true, setOf("reading"), 12)
            assertFalse(ExplorationStore(f.app).read(f.role).has("at"))
            // The ordinary scheduler applies the existing continuity boundary without
            // inventing work. Enabling exploration alone must remain quiet.
            assertNull(ExplorationStore(f.app).next())
            val errors = CopyOnWriteArrayList<String>()
            val completed = AtomicInteger()
            val delegate = f.delegate(errors, completed)
            var phase = 1
            var step = 0
            var taskId = ""
            var resultSource = ""
            var resultQuote = ""
            val calculations = mutableListOf<String>()
            val board = TaskBoard(f.app, "role:" + f.role, false)
            var proposal: JSONObject? = null
            f.output = { _, frame ->
                step++
                when (phase) {
                    1 -> when (step) {
                        1 -> emit(tool("companion_todo", "action" to "save", "title" to "星空测量研究",
                            "details" to """{"origin":"这次谈话提出的问题","goal":"比较异常点对统计量的影响","next":"核对已有样本"}"""))
                        2 -> {
                            taskId = board.list().single().getString("id")
                            assertFalse(board.get(taskId).has("dispatch")) // Saving interest is not scheduling.
                            emit(tool("companion_explore", "action" to "schedule", "minutes" to "15",
                                "purpose" to ("星空测量研究；事项 " + taskId + "；先读真实进度")))
                        }
                        else -> emit("我想保留这个问题。" + tagged(report(frame)
                            .put("decisions", JSONArray().put(decision(frame, 0, "chosen", "保留星空测量研究，比较方法")))))
                    }
                    2 -> when (step) {
                        1 -> {
                            assertTrue(frame.materialSources.values.any { it.contains("保留星空测量研究") })
                            emit(tool("companion_todo", "action" to "comment", "id" to taskId,
                                "content" to "已核对样本 1、2、3、100；还没有完成计算。"))
                        }
                        2 -> emit(tool("companion_explore", "action" to "schedule", "minutes" to "15",
                            "purpose" to ("继续星空测量事项 " + taskId + "；比较实际计算结果")))
                        else -> emit("<companion_quiet/>" + tagged(report(frame)))
                    }
                    3 -> when (step) {
                        1 -> emit(tool("calculate", "expression" to "(1+2+3+100)/4",
                            "__operit_action" to JSONObject().put("decision", "先检验已保留研究方向的样本均值")
                                .put("intention_refs", JSONArray().put(JSONObject().put("kind", "decision")
                                    .put("id", "research").put("version", 1)))
                                .put("evidence", JSONArray().put(event(frame))).toString()))
                        2 -> {
                            val receipt = frame.materialSources.entries.map { it.key to JSONObject(it.value) }
                                .single { (_, material) -> material.optString("type") == "actual_operation" &&
                                    JSONObject(material.getString("text")).optString("tool") == "calculate" }
                            resultSource = receipt.first
                            val body = JSONObject(receipt.second.getString("text"))
                            assertEquals("completed", body.getString("execution"))
                            assertTrue(body.getString("result_excerpt").contains("26.5"))
                            resultQuote = "26.5"
                            calculations += receipt.second.getJSONObject("locator").getString("action_id")
                            emit(tool("companion_explore", "action" to "schedule", "minutes" to "15",
                                "purpose" to "把星空异常点经验用于另一组样本，并重新评价方法"))
                        }
                        else -> {
                            val receipt = JSONObject(frame.materialSources.getValue(resultSource))
                            proposal = JSONObject().put("op", "propose").put("id", "method-v1").put("expected_version", 0)
                                .put("origin", "subject_development").put("reason", "实际均值暴露异常点影响")
                                .put("record", JSONObject().put("id", "sample-method").put("family", "self_knowledge")
                                    .put("kind", "研究方法").put("description", "遇到异常点时比较中位数，并保留原始样本；不确定是否适用于所有分布。")
                                    .put("status", "active")).put("evidence", JSONArray().put(resultSource))
                            emit("<companion_quiet/>" + tagged(report(frame)
                                .put("action_feedback", JSONArray().put(JSONObject().put("id", "compare-result")
                                    .put("expected_version", 0).put("meaning", "星空测量均值受到极端值影响，想比较稳健方法")
                                    .put("action_id", receipt.getJSONObject("locator").getString("action_id"))
                                    .put("result_source", resultSource).put("evidence", JSONArray().put(resultSource))))
                                .put("life", JSONArray().put(life(frame, "experience", "sample-comparison",
                                    "星空测量结果提示：换一组样本时也比较中位数，保留异常点及不确定性。", listOf(resultSource))))
                                .put("inner", JSONArray().put(proposal))))
                        }
                    }
                    4 -> when (step) {
                        1 -> {
                            assertTrue(frame.materialSources.values.any { it.contains("换一组样本时也比较中位数") })
                            // A large source-bound candidate is only previewed in the shared
                            // budget. Read its exact full version through the normal tool before
                            // adopting; a fragment must never grant adoption on its own.
                            emit(tool("companion_internal_state", "action" to "get",
                                "section" to "inner_pending", "id" to "method-v1"))
                        }
                        2 -> {
                            assertTrue(frame.innerPending.containsKey("method-v1"))
                            // This tool choice is a simulated model response. The executor really calculates it.
                            emit(tool("calculate", "expression" to "(11+12)/2",
                                "__operit_action" to JSONObject().put("decision", "用已提供的异常点经验比较另一组样本")
                                    .put("intention_refs", JSONArray().put(JSONObject().put("kind", "experience")
                                        .put("id", "sample-comparison").put("version", 1)))
                                    .put("evidence", JSONArray().put(event(frame))).toString()))
                        }
                        3 -> {
                            val receipt = frame.materialSources.values.map(::JSONObject).single { material ->
                                material.optString("type") == "actual_operation" && material.getString("text").contains("11.5")
                            }
                            calculations += receipt.getJSONObject("locator").getString("action_id")
                            emit(tool("companion_explore", "action" to "schedule", "minutes" to "15",
                                "purpose" to "重新评价星空测量方向与研究方法，核对已经得到的结果"))
                        }
                        else -> {
                            assertTrue(frame.materialSources.values.any { it.contains("11.5") })
                            val pending = InnerStructure.pending(InternalStateStore(f.app).read(f.role)).single { it.getString("id") == "method-v1" }
                            val adopted = JSONObject().put("op", "adopt").put("id", "method-v1").put("reason", "采用仍可修正的方法认识")
                                .put("development", JSONObject().put("account", "实际结果与原判断存在差异，保留适用范围")
                                    .put("before", pending.get("before")).put("after", pending.getJSONObject("record"))
                                    .put("anchors", JSONArray().put(JSONObject().put("ref", resultSource)
                                        .put("quote", resultQuote).put("interpretation", "异常点值得分别考察"))))
                            emit("<companion_quiet/>" + tagged(report(frame).put("inner", JSONArray().put(adopted))))
                        }
                    }
                    5 -> when (step) {
                        1 -> {
                            assertTrue(frame.materialText.contains("遇到异常点时比较中位数"))
                            assertTrue(frame.materialText.contains("不确定是否适用于所有分布"))
                            emit(tool("companion_explore", "action" to "pause"))
                        }
                        2 -> emit(tool("companion_todo", "action" to "save", "id" to taskId, "status" to "paused"))
                        else -> emit("<companion_quiet/>" + tagged(report(frame)
                            .put("decisions", JSONArray().put(decision(frame, 1, "deferred", "先暂停星空研究，保留真实结果和方法认识")))))
                    }
                }
            }
            f.sendThroughDelegate(delegate, "interest retained and scheduled", input = "星空测量中的异常点如何影响结果")
            assertEquals(3, step)
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(f.warnings.toString(), f.warnings.isEmpty())
            for (round in 2..5) {
                phase = round; step = 0; f.wake = true
                val scheduler = ExplorationStore(f.app) // Reopened store, no final-state DB writes.
                val due = scheduler.read(f.role).getLong("at")
                val work = requireNotNull(scheduler.next(due))
                assertNull(scheduler.next(due))
                assertTrue(work.purpose.contains("星空"))
                val activity = ActivityNotes.begin(f.app, f.role, f.chat, work.ticket, work.purpose)
                SubjectLife(f.app).checkpoint(activity, work.purpose, "executing", exploration = true)
                try {
                    f.sendThroughDelegate(delegate, "autonomous round " + round, input = work.purpose,
                        turnOptions = ChatTurnOptions(proactiveWake = true, persistTurn = false, notifyReply = false, disableWarning = true))
                    assertTrue("round " + round + ": " + f.warnings, f.warnings.isEmpty())
                    assertTrue(errors.toString(), errors.isEmpty())
                    SubjectLife(f.app).checkpoint(activity, work.purpose, "completed")
                } finally { ActivityNotes.release(activity) }
                scheduler.finish(work, "completed")
                if (round == 3) assertNull(InnerStructure.record(InternalStateStore(f.app).read(f.role), "sample-method"))
            }
            val state = InternalStateStore(f.app).read(f.role)
            assertEquals(proposal!!.getJSONObject("record").toString(), InnerStructure.record(state, "sample-method")!!.getJSONObject("content").toString())
            assertEquals("paused", board.get(taskId).getString("status"))
            assertTrue(board.get(taskId).toString().contains("已核对样本"))
            assertEquals(2, calculations.distinct().size)
            calculations.forEachIndexed { index, id ->
                val action = SubjectActions.read(f.app, f.role, f.chat, id, 0).getJSONObject("item")
                assertEquals("completed", action.getString("status"))
                assertEquals(if (index == 0) "decision" else "experience", action.getJSONObject("declaration")
                    .getJSONArray("intention_refs").getJSONObject(0).getString("kind"))
            }
            assertNull(ExplorationStore(f.app).next(System.currentTimeMillis() + 30L * 86_400_000))
            val actions = SubjectActions.read(f.app, f.role, f.chat, null, 0).getJSONArray("items")
            assertTrue(actions.length() > 0)
            assertEquals(0, f.executions) // The fake sleep executor was never used.
            assertTrue(f.uncaught.toString(), f.uncaught.isEmpty())
            assertFalse(delegate.isChatLoading(f.chat))
        } finally { f.close() }
    }
}
