package com.ai.assistance.operit.integrations.http

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.core.companion.*
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

/** The actual authenticated HTTP action adapter plus the normal request/adoption adapter.
 * HTTP bearer authentication is tested by WebChatHttpBridge; no real account/model here. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebPrivateStateBridgeTest {
    private lateinit var context: Context
    private lateinit var states: InternalStateStore
    private lateinit var bridge: WebPrivateStateBridge
    private lateinit var role: String
    private lateinit var chat: String
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        CharacterCardManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        role = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "网页私域"))
        val history = ChatHistory(title = "当前助手", messages = emptyList(), characterCardId = role)
        ChatHistoryManager.getInstance(context).saveChatHistory(history)
        chat = history.id
        states = InternalStateStore(context)
        states.configure(role, true)
        states.materialBudget(role, chat, 16384)
        bridge = WebPrivateStateBridge(context)
    }
    private suspend fun call(action: String, body: JSONObject = JSONObject()) =
        bridge.handle(body.put("action", action).put("chat_id", chat))
    private suspend fun candidate(id: String) = call("page", JSONObject().put("kind", "inner_pending").put("id", id))
    private fun record(family: String, id: String) = JSONObject().put("id", id).put("family", family)
        .put("kind", "完整认识").put("description", "我有自己的立场。\n但也可能犹豫，不能删掉条件和矛盾。")
        .put("status", "active").put("context", "尚未限定适用情境").put("exceptions", "仍允许例外").apply {
            if (family == "relation") put("object_ref", "user").put("label", "相处")
                .put("understanding", "她提出的信息和我的认识仍有区别").put("feeling", "在乎")
            if (family == "preference") put("aspect", "思维方式").put("horizon", "long_term")
                .put("orientation", "uncertain").put("ownership", "self")
        }
    @Test fun twoSourcesAcrossFamiliesAreRealCandidatesAndUiReadDoesNotAdopt(): Unit = runBlocking {
        assertEquals("not_opened", call("status").getJSONObject("window").getString("status"))
        val opened = call("open_window")
        assertEquals(opened.getLong("closes_at"), call("open_window").getLong("closes_at"))
        for (family in listOf("personality", "self_knowledge", "value", "stance", "preference", "relation")) {
            for (source in listOf("declared", "original")) {
                val r = record(family, "$family-$source")
                val body = JSONObject().put("record", r)
                if (source == "original") body.put("original", "当时记下的完整原话").put("source", "园园提供的记录")
                val saved = call("submit", body)
                val id = saved.getString("candidate_id")
                assertTrue(call("submit", body).getBoolean("replayed"))
                val exact = candidate(id).getJSONObject("item")
                assertEquals(r.getString("description"), exact.getJSONObject("record").getString("description"))
                assertEquals(if (source == "declared") EstablishedContent.ORIGIN else "user_proposal", exact.getString("origin"))
                assertNull(InnerStructure.record(states.read(role), r.getString("id")))
            }
        }
        assertEquals(12L, InnerRows(states.read(role), true).count())
        val id = call("page", JSONObject().put("kind", "inner_pending")).getJSONArray("items").getJSONObject(0).getString("id")
        val exact = candidate(id)
        call("withdraw", JSONObject().put("id", id).put("candidate_hash", exact.getString("candidate_hash")))
        assertNull(InnerStructure.candidate(states.read(role), id))
    }

    @Test fun editFullTextThenNormalReadAdoptsThatVersionAndHistoryRemains(): Unit = runBlocking {
        call("open_window")
        val r = record("personality", "exact")
        val id = call("submit", JSONObject().put("record", r)).getString("candidate_id")
        val first = candidate(id)
        val oldRead = nativeInnerRead(context, requireNotNull(InnerStructureContext.prepare(context, role, chat, true,
            input = "读过旧版").internalStateFrame), "inner_pending", id)
        val full = "完整第一段。\n不是一种标签：对象、程度、例外和矛盾都保留。\n" + "长内容仍须完整保存。".repeat(80)
        val edited = JSONObject(r.toString()).put("description", full)
        val revised = call("submit", JSONObject().put("record", edited).put("original", edited.toString())
            .put("source", "本次用户修订").put("replaces", id).put("previous_hash", first.getString("candidate_hash"))).getString("candidate_id")
        val proposal = candidate(revised).getJSONObject("item")
        val sourceRef = proposal.getJSONObject("bindings").keys().next()
        fun report(frame: InternalStateFrame) = JSONObject().put("frame_id", frame.id).put("updates", JSONArray())
            .put("create", JSONArray()).put("inner", JSONArray().put(JSONObject().put("op", "adopt").put("id", revised).put("reason", "采用读过的完整版本")
                .put("development", JSONObject().put("account", "这次读取完整修订后形成的采用说明")
                    .put("candidate_hash", InnerStructure.hash(proposal)).put("before_version", 0)
                    .put("anchors", JSONArray().put(JSONObject().put("ref", sourceRef).put("quote", "完整第一段。")
                        .put("interpretation", "采用的是这份有条件和保留的完整内容"))))))
        try { InnerStructureContext.submit(context, oldRead, report(oldRead)); fail("old version must not authorize adoption") }
        catch (_: IllegalArgumentException) {}
        val read = nativeInnerRead(context, requireNotNull(InnerStructureContext.prepare(context, role, chat, true,
            input = "理解修改后的全文").internalStateFrame), "inner_pending", revised)
        assertEquals(full, candidate(revised).getJSONObject("item").getJSONObject("record").getString("description"))
        InnerStructureContext.submit(context, read, report(read))
        val adopted = call("page", JSONObject().put("kind", "inner").put("id", "exact")).getJSONObject("item")
        assertEquals(full, adopted.getJSONObject("content").getString("description"))
        assertTrue(call("page", JSONObject().put("kind", "category_versions").put("id", "inner_record:exact"))
            .getJSONArray("items").length() > 0)
        val later = InnerStructureContext.prepare(context, role, chat, true, input = "后来的一轮")
        assertTrue(later.state.contains("exact"))
    }

    @Test fun currentImportKeepsEachFullSubmittedSourceAndPrivateLegacyKindsAreNotExposed(): Unit = runBlocking {
        val one = record("stance", "new-one")
        val two = record("preference", "new-two")
        call("import", JSONObject().put("source_mode", "new").put("content", JSONArray().put(one).put(two).toString()))
        assertEquals(2L, InnerRows(states.read(role), true).count())
        InnerRows(states.read(role), true).page(0).forEach { pending ->
            assertEquals("user_proposal", pending.getString("origin"))
            val sourceId = pending.getJSONObject("bindings").keys().next()
            val source = call("page", JSONObject().put("kind", "source").put("id", sourceId)).getJSONObject("item")
            assertEquals(pending.getJSONObject("record").getString("description"),
                JSONObject(source.getString("inner_source")).getString("description"))
        }
        for (kind in listOf("dream", "dream_understanding", "companion_note")) {
            try { call("page", JSONObject().put("kind", kind)); fail("private legacy body") }
            catch (_: IllegalArgumentException) {}
        }
        val other = CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "另一助手"))
        val foreign = ChatHistory(title = "外部", messages = emptyList(), characterCardId = other)
        ChatHistoryManager.getInstance(context).saveChatHistory(foreign)
        val result = bridge.handle(JSONObject().put("action", "page").put("chat_id", foreign.id)
            .put("owner", role).put("kind", "inner_pending"))
        assertEquals(0, result.getJSONArray("items").length())
        val group = ChatHistory(title = "群", messages = emptyList(), characterCardId = role, characterGroupId = "group")
        ChatHistoryManager.getInstance(context).saveChatHistory(group)
        try { bridge.handle(JSONObject().put("action", "status").put("chat_id", group.id)); fail("group private access") }
        catch (_: IllegalArgumentException) {}
    }
}
