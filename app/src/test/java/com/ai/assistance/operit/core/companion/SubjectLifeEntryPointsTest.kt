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
        repeat(12) { assertTrue(life.defer(role, chat, "book-$it", "study", "第$it条批注")) }
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
        InternalStateStore(context).materialBudget(role, chat, 16384)
        adopt(rest(JSONObject().put("steps", 2).put("tone", "复杂恐惧").put("form", "跳跃")))
        val work = requireNotNull(SubjectLife(context).next(role, chat, System.currentTimeMillis()))
        assertTrue(work.dream)
        val requests = mutableListOf<Pair<String, JSONObject>>()
        ParticipatoryDreams(context).run(work, ParticipatoryDreams.Model { system, raw, partial ->
            val input = JSONObject(raw)
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
}
