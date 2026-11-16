package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Simulated model responses; actual report parsing, request preparation and archive are used. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectLifeEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "生活测试", characterSetting = "初始参考"))
        InternalStateStore(context).configure(role, true)
        val c = ChatHistory(title = "私聊", messages = emptyList(), characterCardId = role)
        ChatHistoryManager.getInstance(context).saveChatHistory(c)
        chat = c.id
        AssistantWindowRouter.selected(context, c)
    }
    private suspend fun adopt(fields: JSONObject) {
        val request = InnerStructureContext.prepare(context, role, chat, true, input = "当前生活选择")
        val f = requireNotNull(request.internalStateFrame)
        commit(f, fields)
    }
    private suspend fun commit(f: InternalStateFrame, fields: JSONObject) {
        val report = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
        fields.keys().forEach { report.put(it, fields.get(it)) }
        val parser = InternalStateReportStream()
        parser.append("<operit_state_report>$report</operit_state_report>正文")
        parser.finish()
        InnerStructureContext.submit(context, f, requireNotNull(parser.report))
    }
    private fun rest(plan: JSONObject? = null): JSONObject = JSONObject().put("rest", JSONObject()
        .put("expected_version", 0).put("state", "resting").put("mode", "sleep")
        .put("until", System.currentTimeMillis() + 3_600_000).put("meaning", "暂停一会儿，之后再接上")
        .put("evidence", JSONArray()).apply { if (plan != null) put("dream_plan", plan) })

    @Test fun sleepBuffersEventsAndOnlyDispatchesTheChosenEndOnce() = runBlocking {
        adopt(rest())
        val life = SubjectLife(context)
        repeat(12) { assertTrue(life.defer(role, chat, "book-$it", "study", "第${it}条批注")) }
        assertTrue(life.defer(role, chat, "book-0", "study", "第0条批注"))
        assertEquals(12L, CompanionStore(context).activityCount(role, "life_event", "pending"))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        val due = life.rest(role)!!.getJSONObject("content").getLong("until")
        assertNotNull(life.next(role, chat, due))
        assertNull(SubjectLife(context).next(role, chat, due + 1))
        assertEquals(12L, CompanionStore(context).activityCount(role, "life_event", "pending"))
        assertEquals("resting", life.rest(role)!!.getJSONObject("content").getString("state"))
    }
    @Test fun acceptedMessagesSurviveReopenAndClaimIsIdempotent() {
        val inbox = SubjectMessageInbox(context)
        val accepted = inbox.accept(role, chat, "input-one", "自己的原始消息", emptyList())
        assertEquals(accepted.getLong("timestamp"), SubjectMessageInbox(context).accept(role, chat, "input-one", "自己的原始消息", emptyList()).getLong("timestamp"))
        assertTrue(inbox.claim(role, "input-one"))
        assertFalse(inbox.claim(role, "input-one"))
        SubjectLife(context).recover(role)
        assertEquals("unknown", inbox.read(role, "input-one").getString("status"))
        assertFalse(inbox.claim(role, "input-one"))
    }
    @Test fun dreamAlternatesEnvironmentAndSubjectWithoutRealToolsAndReachesLaterRequest() = runBlocking {
        adopt(rest(JSONObject().put("steps", 2).put("tone", "复杂恐惧").put("form", "跳跃")))
        val work = requireNotNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
        assertTrue(work.dream)
        val requests = mutableListOf<Pair<String, JSONObject>>()
        ParticipatoryDreams(context).run(work, ParticipatoryDreams.Model { system, raw, partial ->
            val input = JSONObject(raw)
            assertTrue("Dream input must share the default material budget", SubjectMaterialBudget.estimate(raw) <= 2048)
            requests += system to input
            val text = if (input.has("subject_material")) "我先走近看看，同时仍有些犹豫。" else "眼前是一道没见过的门，里面传来不确定的声音。"
            partial(text)
            text
        })
        assertEquals(4, requests.size)
        assertFalse(requests[0].second.has("subject_material"))
        assertTrue(requests[1].second.has("subject_material"))
        assertEquals("我先走近看看，同时仍有些犹豫。", requests[2].second.getString("previous_fragment"))
        val stored = CompanionStore(context).activityRead(role, "dream_process", work.id)!!
        assertEquals("completed", stored.getString("status"))
        assertEquals("DREAM_NOT_REALITY", stored.getString("reality"))
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val later = InnerStructureContext.prepare(context, role, chat, true, input = "醒来后想接上之前的事")
        assertTrue(later.state.contains("dream_not_reality"))
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "action", null, 0).getJSONArray("items").length())
    }
    @Test fun failedDreamKeepsPartialAndDoesNotAutomaticallyRunAgain() = runBlocking {
        adopt(rest(JSONObject().put("steps", 1)))
        val work = requireNotNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
        try {
            ParticipatoryDreams(context).run(work, ParticipatoryDreams.Model { _, _, save -> save("记得窗边的一道光。"); error("network unavailable") })
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals("记得窗边的一道光。", CompanionStore(context).activityRead(role, "dream_step", "${work.id}:0")!!.getString("text"))
        assertNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
    }

    @Test fun earlierFailedInputsDoNotStarveNewInputAndCompletedInputDoesNotWakeTwice() = runBlocking {
        adopt(rest())
        val inbox = SubjectMessageInbox(context)
        repeat(12) { i ->
            inbox.accept(role, chat, "old-$i", "旧输入$i", emptyList())
            inbox.finish(role, "old-$i", "unknown")
        }
        val input = inbox.accept(role, chat, "next", "新输入", emptyList())
        SubjectLife(context).defer(role, chat, "input:next", "user_message", "新输入")
        assertEquals("next", inbox.next(role)!!.getString("id"))
        assertTrue(inbox.claim(role, "next"))
        inbox.finish(role, "next", "completed")
        assertFalse(inbox.claim(role, "next"))
        assertEquals(input.getLong("timestamp"), inbox.accept(role, chat, "next", "新输入", emptyList()).getLong("timestamp"))
        assertEquals("handled", CompanionStore(context).activityRead(role, "life_event", "input:next")!!.getString("status"))
        assertNull(inbox.next(role))
    }

    @Test fun experienceAndDirectionAreReadLaterAndCanSupportRealActionDeclaration() = runBlocking {
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val request = InnerStructureContext.prepare(context, role, chat, true, input = "我刚试的资料整理只完成了第一段，其他部分尚未完成。")
        val frame = request.internalStateFrame!!
        val source = frame.eventIds.last()
        fun content(kind: String, id: String, text: String) = JSONObject().put("kind", kind).put("id", id)
            .put("expected_version", 0).put("text", text).put("scope", "这次资料整理")
            .put("uncertainty", "下一次是否适用尚待观察").put("status", "considering").put("evidence", JSONArray().put(source))
        commit(frame, JSONObject().put("life", JSONArray()
            .put(content("experience", "organize", "实际只完成了一部分；先核对已有结果，不能当成全部成功。"))
            .put(content("direction", "project", "我仍想把资料整理清楚，也可能改变做法。")
                .put("purpose", "保留自己的发现").put("goal", "可重新查到出处")
                .put("resolve", "投入一段有限时间，再看结果").put("ambition", "逐渐形成自己的整理方法"))))
        val later = InnerStructureContext.prepare(context, role, chat, true, input = "想继续整理时之前有哪些实际结果？")
        val next = later.internalStateFrame!!
        val material = next.materialSources.values.map(::JSONObject).first { it.optString("type") == "subject_direction" }
        assertTrue(later.state.contains("保留自己的发现"))
        SubjectActions.validateDeclaration(JSONObject().put("intention_refs", JSONArray().put(JSONObject()
            .put("kind", "direction").put("id", "project").put("version", material.getJSONObject("locator").getInt("version"))))
            .put("evidence", JSONArray().put(material.getString("id"))), next)
        val stored = InternalStateStore(context).inspectCategory(role, chat, "experience", "organize", 0).getJSONObject("item")
        assertTrue(stored.getJSONObject("content").getBoolean("not_a_plan"))
        assertTrue(stored.getJSONObject("content").getJSONObject("bindings").has(source))
        assertFalse(CompanionStore(context).read("schedule:$chat").has("at"))
    }

    @Test fun restDeferralKeepsOriginalScheduleAndActivityBudgetAcrossResume() {
        val schedule = JSONObject().put("at", 100L).put("purpose", "自己的资料整理").put("owner", "ai")
        CompanionSchedule.claim(schedule, "ticket", 200)
        CompanionSchedule.defer(schedule, "ticket")
        assertEquals(100L, schedule.getLong("at"))
        assertEquals("deferred", CompanionSchedule.state(schedule))
        val ref = ActivityNotes.Ref(role, chat, "same-activity")
        val life = SubjectLife(context)
        life.checkpoint(ref, "原活动", "executing", exploration = true)
        life.checkpoint(ref, "原活动", "paused", toolsUsed = 7)
        life.chooseActivity(role, ref.id, "continue", "先回复，随后接着原处")
        val work = life.next(role, chat, System.currentTimeMillis())!!
        assertEquals(ref.id, work.activity)
        assertTrue(life.isExploration(role, work.activity))
        val budget = CompanionToolBudget()
        budget.reset(life.toolsUsed(role, work.activity))
        repeat(5) { assertTrue(budget.admit("read_file", null)) }
        assertFalse(budget.admit("write_file", null))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
    }

    @Test fun explicitWakeIsDistinctFromOrdinaryMessagesAndDoesNotPretendAlreadyAwake() = runBlocking {
        adopt(rest())
        val life = SubjectLife(context)
        repeat(15) { life.defer(role, chat, "quiet-$it", "user_message", "普通输入$it") }
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        val request = life.requestWake(role, chat)
        assertEquals(request.getString("id"), life.requestWake(role, chat).getString("id"))
        val work = life.next(role, chat, System.currentTimeMillis())!!
        assertTrue(work.text.contains("用户在首页明确请求"))
        assertNull(life.next(role, chat, System.currentTimeMillis()))
        assertEquals("resting", life.rest(role)!!.getJSONObject("content").getString("state"))
    }
}
