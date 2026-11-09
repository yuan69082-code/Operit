package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Production state/message/tool adapters with real AtomicFile, SQLite and chat metadata.
 * Does not claim a live model, microphone or foreground UI was exercised. */
@RunWith(OperitStorageTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class InternalStateEntryPointsTest {
    private lateinit var context: Context
    private lateinit var states: InternalStateStore
    private lateinit var role: String
    private lateinit var other: String
    private lateinit var one: String
    private lateinit var two: String
    private lateinit var foreign: String

    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = UUID.randomUUID().toString(); other = UUID.randomUUID().toString()
        // Existing user setting, not a new selection/identity mechanism.
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit()
            .putBoolean("internal_state:$role", true).putBoolean("internal_state:$other", true).commit()
        states = InternalStateStore(context)
        suspend fun chat(owner: String): String {
            val history = ChatHistory(title = "状态入口测试", messages = emptyList(), characterCardId = owner)
            ChatHistoryManager.getInstance(context).saveChatHistory(history)
            return history.id
        }
        one = chat(role); two = chat(role); foreign = chat(other)
    }

    private fun definition(id: String, scope: String) = JSONObject()
        .put("id", id).put("name", id).put("description", "测试明确输入")
        .put("kind", "emotion").put("min", 0).put("max", 100).put("baseline", 25)
        .put("scope", scope).put("private", false).put("share", false).put("participates", true)
        .put("effects", JSONArray()).put("time_rules", JSONObject()
            .put("increase", JSONObject().put("condition", "always").put("reason", "测试暂停").put("interval_minutes", 1).put("amount", 0))
            .put("decrease", JSONObject().put("condition", "always").put("reason", "测试暂停").put("interval_minutes", 1).put("amount", 0)).put("floor", 0))
    private fun frame(chat: String = one) = requireNotNull(states.prepare(role, chat, true, currentInput = "收到新消息").internalStateFrame)
    private fun report(frame: InternalStateFrame, updates: JSONArray = JSONArray(), creates: JSONArray = JSONArray()) =
        JSONObject().put("frame_id", frame.id).put("updates", updates).put("create", creates)
    private fun seed() {
        val frame = frame()
        val creates = JSONArray(listOf("global", "session").map {
            JSONObject().put("definition", definition(it, it)).put("value", 63).put("reason", "本轮明确报告")
        })
        states.submit(frame, report(frame, creates = creates))
    }
    private fun value(chat: String, id: String): Double = InternalStateEngine.snapshot(states.snapshot(role, chat, true), chat, System.currentTimeMillis())
        .single { it.getString("id") == id }.getDouble("value")

    @Test fun normalPreparationAndCommitContinueAcrossWindowsWithoutMergingSessions(): Unit = runBlocking {
        assertFalse(states.requireRecipient(role, one))
        seed()
        assertEquals(63.0, value(two, "global"), 0.0)
        assertEquals(25.0, value(two, "session"), 0.0)
        assertEquals(63.0, value(one, "session"), 0.0)
        assertTrue(InternalStateEngine.definitions(states.read(other)).isEmpty())
        val restarted = InternalStateStore(context)
        assertEquals(SubjectStateBoundary.revision(states.read(role)), SubjectStateBoundary.revision(restarted.read(role)))
        assertEquals(63.0, InternalStateEngine.snapshot(restarted.read(role), one, System.currentTimeMillis()).first().getDouble("value"), 0.0)
        // A host-populated cache must not bypass ownership on a later assistant read.
        AttentionGear.state(context, null, role)
        withContext(AssistantScope.executionContext(other)) {
            assertThrows(IllegalArgumentException::class.java) { states.read(role) }
            assertThrows(IllegalArgumentException::class.java) { states.event(role, one, "bad", "wake", "不得进入") }
            assertThrows(IllegalArgumentException::class.java) { AttentionGear.state(context, null, role) }
        }
    }

    @Test fun messageToolAndBackgroundFactsHaveCorrectSubjectAndToolRetryIsIdempotent() = runBlocking {
        val message = ChatMessage(sender = "user", content = "明确来自这个窗口")
        withContext(Dispatchers.IO) {
            states.message(one, message)
            states.message(one, message)
            val tool = AITool("companion_reading", listOf(ToolParameter("action", "get")))
            InternalStateStore.noteTool(context, role, one, tool, true, "host-call-1")
            val version = SubjectStateBoundary.revision(states.read(role))
            InternalStateStore.noteTool(context, role, one, tool, true, "host-call-1")
            assertEquals(version, SubjectStateBoundary.revision(states.read(role)))
            InternalStateStore.noteEvent(context, role, two, "background-ticket", "wake", "已有后台事件")
        }
        val events = InternalStateEngine.rows(states.read(role), "events")
        assertEquals(1, events.count { it.optString("source") == "user_message" })
        assertEquals(1, events.count { it.getString("id") == "tool:host-call-1" })
        assertEquals("user", events.first { it.optString("source") == "user_message" }.getString("author"))
        assertTrue(events.filter { it.has("recipient") }.all { it.getString("recipient") == role })
        assertTrue(InternalStateEngine.rows(states.read(other), "events").isEmpty())
        try { states.tool(role, foreign, "read_file", null, null, true, "wrong-window"); fail("foreign chat") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun duplicateAndStaleReportsCannotOverwriteAcceptedState() {
        seed()
        val old = frame()
        val current = frame(two)
        val accepted = report(current, JSONArray().put(JSONObject().put("id", "global").put("value", 79).put("reason", "较新报告")))
        val receipt = states.submit(current, accepted)
        val version = SubjectStateBoundary.revision(states.read(role))
        assertEquals(receipt.toString(), states.submit(current, accepted).toString())
        assertEquals(version, SubjectStateBoundary.revision(states.read(role)))
        assertThrows(InternalStateReportRejected::class.java) { states.submit(old, report(old)) }
        assertEquals(79.0, value(one, "global"), 0.0)
    }

    @Test fun concurrentBackgroundTransactionsKeepEveryAcceptedFact() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 24).map { i -> pool.submit {
                InternalStateStore(context).event(role, if (i % 2 == 0) one else two, "concurrent:$i", "wake", "后台事实 $i")
            } }
            writes.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(24, InternalStateEngine.rows(states.read(role), "events").count { it.getString("id").startsWith("concurrent:") })
            assertEquals(24L, SubjectStateBoundary.revision(states.read(role)))
        } finally { pool.shutdownNow() }
    }

    @Test fun archivedReceiptRetryDoesNotRestoreOrReapplyOldState() {
        seed()
        val request = frame()
        val payload = report(request, JSONArray().put(JSONObject().put("id", "global").put("value", 71).put("reason", "真实回报")))
        val receipt = states.submit(request, payload)
        val row = InternalStateEngine.rows(states.read(role), "semantic_reports").single { it.getString("frame_id") == request.id }
        // Simulate the existing archive boundary, retaining the actual production receipt.
        InternalStateArchive(java.io.File(context.filesDir, "companion/internal-state-history.sqlite"), "internal-state:$role").use {
            it.put("semantic_reports", row); it.commit()
        }
        CompanionStore(context).updateSubject(role, "internal-state", "test_archive_fixture") { data ->
            data.put("semantic_reports", JSONArray(InternalStateEngine.rows(data, "semantic_reports").filter { it.getString("frame_id") != request.id }))
        }
        val version = SubjectStateBoundary.revision(states.read(role))
        assertEquals(receipt.toString(), states.submit(request, payload).toString())
        assertEquals(version, SubjectStateBoundary.revision(states.read(role)))
        assertFalse(InternalStateEngine.rows(states.read(role), "semantic_reports").any { it.getString("frame_id") == request.id })
        assertThrows(InternalStateReportRejected::class.java) { states.submit(request, report(request)) }
        assertEquals(71.0, value(one, "global"), 0.0)
    }

    @Test fun groupMessageAuthorsDoNotBecomeAnotherAssistantsExperience() = runBlocking {
        // Existing group history remains attributable even when the group card is unavailable.
        val group = ChatHistory(title = "已有群历史", messages = emptyList(), characterGroupId = "historical-group")
        ChatHistoryManager.getInstance(context).saveChatHistory(group)
        val at = System.currentTimeMillis()
        val a = ChatMessage(sender = "ai", content = "A的感受", roleName = "同名助手", timestamp = at)
        val b = ChatMessage(sender = "ai", content = "B的感受", roleName = "同名助手", timestamp = at + 1)
        val unknown = ChatMessage(sender = "ai", content = "没有作者凭据", roleName = "同名助手", timestamp = at + 2)
        com.ai.assistance.operit.core.group.GroupProvenance.record(context, group.id, a, role)
        com.ai.assistance.operit.core.group.GroupProvenance.record(context, group.id, b, other)
        states.message(group.id, a); states.message(group.id, b); states.message(group.id, unknown)
        val own = InternalStateEngine.rows(states.read(role), "events")
        val peers = InternalStateEngine.rows(states.read(other), "events")
        assertEquals(1, own.size); assertEquals(1, peers.size)
        assertEquals("A的感受", own.single().getString("detail"))
        assertEquals(role, own.single().getString("author"))
        assertEquals("B的感受", peers.single().getString("detail"))
        assertEquals(other, peers.single().getString("author"))
    }

    @Test fun userEditKeepsItsSourceAndInvalidatesAnEarlierFrame() {
        seed()
        val old = frame()
        states.updateFromUser(role, one, "global", 1, old.revision,
            JSONObject().put("value", 12), "用户明确调整", false)
        assertThrows(InternalStateReportRejected::class.java) { states.submit(old, report(old)) }
        val saved = states.read(role)
        val state = InternalStateEngine.rows(saved, "instances").single { it.getString("dimension") == "global" }
        assertEquals(12.0, state.getDouble("value"), 0.0)
        assertEquals("user_edit", state.getString("experience_source"))
        assertEquals("user_edit", saved.getJSONObject(SubjectStateBoundary.FIELD).getString("last_source"))
    }
}
