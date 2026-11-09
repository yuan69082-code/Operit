package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.InternalStateReadResultData
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ToolResult
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

/** Real submission, request selection, native read receipt and round-end adoption adapters.
 * Candidate text is a simulated model response; no assertion of real model understanding. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EstablishedContentEntryPointsTest {
    private lateinit var context: Context
    private lateinit var states: InternalStateStore
    private lateinit var role: String
    private lateinit var chat: String
    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "已有助手"))
        val history = ChatHistory(title = "承接", messages = emptyList(), characterCardId = role)
        ChatHistoryManager.getInstance(context).saveChatHistory(history)
        chat = history.id
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit().putBoolean("internal_state:$role", true).commit()
        states = InternalStateStore(context)
    }
    private fun preference(id: String) = JSONObject().put("id", id).put("family", "preference").put("kind", id)
        .put("description", "已有的表达偏好").put("status", "active").put("aspect", "语言方式")
        .put("orientation", "uncertain").put("horizon", "contextual").put("ownership", "self")
        .put("context", "有条件").put("exceptions", "不代表每次都这样表达")
    private fun report(f: InternalStateFrame, actions: JSONArray) = JSONObject().put("frame_id", f.id)
        .put("updates", JSONArray()).put("create", JSONArray()).put("inner", actions)
    private suspend fun adoptPage(): Int {
        val s = InnerStructureContext.prepare(context, role, chat, true, input = "继续理解此前提交的内容")
        val body = states.inspectCategory(role, chat, "inner_pending", null, 0)
        val rows = body.getJSONArray("items")
        if (rows.length() == 0) return 0
        val result = ToolResult("companion_internal_state", true, InternalStateReadResultData(body.toString(), role, chat, "inner_pending", true))
        val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "继续")), s) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, body.toString(), "companion_internal_state")
        val f = requireNotNull(CurrentRequestContext.internalStateFrame(SubjectToolMaterials.attach(turns, System.currentTimeMillis(), listOf(result))))
        val actions = JSONArray()
        for (i in 0 until rows.length()) actions.put(JSONObject().put("op", "adopt").put("id", rows.getJSONObject(i).getString("id")).put("reason", "本次采用"))
        InnerStructureContext.submit(context, f, report(f, actions))
        return rows.length()
    }
    @Test fun moreThanEightPerRoundAndMoreThan32PendingAnd128RecordsPersistAndContinue(): Unit = runBlocking {
        states.openEstablishedWindow(role)
        repeat(11) { batch ->
            val f = requireNotNull(InnerStructureContext.prepare(context, role, chat, true, input = "恢复已有偏好").internalStateFrame)
            val actions = JSONArray()
            repeat(12) { index ->
                val id = "p${batch * 12 + index}"
                actions.put(JSONObject().put("op", "propose").put("id", id).put("record", preference(id))
                    .put("origin", EstablishedContent.ORIGIN).put("evidence", JSONArray()).put("expected_version", 0).put("reason", "恢复已有内容"))
            }
            InnerStructureContext.submit(context, f, report(f, actions))
        }
        assertEquals(132L, InnerRows(states.read(role), true).count())
        var adopted = 0
        while (adopted < 132) { val count = adoptPage(); assertTrue(count > 0); adopted += count }
        val restarted = InternalStateStore(context).read(role)
        assertEquals(132L, InnerRows(restarted, false).count())
        assertEquals(0L, InnerRows(restarted, true).count())
        assertFalse(restarted.getJSONObject("inner_structure").has("records"))
        assertTrue(states.inspectCategory(role, chat, "inner", "p131", 0).getJSONObject("item").getJSONObject("content").has("exceptions"))
        val next = InnerStructureContext.prepare(context, role, chat, true, input = "继续")
        assertTrue(next.state.contains("record_count\":132"))
    }
    @Test fun submissionReplaysWithoutResetAndCannotAdoptUnreadCandidate(): Unit = runBlocking {
        val opened = states.openEstablishedWindow(role)
        val before = requireNotNull(InnerStructureContext.prepare(context, role, chat, true, input = "之前的轮次").internalStateFrame)
        val receipt = states.submitEstablishedContent(role, chat, "file-a:1", preference("old"))
        assertTrue(states.submitEstablishedContent(role, chat, "file-a:1", preference("old")).getBoolean("replayed"))
        val id = receipt.getString("candidate_id")
        val pending = requireNotNull(InnerStructure.candidate(states.read(role), id))
        assertTrue(pending.getJSONObject("bindings").getJSONObject("declaration:$id").isNull("formed_at"))
        assertEquals(opened.getLong("closes_at"), states.openEstablishedWindow(role).getLong("closes_at"))
        try { InnerStructureContext.submit(context, before, report(before, JSONArray().put(JSONObject().put("op", "adopt").put("id", id).put("reason", "不能冒认已读")))); fail("stale/unread") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, adoptPage())
        assertTrue(states.submitEstablishedContent(role, chat, "file-a:1", preference("old")).getBoolean("replayed"))
        assertEquals(1L, InnerRows(InternalStateStore(context).read(role), false).count())
    }
    @Test fun deadlineOnlyClosesSubmissionsNotSavedCandidates() {
        val data = JSONObject()
        val opened = EstablishedContent.open(data, "owner", 100)
        EstablishedContent.declaration(data, "owner", "p", "value", 101, null)
        assertEquals(100L + EstablishedContent.DURATION, EstablishedContent.open(data, "owner", 200).getLong("closes_at"))
        assertThrows(IllegalArgumentException::class.java) {
            EstablishedContent.declaration(data, "owner", "late", "relation", opened.getLong("closes_at"), null)
        }
        assertEquals(1L, EstablishedContent.status(JSONObject(data.toString()), 102).getLong("submitted"))
        assertEquals("closed", EstablishedContent.status(data, opened.getLong("closes_at")).getString("status"))
    }
}
