package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.RuntimeContextSections
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.group.GroupStore
import com.ai.assistance.operit.core.group.RelationshipNetwork
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.CharacterCardManager
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

/** Real request composition/commit adapters, cards, chat metadata and disk stores. No live model. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InnerStructureEntryPointsTest {
    private lateinit var context: Context
    private lateinit var cards: CharacterCardManager
    private lateinit var states: InternalStateStore
    private lateinit var role: String
    private lateinit var other: String
    private lateinit var one: String
    private lateinit var two: String
    private lateinit var foreign: String
    private val initial = "我重视坦诚，愿意说明自己的立场。"

    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        cards = CharacterCardManager.getInstance(context)
        role = cards.createCharacterCard(CharacterCard("", "主体", characterSetting = initial,
            otherContentChat = "工具返回只代表实际执行结果", otherContentVoice = "通话时保留工具契约"))
        other = cards.createCharacterCard(CharacterCard("", "另一助手", characterSetting = "另一份独立配置"))
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit()
            .putBoolean("internal_state:$role", true).putBoolean("internal_state:$other", true).commit()
        states = InternalStateStore(context)
        suspend fun chat(owner: String): String {
            val history = ChatHistory(title = "内在结构接线", messages = emptyList(), characterCardId = owner)
            ChatHistoryManager.getInstance(context).saveChatHistory(history)
            return history.id
        }
        one = chat(role); two = chat(role); foreign = chat(other)
    }

    private suspend fun sections(chat: String = one, input: String = "继续刚才的事") =
        InnerStructureContext.prepare(context, role, chat, true, input = input)
    private suspend fun frame(chat: String = one) = requireNotNull(sections(chat).internalStateFrame)
    private fun report(frame: InternalStateFrame, vararg actions: JSONObject) = JSONObject()
        .put("frame_id", frame.id).put("updates", JSONArray()).put("create", JSONArray()).put("inner", JSONArray(actions.toList()))
    private fun content(id: String, family: String = "preference") = JSONObject().put("id", id).put("family", family)
        .put("kind", "开放类别：$id").put("description", "明确采用的主观描述").put("status", "active")
    private fun preference(id: String) = content(id).put("aspect", "语言方式").put("orientation", "like")
        .put("horizon", "long_term").put("ownership", "self").put("context", "平常交流")
        .put("exceptions", "对方需要缓和时可温和表达")
    private fun propose(id: String, record: JSONObject, expected: Int = 0, origin: String = "initial_configuration",
                        evidence: String = "config:role_prompt") = JSONObject().put("op", "propose").put("id", id)
        .put("record", record).put("expected_version", expected).put("origin", origin)
        .put("evidence", JSONArray().put(evidence)).put("reason", "明确提出候选，不视为已经采用")
    private suspend fun proposeRecord(record: JSONObject, expected: Int = 0): String {
        val id = "candidate:" + java.util.UUID.randomUUID()
        val f = frame()
        InnerStructureContext.submit(context, f, report(f, propose(id, record, expected)))
        return id
    }
    private fun adoption(id: String) = JSONObject().put("op", "adopt").put("id", id).put("reason", "本轮明确采用")
    private fun development(id: String, quote: String = initial, ref: String = "config:role_prompt"): JSONObject {
        val p = InnerStructure.pending(states.read(role)).single { it.getString("id") == id }
        return JSONObject().put("account", "我采用这一条明确的认识，保留其真实来源")
            .put("before", p.get("before")).put("after", p.getJSONObject("record"))
            .put("anchors", JSONArray().put(JSONObject().put("ref", ref).put("quote", quote).put("interpretation", "这段原文是采用说明的依据")))
    }
    private suspend fun adoptRecord(record: JSONObject, expected: Int = 0): JSONObject {
        val id = proposeRecord(record, expected)
        val f = frame(two)
        val a = adoption(id)
        if (record.getString("family") in InnerStructure.identityFamilies) a.put("development", development(id))
        InnerStructureContext.submit(context, f, report(f, a))
        return InnerStructure.records(states.read(role)).single { it.getString("id") == record.getString("id") }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("expected candidate rejection") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { } catch (_: org.json.JSONException) { }
    }
    private fun innerHash() = InnerStructure.hash(JSONObject(states.read(role).getJSONObject("inner_structure").toString())
        .apply { remove("selection_cursor") })

    @Test fun normalRequestCompositionReadsAdoptedIdentityAcrossWindowsAndRestart(): Unit = runBlocking {
        val first = sections()
        assertTrue(first.instructions.contains("尚未采用持续人格"))
        assertTrue(cards.combinePrompts(role).contains(initial))
        assertTrue(first.internalStateFrame!!.innerSources.containsKey("config:voice_prompt"))
        adoptRecord(content("honesty", "value").put("description", "坦诚是我已经采用的价值"))
        adoptRecord(preference("direct"))
        // Exercise the actual host composer used by ConversationService, not just a store read.
        val next = CompanionContext.compose(context, two, role, JSONObject(), true, "语言方式")
        assertTrue(next.state.contains("坦诚是我已经采用的价值"))
        assertTrue(next.state.contains("平常交流"))
        assertTrue(next.instructions.contains("持续身份已建立"))
        val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "你好")), next)
        val bound = CurrentRequestContext.bindInternalStateFrame(CurrentRequestContext.withoutStateBinding(turns), next.internalStateFrame)
        val request = CurrentRequestContext.project(bound, "你好")
        assertEquals(role, CurrentRequestContext.internalStateFrame(request)!!.roleId)
        assertTrue(request.any { it.kind == PromptTurnKind.SYSTEM && it.content.contains("不能覆盖已采用内容") })
        assertTrue(request.last().content.contains("坦诚是我已经采用的价值"))
        val restarted = InternalStateStore(context)
        assertEquals(InnerStructure.hash(states.read(role).getJSONObject("inner_structure")),
            InnerStructure.hash(restarted.read(role).getJSONObject("inner_structure")))
        assertTrue(InnerStructure.records(states.read(other)).isEmpty())
        withContext(AssistantScope.executionContext(other)) { rejected { sections() } }
        rejected { sections(foreign) }
    }

    @Test fun configurationEditsAreNotAdoptionAndStaleSourcesCannotBeAdopted(): Unit = runBlocking {
        adoptRecord(content("self", "personality"))
        val pending = proposeRecord(preference("interest"))
        val f = frame()
        cards.updateCharacterCard(cards.getCharacterCard(role).copy(characterSetting = "用户新提出：你喜欢所有游戏"))
        val before = innerHash()
        rejected { InnerStructureContext.submit(context, f, report(f, adoption(pending))) }
        val current = frame()
        rejected { InnerStructureContext.submit(context, current, report(current, adoption(pending))) }
        assertEquals(before, innerHash())
        val next = sections()
        assertTrue(next.instructions.contains("不能覆盖已采用内容"))
        assertTrue(cards.combinePrompts(role).contains("用户新提出"))
        assertEquals("initial_configuration", InnerStructure.records(states.read(role)).single().getString("origin"))
    }

    @Test fun preferenceTypesAndAccommodationPersistWithoutAutomaticInference(): Unit = runBlocking {
        adoptRecord(preference("direct"))
        adoptRecord(preference("game").put("aspect", "话题").put("object_ref", "游戏")
            .put("ownership", "accommodation").put("orientation", "uncertain").put("horizon", "contextual"))
        adoptRecord(preference("astronomy").put("horizon", "short_term").put("orientation", "neutral"))
        states.event(role, one, "soft-response", "assistant_reply", "这一次温和表达，陪对方聊游戏")
        val records = InnerStructure.records(states.read(role)).associateBy { it.getString("id") }
        assertEquals("long_term", records.getValue("direct").getJSONObject("content").getString("horizon"))
        assertEquals("accommodation", records.getValue("game").getJSONObject("content").getString("ownership"))
        assertEquals("uncertain", records.getValue("game").getJSONObject("content").getString("orientation"))
        assertEquals("neutral", records.getValue("astronomy").getJSONObject("content").getString("orientation"))
        assertTrue(sections(two, "游戏").state.contains("accommodation"))
        val f = frame()
        val userSource = f.eventIds.first { it.startsWith("input:") }
        InnerStructureContext.submit(context, f, report(f, propose("user-p", preference("suggested"), origin = "user_proposal", evidence = userSource)))
        assertFalse(InnerStructure.records(states.read(role)).any { it.getString("id") == "suggested" })
        assertEquals("user_proposal", InnerStructure.pending(states.read(role)).single().getString("origin"))
    }

    @Test fun personalityNeedsExactPriorProcessAndEmotionCannotOverwriteIt(): Unit = runBlocking {
        adoptRecord(content("self", "self_knowledge"))
        val before = InnerStructure.hash(InnerStructure.records(states.read(role)).single())
        adoptRecord(content("anger", "emotion").put("unresolved", true).put("current_state", "生气，但未解决的事仍在"))
        assertEquals(before, InnerStructure.hash(InnerStructure.records(states.read(role)).first { it.getString("id") == "self" }))
        val f = frame()
        val source = f.eventIds.first { it.startsWith("input:") }
        InnerStructureContext.submit(context, f, report(f, propose("now", content("new-self", "personality"), origin = "subject_development", evidence = source)))
        val later = frame()
        rejected { InnerStructureContext.submit(context, later, report(later,
            adoption("now").put("development", development("now", "继续刚才的事", source)))) }
        val initialId = proposeRecord(content("new-value", "value"))
        val bad = frame()
        rejected { InnerStructureContext.submit(context, bad, report(bad,
            adoption(initialId).put("development", development(initialId, "原文没有这句话")))) }
        val wrongBefore = frame()
        rejected { InnerStructureContext.submit(context, wrongBefore, report(wrongBefore,
            adoption(initialId).put("development", development(initialId).put("before", JSONObject())))) }
        assertEquals(2, InnerStructure.records(states.read(role)).size)
    }

    @Test fun relationshipEditorStoresAttributedSuggestionAndAdoptedViewHasSingleAuthority(): Unit = runBlocking {
        val network = RelationshipNetwork(context)
        val suggestion = network.save(null, role, other, "朋友", "用户提出的理解", "尚未采用", "真实输入", one)
        assertEquals("proposal_recorded", suggestion.getString("status"))
        assertTrue(InnerStructure.records(states.read(role)).isEmpty())
        assertFalse(GroupStore(context).read("relationships").has("$role:$other"))
        val event = InternalStateEngine.rows(states.read(role), "events").single { it.getString("id") == suggestion.getString("event_id") }
        assertEquals("user_relation_proposal", event.getString("source"))
        assertEquals("user", event.getString("author"))
        assertTrue(event.getString("inner_source").contains("用户提出的理解"))
        assertTrue(sections(two, "毫不相关的本轮输入").state.contains(suggestion.getString("event_id")))
        adoptRecord(content("toward-other", "relation").put("object_ref", other).put("label", "朋友")
            .put("understanding", "这是我自己的认识").put("feeling", "有亲近，也可以生气"))
        assertEquals("subject_adopted", network.list(role).single().getString("adoption"))
        assertTrue(network.list(other).none { it.optString("adoption") == "subject_adopted" })
        assertTrue(InnerStructure.records(states.read(other)).isEmpty())
        val before = InnerStructure.hash(InnerStructure.records(states.read(role)).single())
        network.delete(null, role, other)
        assertEquals(before, InnerStructure.hash(InnerStructure.records(states.read(role)).single()))
        rejected { network.save(other, role, other, "覆盖", "", "", "", foreign) }
        withContext(AssistantScope.executionContext(role)) {
            rejected { network.save(null, role, other, "伪装用户", "", "", "", one) }
        }
    }

    @Test fun duplicateMalformedAndStaleReportsKeepAtomicBoundary(): Unit = runBlocking {
        val f = frame()
        val payload = report(f, propose("p", preference("p")))
        rejected { states.submit(f, payload) } // Missing host source verification must not be a bypass.
        val receipt = InnerStructureContext.submit(context, f, payload)
        val version = SubjectStateBoundary.revision(states.read(role))
        assertEquals(receipt.toString(), InnerStructureContext.submit(context, f, payload).toString())
        assertEquals(version, SubjectStateBoundary.revision(states.read(role)))
        val stale = frame()
        val next = frame(two)
        InnerStructureContext.submit(context, next, report(next, adoption("p")))
        rejected { InnerStructureContext.submit(context, stale, report(stale, adoption("p"))) }
        val invalid = frame()
        val before = InnerStructure.hash(states.read(role))
        rejected { InnerStructureContext.submit(context, invalid, report(invalid).put("inner", "not an array")) }
        assertEquals(before, InnerStructure.hash(states.read(role)))
        val sameRound = frame()
        rejected { InnerStructureContext.submit(context, sameRound, report(sameRound,
            propose("same", preference("same")), adoption("same"))) }
        assertFalse(InnerStructure.pending(states.read(role)).any { it.getString("id") == "same" })
        // Changing config after a successfully accepted report must not reapply or reject its receipt.
        cards.updateCharacterCard(cards.getCharacterCard(role).copy(characterSetting = "新初始参考"))
        assertEquals(receipt.toString(), InnerStructureContext.submit(context, f, payload).toString())
        assertEquals(1, InnerStructure.records(states.read(role)).size)
    }

    @Test fun historyUsesExistingArchiveAndOmittedLegacyReportKeepsNewState(): Unit = runBlocking {
        repeat(24) { adoptRecord(preference("p$it")) }
        val data = states.read(role)
        assertEquals(40, data.getJSONArray("inner_history").length())
        assertEquals(48L, data.getJSONObject("inner_structure").getLong("history_sequence"))
        val archive = CompanionStore(context).archivedStateRow("internal-state:$role", "inner_history", "inner:1")!!
        assertEquals("proposed", archive.getString("operation"))
        assertEquals(role, archive.getString("owner"))
        assertEquals(8, states.inspect(role, one, 40).getJSONArray("inner_history").length())
        val material = InnerStructure.material(data, "语言方式", 0)
        assertTrue(material.toString().length <= InnerStructure.MATERIAL_BUDGET)
        assertEquals(8, material.getJSONArray("catalogue").length())
        val seen = mutableSetOf<String>()
        repeat(12) {
            val next = JSONObject(sections().state.substringAfter("本轮名录与选择]\n").trim())
                .getJSONObject("inner_structure").getJSONArray("catalogue")
            repeat(next.length()) { i -> seen += next.getJSONObject(i).getString("id") }
        }
        assertTrue("new records must rotate independently of legacy selection", seen.size >= 19)
        val f = frame()
        val before = InnerStructure.hash(states.read(role).getJSONObject("inner_structure"))
        val oldFormat = report(f).apply { remove("inner") }
        InnerStructureContext.submit(context, f, oldFormat)
        assertEquals(before, InnerStructure.hash(states.read(role).getJSONObject("inner_structure")))
        val view = states.inspect(role, one, 8).getJSONObject("inner")
        assertEquals(8, view.getJSONArray("records").length())
        assertEquals(16, view.getInt("next_offset"))
    }

    @Test fun concurrentAdoptionsAcceptOneVersionWithoutLosingOtherCandidate(): Unit = runBlocking {
        val first = proposeRecord(preference("first"))
        val second = proposeRecord(preference("second"))
        val a = frame(one); val b = frame(two)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val writes = listOf(a to first, b to second).map { (f, id) -> pool.submit<Boolean> {
                start.await()
                try { runBlocking { InnerStructureContext.submit(context, f, report(f, adoption(id))) }; true }
                catch (_: InternalStateReportRejected) { false }
            } }
            start.countDown()
            assertEquals(1, writes.count { it.get(30, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(1, InnerStructure.records(states.read(role)).size)
            val remaining = InnerStructure.pending(states.read(role)).single().getString("id")
            val refreshed = frame()
            InnerStructureContext.submit(context, refreshed, report(refreshed, adoption(remaining)))
            assertEquals(2, InnerStructure.records(states.read(role)).size)
            assertTrue(InnerStructure.pending(states.read(role)).isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun explicitOldEvidenceReadThroughGetCanBeResolvedFromTheExistingArchive(): Unit = runBlocking {
        states.event(role, one, "older-fact", "assistant_reply", "我在先前窗口选择直说", author = role)
        val row = InternalStateEngine.rows(states.read(role), "events").single()
        // Move the actual host-recorded row across the existing compaction boundary.
        InternalStateArchive(java.io.File(context.filesDir, "companion/internal-state-history.sqlite"), "internal-state:$role").use {
            it.put("events", row); it.commit()
        }
        CompanionStore(context).updateSubject(role, "internal-state", "archive_fixture") { it.put("events", JSONArray()) }
        val read = withContext(Dispatchers.IO) { states.inspect(role, two, 0) }
        assertTrue(read.getJSONArray("events").toString().contains("older-fact"))
        val f = frame(two)
        assertFalse("old fact was fetched, not selected", "older-fact" in f.eventIds)
        InnerStructureContext.submit(context, f, report(f, propose("old-source", preference("past"),
            origin = "subject_development", evidence = "older-fact")))
        val after = frame()
        InnerStructureContext.submit(context, after, report(after, adoption("old-source")))
        assertEquals("assistant_reply", InnerStructure.records(states.read(role)).single().getJSONObject("evidence")
            .getJSONObject("older-fact").getString("source"))
        assertTrue(InnerStructure.records(states.read(other)).isEmpty())
    }

    @Test fun openNeedHierarchyAndUnresolvedFeelingHaveIndependentStorage(): Unit = runBlocking {
        adoptRecord(content("drive", "drive").put("direction", "探索未知"))
        adoptRecord(content("need", "need").put("level", "broad").put("parent_ref", "drive"))
        adoptRecord(content("specific", "need").put("level", "specific").put("parent_ref", "need"))
        adoptRecord(content("desire", "desire").put("parent_ref", "specific").put("object_ref", "一篇文章"))
        adoptRecord(content("sentiment", "sentiment").put("object_ref", "user").put("unresolved", true).put("current_state", "长期关心"))
        adoptRecord(content("feeling", "feeling").put("object_ref", "user").put("unresolved", true).put("current_state", "这次依然感到失落"))
        val f = frame()
        rejected { InnerStructureContext.submit(context, f, report(f,
            propose("cycle", content("need", "need").put("level", "specific").put("parent_ref", "specific"), expected = 1))) }
        assertEquals(6, InnerStructure.records(states.read(role)).size)
        assertTrue(InnerStructure.records(states.read(role)).single { it.getString("id") == "feeling" }.getJSONObject("content").getBoolean("unresolved"))
    }
}
