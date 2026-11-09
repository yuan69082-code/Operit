package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.services.core.ChatHistoryDelegate
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Normal storage/tool/request adapters, without a real model or Android service scheduler. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ActivityContinuityEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var other: String
    private lateinit var chat: String
    private lateinit var second: String
    private lateinit var foreign: String
    private lateinit var history: ChatHistoryManager
    private lateinit var observerScope: CoroutineScope
    @Before fun setup(): Unit = runBlocking {
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        context = RuntimeEnvironment.getApplication()
        val cards = CharacterCardManager.getInstance(context)
        role = cards.createCharacterCard(CharacterCard("", "主体", characterSetting = "初始"))
        other = cards.createCharacterCard(CharacterCard("", "另一主体"))
        history = ChatHistoryManager.getInstance(context)
        suspend fun window(owner: String): String {
            val c = ChatHistory(title = "活动承接", messages = emptyList(), characterCardId = owner)
            history.saveChatHistory(c); return c.id
        }
        chat = window(role); second = window(role); foreign = window(other)
    }
    @After fun stopObservers(): Unit = runBlocking {
        observerScope.coroutineContext[Job]!!.cancelAndJoin()
    }
    // Production UI observers are intentionally long-lived. Own them outside each finite
    // test coroutine and cancel/join them at teardown instead of waiting for Flow completion.
    private fun delegate() = ChatHistoryDelegate(context, observerScope,
        onTokenStatisticsLoaded = { _, _, _, _ -> }, getEnhancedAiService = { null })
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("expected failure") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }

    @Test fun budgetedNormalRequestUsesSavedMaterialAndAdoptionReadsItAfterRestart(): Unit = runBlocking {
        val states = InternalStateStore(context)
        states.configure(role, true)
        states.materialBudget(role, chat, 16384)
        val library = CompanionStore(context)
        library.saveEntry(library.scope(second, role), null, "星云旧资料", "天文", "knowledge", "尚未读到的原文", null, listOf("星云"))
        EchoThemes.saveTheme(context, role, "星云", listOf("继续"))
        delegate().addWakeMessageToChat(ChatMessage(sender = "ai", content = "实际保存的解释：想先了解"), chat, role, "material-receipt")
        val study = StudyStore(context, role)
        val book = study.create("材料", "writing", "daily", "user").getString("id")
        val receipt = StudyTool.execute(context, role, "review", book,
            JSONObject().put("text", "我读到另一种看法").put("operation_id", "material-review"), 0)
        val sections = CompanionContext.compose(context, second, role, JSONObject(), true, "继续")
        val frame = requireNotNull(sections.internalStateFrame)
        val source = frame.materialSources.entries.single { JSONObject(it.value).optString("type") == "saved_chat_excerpt" }
        assertTrue(frame.materialSources.values.any { it.contains(receipt.getString("reply_id")) })
        val title = frame.materialSources.values.map { JSONObject(it) }.single { it.optString("type") == "title_index_only" }
        assertEquals("星云旧资料", title.getString("text"))
        assertFalse(title.getBoolean("body_read"))
        assertFalse(sections.state.contains("尚未读到的原文"))
        assertEquals("", CompanionContext.related(context, second, role, "继续"))
        val staged = com.ai.assistance.operit.core.chat.CurrentRequestContext.stage(
            listOf(com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.USER, "继续")), sections)
        val request = com.ai.assistance.operit.core.chat.CurrentRequestContext.finalizeBoundRequest(
            com.ai.assistance.operit.core.chat.CurrentRequestContext.withoutStateBinding(staged), "继续",
            com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(staged))
        assertEquals(frame.id, requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(request)).id)
        assertTrue(requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.subjectMaterial(request)).contains("我读到另一种看法"))
        val continuation = SubjectToolMaterials.attach(request + com.ai.assistance.operit.core.chat.hooks.PromptTurn(
            com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.TOOL_RESULT, "实际资料返回", "companion_library"), 100)
        assertTrue(requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(continuation))
            .materialSources.values.any { JSONObject(it).optString("type") == "tool_result" })
        assertEquals(1, request.count { it.content.contains("实际保存的解释：想先了解") })
        assertTrue(request.any { it.content.contains("我读到另一种看法") })
        val record = JSONObject().put("id", "consideration").put("family", "feeling").put("kind", "新的理解")
            .put("description", "依据已读回复重新理解").put("status", "active").put("unresolved", true).put("current_state", "保留不同看法")
        suspend fun submit(f: InternalStateFrame, action: JSONObject) {
            val json = JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray()).put("inner", JSONArray().put(action))
            val parser = InternalStateReportStream()
            assertEquals("正文", parser.append("<operit_state_report>$json</operit_state_report>正文") + parser.finish())
            InnerStructureContext.submit(context, f, requireNotNull(parser.report))
        }
        submit(frame, JSONObject().put("op", "propose").put("id", "material-candidate").put("record", record)
            .put("expected_version", 0).put("origin", "subject_development").put("reason", "重新理解实际材料")
            .put("evidence", JSONArray().put(source.key)))
        val following = requireNotNull(CompanionContext.compose(context, chat, role, JSONObject(), true, "接着").internalStateFrame)
        submit(following, JSONObject().put("op", "adopt").put("id", "material-candidate").put("reason", "采用这次解释"))
        val reopened = InternalStateStore(context).read(role)
        val adopted = InnerStructure.records(reopened).single { it.getString("id") == "consideration" }
        assertEquals("retrieved_material", adopted.getJSONObject("evidence").getJSONObject(source.key).getString("source"))
        assertTrue(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("保留不同看法"))
        assertFalse(CompanionContext.compose(context, foreign, other, JSONObject(), false).state.contains("保留不同看法"))
    }

    @Test fun annotationReplyReceiptAndNextChatReferToActualStoredNote(): Unit = runBlocking {
        val store = StudyStore(context, role)
        val book = store.create("共读", "writing", "daily", "user").getString("id")
        val chapter = store.write(book, null, null, "一", "真实原文", JSONArray(), "user", false).getJSONArray("chapters").getJSONObject(0)
        val version = chapter.getString("version")
        val saved = store.annotate(book, version, 0, 2, "真实", "这里我有疑问", "hand", "#486B65", JSONArray(), JSONArray(), "user", "user-note")
        val note = saved.getJSONArray("notes").getJSONObject(0).getString("id")
        val ids = setOf(saved.getJSONArray("events").getJSONObject(0).getString("id"))
        assertTrue(store.claim(book, ids)); assertFalse(store.claim(book, ids))
        val args = JSONObject().put("reply_to", note).put("text", "这是已保存的确切回复").put("operation_id", "reply-op")
        val receipt = StudyTool.execute(context, role, "reply", book, args, 0)
        assertEquals(chapter.getString("id"), receipt.getString("chapter")); assertEquals(role, receipt.getString("author"))
        assertEquals(note, receipt.getString("reply_to")); assertTrue(receipt.getBoolean("saved"))
        val repeated = StudyTool.execute(context, role, "reply", book, args, 0)
        assertEquals(receipt.getString("reply_id"), repeated.getString("reply_id"))
        store.finish(book, ids, "handled")
        val next = CompanionContext.compose(context, second, role, JSONObject(), false, "书房回了吗")
        assertTrue(next.state.contains(receipt.getString("reply_id"))); assertTrue(next.state.contains("这是已保存的确切回复"))
        val original = StudyTool.execute(context, role, "note", book, JSONObject().put("note_id", receipt.getString("reply_id")), 0)
        assertEquals("这是已保存的确切回复", original.getString("text"))
        assertEquals(2, StudyStore(context, role).book(book).getJSONArray("notes").length())
        rejects { StudyTool.execute(context, role, "reply", book, JSONObject(args.toString()).put("text", "重试偷偷换内容"), 0) }
        assertFalse(CompanionContext.compose(context, foreign, other, JSONObject(), false).state.contains("这是已保存的确切回复"))
    }

    @Test fun backgroundReplyPersistsAndEmptyEarlierReplyDoesNotHideSupplement(): Unit = runBlocking {
        val d = delegate()
        d.addMessageToChat(ChatMessage(sender = "user", content = "13:27 那句话", timestamp = 100), chat)
        d.addMessageToChat(ChatMessage(sender = "ai", content = "", timestamp = 101), chat)
        val message = ChatMessage(sender = "ai", roleName = "主体", content = "补答已真实保存")
        val saved = d.addWakeMessageToChat(message, chat, role, "study:stable:public")
        val replay = d.addWakeMessageToChat(message, chat, role, "study:stable:public")
        assertEquals(saved.message.timestamp, replay.message.timestamp)
        assertTrue(saved.newlySaved); assertFalse(replay.newlySaved)
        assertEquals(1, d.getRuntimeChatHistory(chat).count { it.content == "补答已真实保存" })
        val next = CompanionContext.compose(context, second, role, JSONObject(), false, "继续")
        assertTrue(next.state.contains("补答已真实保存")); assertTrue(next.state.contains("reply_saved"))
        // New delegate/repository reads persistent rows rather than reusing an old UI list.
        assertTrue(delegate().getRuntimeChatHistory(chat).any { it.timestamp == saved.message.timestamp && it.content == message.content })
        rejects { d.addWakeMessageToChat(message.copy(content = "不能以新文字重试同一次发送"), chat, role, "study:stable:public") }
        assertEquals(1, history.recentAssistantActivity(role).count { it.content == "补答已真实保存" })
    }

    @Test fun activityOriginalsArePagedAndPrivateProjectionDoesNotEnterGroups(): Unit = runBlocking {
        val d = delegate()
        d.addWakeMessageToChat(ChatMessage(sender = "ai", content = "私聊内容".repeat(1200)), chat, role, "long-reply")
        val activity = history.recentAssistantActivity(role).first()
        assertEquals(1200, activity.content.length)
        val first = history.assistantActivityDetail(role, activity.messageId, 0)
        val next = history.assistantActivityDetail(role, activity.messageId, 2400)
        assertEquals(2400, first.message.content.length); assertEquals(2400, next.message.content.length)
        assertEquals(4800, first.totalChars)
        rejects { history.assistantActivityDetail(other, activity.messageId, 0) }
        val group = ChatHistory(title = "群", messages = emptyList(), characterGroupId = "historical-group")
        history.saveChatHistory(group)
        assertFalse(CompanionContext.compose(context, group.id, role, JSONObject(), false).state.contains("私聊内容"))
    }

    @Test fun claimIsAtomicAndCancellationDoesNotPretendTheReplySucceeded(): Unit = runBlocking {
        val store = StudyStore(context, role)
        val id = store.create("书", "writing", "daily", "user").getString("id")
        store.annotate(id, null, 0, 0, "", "新批注", "hand", "#486B65", JSONArray(), JSONArray(), "user", "one")
        val ids = setOf(store.book(id).getJSONArray("events").getJSONObject(0).getString("id"))
        val claimed = listOf(async(Dispatchers.IO) { store.claim(id, ids) }, async(Dispatchers.IO) { StudyStore(context, role).claim(id, ids) }).awaitAll()
        assertEquals(1, claimed.count { it })
        store.finish(id, ids, "unknown", "网络中断，不能确认工具结果")
        assertFalse(store.claim(id, ids))
        val facts = StudyStore(context, role).recentActivity()
        assertTrue(facts.toString().contains("unknown")); assertEquals(1, facts.getJSONArray("notes").length())
        store.retry(id); store.retry(id)
        val retained = store.book(id).getJSONArray("events")
        assertEquals(2, retained.length()); assertEquals("unknown", retained.getJSONObject(0).getString("state"))
        assertEquals(ids.single(), retained.getJSONObject(1).getString("recovery_of"))
        store.annotate(id, null, 0, 0, "", "另一条新批注", "hand", "#486B65", JSONArray(), JSONArray(), "user", "two")
        val newId = store.book(id).getJSONArray("events").getJSONObject(2).getString("id")
        assertTrue(store.claim(id, setOf(newId)))
        rejects { StudyTool.execute(context, role, "reply", id, JSONObject().put("reply_to", "missing").put("text", "未保存").put("operation_id", "bad"), 0) }
        assertFalse(store.recentActivity().toString().contains("未保存"))
    }

    @Test fun concurrentChatAndStudyUseActualOwnerAndDoNotLoseMessages(): Unit = runBlocking {
        val d = delegate()
        val store = StudyStore(context, role)
        val book = store.create("书", "writing", "daily", "user").getString("id")
        val writes = listOf(
            async(Dispatchers.IO) { d.addWakeMessageToChat(ChatMessage(sender = "ai", content = "后台表达"), chat, role, "wake:one") },
            async(Dispatchers.IO) { d.addMessageToChat(ChatMessage(sender = "user", content = "同时新消息"), chat) },
            async(Dispatchers.IO) { StudyTool.execute(context, role, "review", book, JSONObject().put("text", "实际书房读后感").put("operation_id", "review"), 0) }
        )
        writes.awaitAll()
        val facts = CompanionContext.compose(context, second, role, JSONObject(), false).state
        assertTrue(facts.contains("后台表达")); assertTrue(facts.contains("同时新消息")); assertTrue(facts.contains("实际书房读后感"))
        withContext(AssistantScope.executionContext(other)) { rejects { history.recentAssistantActivity(role) } }
        rejects { d.addWakeMessageToChat(ChatMessage(sender = "ai", content = "串线"), foreign, role, "bad") }
        assertFalse(history.recentAssistantActivity(other).any { it.content == "后台表达" })
    }
}
