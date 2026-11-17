package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.core.tasks.TaskBoard
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ToolParameter
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

/** Normal tool/report/context adapters with controlled model output, never direct DB writes. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ResearchEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "研究主体"))
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit()
            .putBoolean("internal_state:$role", true).commit()
        val window = ChatHistory(title = "研究", messages = emptyList(), characterCardId = role)
        ChatHistoryManager.getInstance(context).saveChatHistory(window)
        chat = window.id
        CompanionTools.register(AIToolHandler.getInstance(context), context)
    }
    private fun todo(vararg params: Pair<String, String>): JSONObject {
        val values = params.toMap() + mapOf("__operit_package_chat_id" to chat, "__operit_package_caller_card_id" to role)
        val result = AIToolHandler.getInstance(context).executeTool(AITool("companion_todo", values.map { ToolParameter(it.key, it.value) }))
        assertTrue(result.error, result.success)
        return JSONObject(result.result.toString())
    }

    @Test fun offPageSaveCanActuallyBeSubmittedAndReopenedWithoutRepeatingExecution() {
        repeat(21) { todo("action" to "save", "title" to "旧事项$it") }
        val result = todo("action" to "save", "title" to "星空观测研究", "details" to
            JSONObject().put("origin", "本轮谈话").put("goal", "比较观测方法").put("next", "校准").toString())
        val id = result.getJSONObject("saved_item").getString("id")
        assertFalse(result.getJSONArray("todos").toString().contains(id))
        assertFalse(result.getJSONObject("saved_item").has("dispatch"))
        val saved = todo("action" to "submit", "id" to id, "minutes" to "0").getJSONObject("saved_item")
        val dispatch = saved.getJSONObject("dispatch")
        assertEquals("pending", dispatch.getString("state"))
        // A new board reads persisted state. Claiming is the real dispatcher entry point.
        val board = TaskBoard(context, CompanionStore(context).scope(chat, role), false)
        assertNotNull(board.claim(id, dispatch.getString("id")))
        assertNull(board.claim(id, dispatch.getString("id")))
        todo("action" to "comment", "id" to id, "content" to "校准已经完成，下一次比较方法")
        board.dispatchState(id, dispatch.getString("id"), "interrupted", "用户消息插入")
        assertTrue(TaskBoard(context, board.scope, false).get(id).toString().contains("校准已经完成"))
        assertNull(board.pending())
        todo("action" to "save", "id" to id, "status" to "paused")
        assertEquals("paused", board.get(id).getString("status"))
    }

    @Test fun decisionBeyondFirstPageReachesNextNormalRequestWithinOriginalBudget(): Unit = runBlocking {
        repeat(12) { n ->
            val sections = InnerStructureContext.prepare(context, role, chat, true,
                input = if (n == 11) "星空观测与校准" else "今天的一件其他事$n")
            val frame = requireNotNull(sections.internalStateFrame)
            val report = JSONObject().put("frame_id", frame.id).put("updates", JSONArray()).put("create", JSONArray())
                .put("decisions", JSONArray().put(JSONObject().put("id", "d%02d".format(n)).put("expected_version", 0)
                    .put("status", "chosen").put("meaning", if (n == 11) "星空观测先校准仪器" else "别的事项$n")
                    .put("evidence", JSONArray().put(frame.eventIds.single { it == "input:" + frame.id.removePrefix("state-frame:") }))))
            val stream = InternalStateReportStream()
            stream.append("<operit_state_report>$report</operit_state_report>已记录")
            stream.finish()
            InnerStructureContext.submit(context, frame, requireNotNull(stream.report))
        }
        val next = InnerStructureContext.prepare(context, role, chat, true, input = "继续星空观测校准")
        assertTrue(next.state.contains("星空观测先校准仪器"))
        assertTrue(requireNotNull(next.internalStateFrame).materialSources.keys.any { it.startsWith("material:decision:d11:") })
        // Saving a chosen interpretation never creates a schedule or private task on its own.
        assertFalse(ExplorationStore(context).read(role).has("at"))
        assertTrue(TaskBoard(context, CompanionStore(context).scope(chat, role), false).list().isEmpty())
    }

    @Test fun resumedActivityUsesOriginalQuestionAndOtherRolesCannotBorrowIt(): Unit = runBlocking {
        val first = ActivityNotes.begin(context, role, chat, "research-trigger", "星空观测误差研究")
        SubjectLife(context).checkpoint(first, "星空观测误差研究", "paused", exploration = true)
        ActivityNotes.release(first)
        val resumed = ActivityNotes.begin(context, role, chat, "new-resume-trigger", "恢复控制说明", first.id)
        try {
            assertEquals("星空观测误差研究", ActivityNotes.selectionInput(context, role, chat))
            assertNull(ActivityNotes.selectionInput(context, "another-role", chat))
            assertNull(ActivityNotes.selectionInput(context, role, "another-window"))
        } finally { ActivityNotes.release(resumed) }
        assertNull(ActivityNotes.selectionInput(context, role, chat))
    }
}
