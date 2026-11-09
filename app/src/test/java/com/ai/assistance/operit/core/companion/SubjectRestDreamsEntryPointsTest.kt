package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.*
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.backup.RawSnapshotBackupManager
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.util.stream.StreamCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Model content below is simulated. Production request composition, hidden report parsing,
 * adoption, native query, SQLite and full snapshot backup/restore are exercised directly. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectRestDreamsEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var chat: String
    private lateinit var second: String
    private lateinit var handler: AIToolHandler
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "梦测试", characterSetting = "初始参考"))
        InternalStateStore(context).configure(role, true)
        suspend fun window(): String {
            val c = ChatHistory(title = "私聊", messages = emptyList(), characterCardId = role)
            ChatHistoryManager.getInstance(context).saveChatHistory(c); return c.id
        }
        chat = window(); second = window()
        com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences(context)
        handler = AIToolHandler.getInstance(context)
        handler.registerDefaultTools(); registerAllTools(handler, context)
        handler.getToolPermissionSystem().saveMasterSwitch(PermissionLevel.ALLOW)
        handler.getToolPermissionSystem().saveToolPermission("companion_internal_state", PermissionLevel.ALLOW)
        CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", "companion_internal_state", true)
    }
    private suspend fun request(window: String = chat, wake: Boolean = false) =
        InnerStructureContext.prepare(context, role, window, true, wake = wake, input = "延续此前的理解")
    private suspend fun submit(f: InternalStateFrame, fields: JSONObject): JSONObject {
        val report = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
        fields.keys().forEach { report.put(it, fields.get(it)) }
        val parser = InternalStateReportStream()
        assertEquals("正常表达", parser.append("<operit_state_report>$report</operit_state_report>正常表达") + parser.finish())
        return InnerStructureContext.submit(context, f, requireNotNull(parser.report))
    }
    private fun rest(state: String, version: Int) = JSONObject().put("expected_version", version)
        .put("state", state).put("meaning", "我选择暂歇或继续，未完成的事仍在").put("evidence", JSONArray())
    private fun dreaming() = JSONObject().put("rest", rest("resting", 0)).put("dream", JSONObject()
        .put("title", "海上的书房").put("content", "书页变成鲸鱼，窗外的月亮在倒着走。user在梦中没有认出我。")
        .put("sources", JSONArray()))
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("invalid candidate accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
    private fun item(kind: String, id: String) = InternalStateStore(context).inspectCategory(role, chat, kind, id, 0).getJSONObject("item")
    private suspend fun readMaterial(window: String, kind: String, id: String): InternalStateFrame {
        val s = request(window); val f = requireNotNull(s.internalStateFrame)
        val tool = AITool("companion_internal_state", listOf(ToolParameter("action", "get"), ToolParameter("section", kind), ToolParameter("id", id)))
        val results = ToolExecutionManager.executeInvocations(listOf(ToolInvocation(tool, "", 0..0)), context,
            handler, handler.getOrCreatePackageManager(), object : StreamCollector<String> { override suspend fun emit(value: String) {} },
            callerCardId = role, callerChatId = window, subjectFrame = f, subjectRound = 0).results
        assertTrue(results.single().error, results.single().success)
        val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "查阅自己此前的梦")), s) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, ConversationMarkupManager.buildToolResultMessage(results), "tools")
        return requireNotNull(CurrentRequestContext.internalStateFrame(SubjectToolMaterials.attach(turns, System.currentTimeMillis(), results, context)))
    }
    private suspend fun createDream(): String {
        val f = requireNotNull(request().internalStateFrame)
        submit(f, dreaming())
        return "dream-" + InnerStructure.hash(f.id)
    }
    private fun interpretation(source: String, version: Int = 0) = JSONObject().put("id", "first-understanding")
        .put("expected_version", version).put("dream_source", source).put("meaning", "这是梦，不是她真的不认识我；我想把那一点失落记下来")
        .put("feeling", "醒后仍有一点失落").put("objects", JSONArray().put("user")).put("evidence", JSONArray().put(source))

    @Test fun normalPrivateDreamThenWakingUnderstandingSurvivesWindowsAndReopen(): Unit = runBlocking {
        InternalStateStore(context).materialBudget(role, chat, 16384)
        val id = createDream()
        val dream = item("dream", id)
        assertEquals("DREAM_NOT_REALITY", dream.getJSONObject("content").getString("reality"))
        assertEquals(0, dream.getJSONObject("content").getJSONObject("source_bindings").length())
        val frame = readMaterial(second, "dream", id)
        val source = frame.materialSources.entries.first { JSONObject(it.value).optString("type") == "dream_not_reality" }.key
        assertTrue(frame.materialSources.values.any { JSONObject(it).optJSONObject("locator")?.optString("kind") == "rest" })
        val fields = JSONObject().put("rest", rest("awake", 1)).put("dream_understanding", JSONArray().put(interpretation(source)))
        submit(frame, fields); submit(frame, fields)
        assertEquals(1, item("dream_understanding", "first-understanding").getInt("version"))
        val next = requireNotNull(request(chat, wake = true).internalStateFrame)
        assertTrue(next.materialSources.values.any { it.contains("醒后仍有一点失落") })
        // A simulated later interpretation can change the ordinary adopted feeling structure,
        // without a technical dream-success signal selecting any emotion on the model's behalf.
        val feeling = JSONObject().put("id", "after-dream").put("family", "feeling").put("kind", "梦后余波")
            .put("description", "梦不是真实行为，但我确实还在理解它").put("status", "active")
            .put("unresolved", true).put("current_state", "一点失落，不是对现实行为的指控")
        val proposal = JSONObject().put("op", "propose").put("id", "feeling-proposal").put("record", feeling)
            .put("expected_version", 0).put("origin", "subject_development").put("reason", "醒后自己的解释")
            .put("evidence", JSONArray().put(source))
        // Read the exact dream when a prior receipt competes for the shared material budget.
        val proposing = readMaterial(second, "dream", id)
        submit(proposing, JSONObject().put("inner", JSONArray().put(proposal)))
        val adopting = requireNotNull(request().internalStateFrame)
        submit(adopting, JSONObject().put("inner", JSONArray().put(JSONObject().put("op", "adopt")
            .put("id", "feeling-proposal").put("reason", "明确采用这份感受解释"))))
        val continued = request(second)
        assertTrue(continued.state.contains("不是对现实行为的指控"))
        assertEquals("awake", item("rest", "current").getJSONObject("content").getString("state"))
        // New store/request instances reopen real persisted heads; no in-memory dream cache.
        assertEquals(id, InternalStateStore(context).inspectCategory(role, second, "dream", id, 0).getJSONObject("item").getString("id"))
        val messages = ChatHistoryManager.getInstance(context).loadChatMessages(chat)
        assertTrue(messages.isEmpty()) // Internal dream generation is not a public chat send.
    }

    @Test fun dreamWithoutRealityEvidenceIsValidButWithoutRestIsAtomicRejection(): Unit = runBlocking {
        val f = requireNotNull(request().internalStateFrame)
        rejected { submit(f, JSONObject().put("dream", dreaming().getJSONObject("dream"))) }
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "dream", null, 0).getJSONArray("items").length())
        submit(f, dreaming())
        assertEquals(1, item("rest", "current").getInt("version"))
    }

    @Test fun duplicatedReportDoesNotGenerateSecondDreamAndStaleReportCannotOverwrite(): Unit = runBlocking {
        val f = requireNotNull(request().internalStateFrame)
        val fields = dreaming(); submit(f, fields); submit(f, fields)
        assertEquals(1, InternalStateStore(context).inspectCategory(role, chat, "dream", null, 0).getJSONArray("items").length())
        rejected { submit(f, JSONObject().put("rest", rest("awake", 0))) }
        assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
    }

    @Test fun rereadingDreamDoesNotAdoptFeelingOrWakeSubject(): Unit = runBlocking {
        val id = createDream()
        repeat(3) { readMaterial(second, "dream", id) }
        assertEquals(1, item("dream", id).getInt("version"))
        assertEquals(1, item("rest", "current").getInt("version"))
        assertEquals(0, InternalStateStore(context).inspectCategory(role, chat, "dream_understanding", null, 0).getJSONArray("items").length())
    }

    @Test fun fabricatedDreamSourceRejectsWholeWakingCandidate(): Unit = runBlocking {
        createDream()
        val f = readMaterial(chat, "rest", "current")
        rejected { submit(f, JSONObject().put("rest", rest("awake", 1))
            .put("dream_understanding", JSONArray().put(interpretation("material:dream:invented:1")))) }
        assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
    }

    @Test fun anotherAssistantCannotReadDreamAndOwnRequestHasNoDream(): Unit = runBlocking {
        val id = createDream()
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一个"))
        InternalStateStore(context).configure(other, true)
        val c = ChatHistory(title = "另一私聊", messages = emptyList(), characterCardId = other)
        ChatHistoryManager.getInstance(context).saveChatHistory(c)
        rejected { InternalStateStore(context).inspectCategory(role, c.id, "dream", id, 0) }
        val f = requireNotNull(InnerStructureContext.prepare(context, other, c.id, true, input = "你好").internalStateFrame)
        assertFalse(f.materialSources.values.any { it.contains("海上的书房") })
    }

    @Test fun groupCannotReadDreamOrSubmitPrivateRest(): Unit = runBlocking {
        val id = createDream()
        val group = com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
            .createCharacterGroupCard(CharacterGroupCard("", "群", members = listOf(GroupMemberConfig(role))))
        val c = ChatHistory(title = "群聊", messages = emptyList(), characterGroupId = group)
        ChatHistoryManager.getInstance(context).saveChatHistory(c)
        rejected { InternalStateStore(context).inspectCategory(role, c.id, "dream", id, 0) }
        rejected { InternalStateStore(context).inspectCategory(role, c.id, "category_versions", "dream:$id", 0) }
        val s = InnerStructureContext.prepare(context, role, c.id, true, input = "群聊输入")
        assertFalse(s.state.contains("海上的书房"))
        rejected { submit(requireNotNull(s.internalStateFrame), JSONObject().put("rest", rest("awake", 1))) }
        assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun realFullSnapshotBackupRestoresDreamAndSubsequentNormalRead(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val id = createDream()
            val original = item("dream", id).toString()
            val zip = RawSnapshotBackupManager.exportToBackupDir(context)
            assertTrue(zip.length() > 0)
            val f = readMaterial(second, "rest", "current")
            submit(f, JSONObject().put("rest", rest("awake", 1)))
            RawSnapshotBackupManager.restoreFromBackupUri(context, Uri.fromFile(zip))
            // The existing restore UI requires an application restart. Simulate only that
            // singleton lifetime boundary; do not edit any persisted subject or chat data.
            ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
            assertEquals(original, item("dream", id).toString())
            assertEquals("resting", item("rest", "current").getJSONObject("content").getString("state"))
            assertTrue(requireNotNull(request(second).internalStateFrame).materialSources.values.any { it.contains("海上的书房") })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun manyDreamsStayPagedAndHotSnapshotContainsOnlyLatestCommit(): Unit = runBlocking {
        val first = createDream()
        repeat(10) {
            val f = requireNotNull(request().internalStateFrame)
            submit(f, JSONObject().put("dream", JSONObject().put("title", "第$it段梦")
                .put("content", "想象的风景$it").put("sources", JSONArray())))
        }
        val store = InternalStateStore(context)
        val one = store.inspectCategory(role, chat, "dream", null, 0)
        val two = store.inspectCategory(role, second, "dream", null, one.getInt("next_offset"))
        assertEquals(8, one.getJSONArray("items").length())
        assertEquals(3, two.getJSONArray("items").length())
        assertEquals(first, item("dream", first).getString("id"))
        assertEquals(1, store.read(role).getJSONObject("category_commit").getJSONArray("rows").length())
        val f = requireNotNull(request().internalStateFrame)
        assertTrue(f.materialSources.size <= 24)
        assertTrue(SubjectRestDreams.materials(store.read(role)).size <= 24)
    }
}
