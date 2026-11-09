package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.util.stream.StreamCollector
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Simulated model display tags; production note sink, tool executor, archive and normal
 * request composer. This does not claim a real model produced or understood these notes. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ActivityNotesEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    private lateinit var handler: AIToolHandler
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "随记"))
        InternalStateStore(context).configure(role, true)
        chat = window(role)
        com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences(context)
        handler = AIToolHandler.getInstance(context)
        handler.registerDefaultTools(); registerAllTools(handler, context)
        handler.getToolPermissionSystem().saveMasterSwitch(PermissionLevel.ALLOW)
        listOf("sleep", "companion_internal_state").forEach {
            handler.getToolPermissionSystem().saveToolPermission(it, PermissionLevel.ALLOW)
            CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", it, true)
        }
    }
    private suspend fun window(owner: String): String {
        val c = ChatHistory(title = "随记读取", messages = emptyList(), characterCardId = owner)
        ChatHistoryManager.getInstance(context).saveChatHistory(c); return c.id
    }
    private fun parser(ref: ActivityNotes.Ref, round: String) = ActivityNoteStream(round,
        { k, t, w -> ActivityNotes.note(context, ref, k, t, w) },
        { ActivityNotes.withdraw(context, ref, it) },
        { ActivityNotes.part(context, ref, it, "private_choice", JSONObject().put("chosen", true)) })

    @Test fun quietToolRoundAndWakingNotePersistThenEnterNextNormalRequest(): Unit = runBlocking {
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val ref = ActivityNotes.begin(context, role, chat, "trigger-one", "用户已有安排")
        try {
            val first = parser(ref, "0")
            first.append("<activity_note>想看一眼那个结果。</activity_note>")
            val frame = requireNotNull(InnerStructureContext.prepare(context, role, chat, true, wake = true, input = "已有安排").internalStateFrame)
            var executions = 0
            handler.registerTool("sleep", executor = { _: AITool -> executions++; ToolResult("sleep", true, StringResultData("已完成本地操作")) })
            suspend fun execute() = ToolExecutionManager.executeInvocations(listOf(ToolInvocation(AITool("sleep", emptyList()), "", 0..0)),
                context, handler, handler.getOrCreatePackageManager(), object : StreamCollector<String> { override suspend fun emit(value: String) {} },
                callerCardId = role, callerChatId = chat, subjectFrame = frame, subjectRound = 0, activityId = ref.id)
            assertTrue(execute().results.single().success)
            assertTrue(execute().results.single().success)
            assertEquals(1, executions)
            val next = parser(ref, "1")
            next.append("<waking_note>梦没记清，只留下一点海的感觉。</waking_note><activity_note>结果和想的不同，我先留着。</activity_note><companion_quiet/>")
            next.finish(); ActivityNotes.finish(context, ref, "quiet")
            val parts = ActivityNotes.page(context, role, "activity_part", ref.id, 0).getJSONArray("items")
            assertTrue((0 until parts.length()).any { parts.getJSONObject(it).getString("kind") == "operation" })
            assertEquals(0, CompanionStore(context).activityRead(role, "activity", ref.id)!!.getInt("message_count"))
            assertEquals(1, ActivityNotes.page(context, role, "waking_note", null, 0).getJSONArray("items").length())
            assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "dream", null, 0).getJSONArray("items").length())
            val sections = InnerStructureContext.prepare(context, role, window(role), true, input = "后来呢")
            assertTrue(sections.state.contains("海的感觉"))
            assertTrue(sections.state.contains("结果和想的不同"))
            assertTrue(sections.state.contains("not_new_occurrence"))
        } finally { ActivityNotes.release(ref) }
    }

    @Test fun completeParagraphSurvivesInterruptionAndNativeGetReadsOriginal(): Unit = runBlocking {
        val ref = ActivityNotes.begin(context, role, chat, "interrupted", "批注")
        try {
            val p = parser(ref, "0")
            p.append("<activity_note>确实留下的全文。</activity_note><activity_note>未完成")
            p.finish(); ActivityNotes.finish(context, ref, "interrupted")
        } finally { ActivityNotes.release(ref) }
        val tool = AITool("companion_internal_state", mapOf("action" to "get", "section" to "activity_part", "id" to ref.id,
            "__operit_package_chat_id" to chat, "__operit_package_caller_card_id" to role).map { ToolParameter(it.key, it.value) })
        val result = handler.executeTool(tool)
        assertTrue(result.error, result.success)
        assertTrue(result.result.toString().contains("确实留下的全文"))
        assertFalse(result.result.toString().contains("未完成"))
        val sections = InnerStructureContext.prepare(context, role, chat, true, input = "看看当时")
        val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "看看当时")), sections) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, result.result.toString(), result.toolName)
        val f = CurrentRequestContext.internalStateFrame(SubjectToolMaterials.attach(turns, System.currentTimeMillis(), listOf(result), context))!!
        assertTrue(f.materialSources.values.any { it.contains("确实留下的全文") })
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一个助手"))
        InternalStateStore(context).configure(other, true)
        val foreign = window(other)
        assertFalse(InnerStructureContext.prepare(context, other, foreign, true, input = "你好").state.contains("确实留下的全文"))
        try { InternalStateStore(context).inspectCategory(role, foreign, "activity_part", ref.id, 0); fail("must isolate") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun realChatWritesCountOnceAndDoNotTreatProvisionalTextAsSent(): Unit = runBlocking {
        val ref = ActivityNotes.begin(context, role, chat, "normal-input", "normal_input")
        val history = ChatHistoryManager.getInstance(context)
        try {
            val empty = ChatMessage(sender = "ai", content = "", timestamp = 1234)
            history.addMessage(chat, empty)
            assertEquals(0, CompanionStore(context).activityRead(role, "activity", ref.id)!!.getInt("message_count"))
            val final = empty.copy(content = "这是已经写入会话的表达。")
            history.updateMessage(chat, final)
            history.updateMessage(chat, final)
            history.addMessage(chat, ChatMessage(sender = "ai", content = "另一条实际表达。", timestamp = 1235))
            assertEquals(role, history.getChatMetadata(chat)?.characterCardId)
            assertEquals(ref, ActivityNotes.current(chat))
            assertEquals(org.robolectric.shadows.ShadowLog.getLogsForTag("ActivityNotes").joinToString { it.msg + " " + it.throwable?.stackTraceToString() },
                2, CompanionStore(context).activityRead(role, "activity", ref.id)!!.getInt("message_count"))
            ActivityNotes.schedule(context, ref, JSONObject().put("generation", "plan-one").put("at", 9999)
                .put("purpose", "已经保存的继续事项").put("paused", false))
            val page = ActivityNotes.page(context, role, "activity_part", ref.id, 0).getJSONArray("items")
            assertEquals(2, (0 until page.length()).count { page.getJSONObject(it).getString("kind") == "message" })
            assertTrue(page.toString().contains(final.content))
            assertFalse(page.toString().contains("\"read\":true"))
            assertTrue(InnerStructureContext.prepare(context, role, window(role), true, input = "继续").state.contains("写入会话"))
        } finally { ActivityNotes.release(ref) }
    }
}
