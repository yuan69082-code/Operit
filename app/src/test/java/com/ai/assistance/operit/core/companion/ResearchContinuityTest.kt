package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.core.tasks.TaskRecords
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Five controlled model turns through real reducers, selection, task and scheduling policies.
 * Archive and model are test doubles. This does NOT prove a provider will choose these outputs. */
class ResearchContinuityTest {
    @Test fun interruptedResearchKeepsProgressAndWaitsForExplicitResume() {
        val plans = JSONObject()
        ExplorationPolicy.configure(plans, true, setOf("reading"), 8, 1, continuous = true)
        val tasks = JSONArray()
        val task = TaskRecords.save(tasks, "owner", null, JSONObject().put("title", "星空研究"), setOf("owner"), 2)
        ExplorationPolicy.schedule(plans, 15, "继续事项 ${task.getString("id")}", 3)
        val due = plans.getLong("at")
        assertTrue(ExplorationPolicy.claim(plans, due, "day", "before-message", "chat"))
        TaskRecords.comment(task, "owner", "已经校准，后续不要重复校准", due + 1)
        ExplorationPolicy.finish(plans, "before-message", "interrupted", due + 2, continuous = true)
        val restoredPlan = JSONObject(plans.toString())
        val restoredTask = JSONArray(tasks.toString()).getJSONObject(0)
        assertFalse(ExplorationPolicy.due(restoredPlan, due + 999999, "day"))
        assertTrue(ExplorationPolicy.canResume(restoredPlan))
        ExplorationPolicy.resume(restoredPlan)
        assertTrue(ExplorationPolicy.claim(restoredPlan, due + 999999, "day", "resumed", "new-chat"))
        assertFalse(ExplorationPolicy.claim(restoredPlan, due + 999999, "day", "duplicate", "new-chat"))
        assertTrue(TaskRecords.material(restoredTask).getJSONObject("latest_note").getString("text").contains("已经校准"))
        assertTrue(restoredPlan.getString("purpose").contains(restoredTask.getString("id")))
    }

