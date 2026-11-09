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
    @Before fun setup(): Unit = runBlocking {
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
    private fun delegate(scope: CoroutineScope) = ChatHistoryDelegate(context, scope,
        onTokenStatisticsLoaded = { _, _, _, _ -> }, getEnhancedAiService = { null })
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("expected failure") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
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
        val d = delegate(this)
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
        assertTrue(delegate(this).getRuntimeChatHistory(chat).any { it.timestamp == saved.message.timestamp && it.content == message.content })
        rejects { d.addWakeMessageToChat(message.copy(content = "不能以新文字重试同一次发送"), chat, role, "study:stable:public") }
        assertEquals(1, history.recentAssistantActivity(role).count { it.content == "补答已真实保存" })
    }

    @Test fun activityOriginalsArePagedAndPrivateProjectionDoesNotEnterGroups(): Unit = runBlocking {
        val d = delegate(this)
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
        store.annotate(id, null, 0, 0, "", "另一条新批注", "hand", "#486B65", JSONArray(), JSONArray(), "user", "two")
        val newId = store.book(id).getJSONArray("events").getJSONObject(1).getString("id")
        assertTrue(store.claim(id, setOf(newId)))
        rejects { StudyTool.execute(context, role, "reply", id, JSONObject().put("reply_to", "missing").put("text", "未保存").put("operation_id", "bad"), 0) }
        assertFalse(store.recentActivity().toString().contains("未保存"))
    }

    @Test fun concurrentChatAndStudyUseActualOwnerAndDoNotLoseMessages(): Unit = runBlocking {
        val d = delegate(this)
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