    @Test fun chosenInterestSchedulesResumesLearnsTransfersAndCanStop() {
        val archive = RecallTestHistory()
        val base = JSONObject()
        InnerStructure.root(base) // Legacy in-memory records; normal candidate/adoption rules still run.
        var state = InternalStateDocument(base, archive)
        InnerFoundations.install(state, 1)
        val plans = JSONObject()
        ExplorationPolicy.configure(plans, true, setOf("reading"), 8, 1, continuous = true)
        val tasks = JSONArray()
        var now = 10L
        fun frame(round: Int, input: String, materials: List<JSONObject> = emptyList()): InternalStateFrame {
            val event = "input:$round"
            InternalStateEngine.event(state, event, if (round == 1) "user_message" else "wake", "chat", input, now, applyTriggers = false)
            return InternalStateFrame("frame:$round", "owner", "chat", InternalStateEngine.semanticRevision(state), now,
                setOf(event), emptyMap(), state.getLong("sequence"), innerVersions = InnerStructure.versions(state),
                innerPending = InnerStructure.pendingBindings(state), innerObjects = setOf("user"),
                foundationVersions = InnerFoundations.currentVersions(state),
                interactionMembers = InnerFoundations.currentVersions(state).mapKeys { "foundation:${it.key}" },
                materialSources = materials.associate { it.getString("id") to it.toString() })
        }
        fun submit(f: InternalStateFrame, fields: JSONObject) {
            val report = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
            fields.keys().forEach { report.put(it, fields.get(it)) }
            InternalStateReports.apply(state, f, report, now)
            archive.commit(state)
        }
        fun decision(version: Int, status: String, meaning: String, source: String) = JSONObject()
            .put("id", "research").put("expected_version", version).put("status", status)
            .put("meaning", meaning).put("evidence", JSONArray().put(source))
        fun schedule() = ExplorationPolicy.schedule(plans, 15, "星空观测误差研究，核对研究进度与实际回执", now)
        fun start(ticket: String) {
            now = plans.getLong("at") + 1
            assertTrue(ExplorationPolicy.claim(plans, now, "day", ticket, "chat"))
            assertFalse(ExplorationPolicy.claim(plans, now, "day", "$ticket-duplicate", "chat"))
        }
        fun finish(ticket: String) = ExplorationPolicy.finish(plans, ticket, "completed", now, continuous = true)

        // 1: A model chooses to retain an interest. Decision adoption alone cannot schedule it.
        val f1 = frame(1, "星空观测为什么存在误差")
        submit(f1, JSONObject().put("decisions", JSONArray().put(decision(0, "chosen", "我想保留星空观测误差研究", "input:1"))))
        assertFalse(plans.has("at"))
        val task = TaskRecords.save(tasks, "owner", null, JSONObject().put("title", "星空观测误差研究")
            .put("origin", "input:1").put("goal", "比较统计方法").put("next", "先核对样本"), setOf("owner"), now)
        val taskId = TaskRecords.receipt(task).getString("id")
        assertFalse(task.has("dispatch"))
        schedule() // Separate explicit model tool choice, not a reducer side effect.

        // 2: No user message. A due subject-authored plan is claimed once and reads its old decision.
        start("turn2")
        state = InternalStateDocument(JSONObject(state.toString()), archive) // Process/document recreation.
        val oldDecision = ActionUnderstanding.materials(state, plans.getString("purpose"), "chat")
        assertTrue(oldDecision.any { it.getString("text").contains("保留星空") })
        val f2 = frame(2, plans.getString("purpose"), oldDecision)
        submit(f2, JSONObject().put("decisions", JSONArray().put(decision(1, "chosen", "先比较平均值与异常点的影响", "input:2"))))
        TaskRecords.comment(task, "owner", "已完成取样，下一次只比较方法", now)
        schedule(); finish("turn2")

        // 3: The fixture executes a calculation and supplies its result, not a prewritten success flag.
        start("turn3")
        assertTrue(TaskRecords.material(task).getJSONObject("latest_note").getString("text").contains("已完成取样"))
        val samples = listOf(1.0, 2.0, 3.0, 100.0)
        val mean = samples.average()
        val material = JSONObject().put("id", "material:action:calculation:3").put("type", "actual_operation")
            .put("owner", "owner").put("author", "host_executor").put("at", now).put("status", "retrieved")
            .put("not_new_occurrence", true).put("text", "星空样本实际均值=$mean，样本=$samples")
            .put("locator", JSONObject().put("tool", "companion_internal_state get section=action")
                .put("action_id", "calculation").put("version", 3))
        now += 2
        val f3 = frame(3, plans.getString("purpose"), listOf(material))
        val feedback = JSONObject().put("id", "method-review").put("expected_version", 0)
            .put("action_id", "calculation").put("result_source", material.getString("id"))
            .put("meaning", "星空样本均值被异常点拉高；下次比较中位数，并保留原始样本")
            .put("evidence", JSONArray().put(material.getString("id")))
        val method = JSONObject().put("id", "robust-method").put("family", "self_knowledge")
            .put("kind", "研究方法").put("description", "星空观测有异常点时比较中位数，并检查原始样本")
            .put("status", "active")
        val proposal = JSONObject().put("op", "propose").put("id", "method-candidate").put("expected_version", 0)
            .put("origin", "subject_development").put("record", method).put("reason", "实际计算与原预期有差异")
            .put("evidence", JSONArray().put(material.getString("id")))
        val members = JSONArray(listOf("exploration", "rest").map { key -> JSONObject()
            .put("kind", "foundation").put("id", "foundation:drive:$key")
            .put("version", f3.interactionMembers.getValue("foundation:foundation:drive:$key")) })
        val union = JSONObject().put("id", "research-pacing").put("expected_version", 0).put("members", members)
            .put("objects", JSONArray().put("星空研究")).put("evidence", JSONArray().put(material.getString("id")))
            .put("interpretation", "星空探索兴趣与休息需要可以通过分段研究协调")
            .put("decision", "下一次先核对进度再决定投入").put("status", "active").put("effects", JSONArray())
        submit(f3, JSONObject().put("action_feedback", JSONArray().put(feedback)).put("inner", JSONArray().put(proposal))
            .put("unions", JSONArray().put(union)))
        assertNull(InnerStructure.record(state, "robust-method")) // A proposal is not already adopted.
        TaskRecords.comment(task, "owner", "计算已完成，均值=$mean；准备比较中位数", now)
        schedule(); finish("turn3")

        // 4: Retrieval affects a separate fixture problem, and adoption occurs in a later frame.
        start("turn4")
        val recalled = ActionUnderstanding.materials(state, "星空样本中位数", "chat")
        val understanding = recalled.single { it.getString("type") == "adopted_feedback" }.getString("text")
        val secondSamples = listOf(10.0, 11.0, 12.0, 400.0)
        // Controlled model stand-in: it can only choose the new method if the delivered material contains it.
        val answer = if (understanding.contains("比较中位数")) (secondSamples[1] + secondSamples[2]) / 2 else secondSamples.average()
        assertEquals(11.5, answer, 0.0)
        val relation = InnerInteractions.material(state, chat = "chat", at = now, input = "星空研究投入")
        assertEquals("research-pacing", relation.getJSONArray("items").getJSONObject(0).getString("id"))
        assertTrue(relation.getJSONArray("items").getJSONObject(0).has("current_member_versions"))
        val f4 = frame(4, "对另一个星空样本试用新方法", recalled)
        val pending = InnerStructure.pending(state).single()
        val adopt = JSONObject().put("op", "adopt").put("id", pending.getString("id")).put("reason", "采用可修正的方法认识")
            .put("development", JSONObject().put("account", "实际均值暴露了异常点问题")
                .put("before", pending.get("before")).put("after", pending.getJSONObject("record"))
                .put("anchors", JSONArray().put(JSONObject().put("ref", material.getString("id"))
                    .put("quote", "实际均值=26.5").put("interpretation", "异常点值得单独检查"))))
        val rest = "foundation:drive:rest"
        val restChange = JSONObject().put("id", rest).put("expected_version", f4.foundationVersions.getValue(rest))
            .put("value", 0.7).put("reason", "这次比较已足够，想分段投入")
            .put("evidence", JSONArray().put("input:4"))
        submit(f4, JSONObject().put("inner", JSONArray().put(adopt)).put("foundation", JSONArray().put(restChange)))
        schedule(); finish("turn4")

        // 5: A new independent activity reads adopted method and can stop the direction.
        start("turn5")
        val knowledge = InnerStructure.material(state, "星空异常点", 0)
        // Provenance can make a record large: the normal path then delivers a source-linked
        // fragment, not a second shortened authority. Assert actual content, not the catalogue.
        assertTrue((knowledge.getJSONArray("identity").toString() + knowledge.getJSONArray("record_fragments"))
            .contains("星空观测有异常点时比较中位数"))
        val currentRelation = InnerInteractions.material(state, chat = "chat", at = now, input = "星空研究投入")
            .getJSONArray("items").getJSONObject(0)
        assertEquals(f4.foundationVersions.getValue(rest) + 1,
            currentRelation.getJSONObject("current_member_versions").getInt("foundation:$rest"))
        assertEquals(1, currentRelation.getInt("version")) // Reading never rewrites the old union.
        val f5 = frame(5, "重新评价研究方向")
        val ended = JSONObject(union.toString()).put("expected_version", 1).put("status", "ended")
            .put("interpretation", "现在想暂停星空研究，这个投入安排已不再适用").put("decision", "保留结果，暂缓研究")
            .put("evidence", JSONArray().put("input:5"))
        ended.getJSONArray("members").getJSONObject(1).put("version", f5.foundationVersions.getValue(rest))
        submit(f5, JSONObject().put("decisions", JSONArray().put(decision(2, "deferred", "星空研究先暂停，保留方法和实际进度", "input:5")))
            .put("unions", JSONArray().put(ended)))
        ExplorationPolicy.selfPause(plans)
        val paused = TaskRecords.save(tasks, "owner", taskId, JSONObject().put("status", "paused"), setOf("owner"), now)
        finish("turn5")
        assertFalse(ExplorationPolicy.due(plans, now + 99999999, "later"))
        assertEquals("paused", paused.getString("status"))
        assertTrue(TaskRecords.material(paused).getJSONObject("latest_note").getString("text").contains("26.5"))
        assertNotNull(InnerStructure.record(state, "robust-method"))
        assertEquals("deferred", CategoryEvents.row(state, "decision", "research")!!.getJSONObject("content").getString("status"))
        assertEquals("ended", CategoryEvents.row(state, "union", "research-pacing")!!.getJSONObject("content").getString("status"))
    }
}
