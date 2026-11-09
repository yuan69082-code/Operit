package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
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

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CategoryEventsEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var other: String
    private lateinit var chat: String
    private lateinit var second: String
    private lateinit var states: InternalStateStore
    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        val cards = CharacterCardManager.getInstance(context)
        role = cards.createCharacterCard(CharacterCard("", "主体", characterSetting = "初始参考"))
        other = cards.createCharacterCard(CharacterCard("", "另一主体"))
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit()
            .putBoolean("internal_state:$role", true).putBoolean("internal_state:$other", true).commit()
        suspend fun window(): String {
            val c = ChatHistory(title = "事件测试", messages = emptyList(), characterCardId = role)
            ChatHistoryManager.getInstance(context).saveChatHistory(c); return c.id
        }
        chat = window(); second = window(); states = InternalStateStore(context)
    }
    private suspend fun frame(window: String = chat) = requireNotNull(InnerStructureContext.prepare(context, role, window, true, input = "事情确实发生了，现在重新理解").internalStateFrame)
    private fun report(f: InternalStateFrame, events: List<JSONObject> = emptyList(), inner: List<JSONObject> = emptyList()) =
        JSONObject().put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray())
            .put("episodes", JSONArray(events)).put("inner", JSONArray(inner))
    private suspend fun submit(f: InternalStateFrame, r: JSONObject): JSONObject {
        // The same stream parser and host adapter as successful model round completion.
        val stream = InternalStateReportStream()
        val public = stream.append("<operit_state_report>$r</operit_state_report>正文") + stream.finish()
        assertEquals("正文", public)
        return InnerStructureContext.submit(context, f, requireNotNull(stream.report))
    }
    private fun content(id: String, family: String = "emotion") = JSONObject().put("id", id).put("family", family)
        .put("kind", id).put("description", "主体采用的类别").put("status", "active")
    private suspend fun adopt(c: JSONObject, expected: Int = 0, omitReason: Boolean = false) {
        val id = java.util.UUID.randomUUID().toString()
        val p = JSONObject().put("op", "propose").put("id", id).put("record", c).put("expected_version", expected)
            .put("origin", "initial_configuration").put("evidence", JSONArray().put("config:role_prompt"))
        if (!omitReason) p.put("reason", "明确提出")
        val f = frame(); submit(f, report(f, inner = listOf(p)))
        val a = JSONObject().put("op", "adopt").put("id", id)
        if (!omitReason) a.put("reason", "明确采用")
        val next = frame(second); submit(next, report(next, inner = listOf(a)))
    }
    private suspend fun category(id: String = "unwilling") = adopt(content(id).put("unresolved", true).put("current_state", "仍在理解"))
    private fun appraisal(f: InternalStateFrame, id: String, version: Int = 0, category: String = "unwilling", source: String = f.eventIds.first()) =
        JSONObject().put("op", "appraise").put("id", id).put("expected_version", version).put("category_ref", category)
            .put("objects", JSONArray().put("user")).put("goals", JSONArray().put("弄明白这件事"))
            .put("intensity", 0.6).put("importance", "对此事很在意").put("long_term_impact", "影响今后的理解")
            .put("attention", "foreground").put("status", "unresolved").put("understanding", "这是独立事情$id")
            .put("basis", "input").put("reason", "本次主观解释").put("evidence", JSONArray().put(source))
    private fun episode(id: String) = states.inspectCategory(role, chat, "episode", id, 0).getJSONObject("item")
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } catch (_: org.json.JSONException) { }
    }

    @Test fun unionAdoptionSharesTheReportTransactionAndDoesNotReplayMembers(): Unit = runBlocking {
        category(); category("longing")
        fun union(f: InternalStateFrame, version: Int) = JSONObject().put("id", "u").put("expected_version", version)
            .put("members", JSONArray(listOf("unwilling", "longing").map {
                JSONObject().put("kind", "inner").put("id", it).put("version", f.interactionMembers.getValue("inner:$it"))
            })).put("objects", JSONArray().put("user")).put("evidence", JSONArray().put(f.eventIds.first()))
            .put("interpretation", "亲近与失落同时存在").put("decision", "尚未决定行动").put("status", "active")
            .put("effects", JSONArray().put(JSONObject().put("from", "inner:unwilling").put("to", "inner:longing").put("meaning", "重新理解，暂不改值")))
        val f = frame()
        val r = report(f).put("unions", JSONArray().put(union(f, 0)))
        val before = InnerStructure.hash(states.read(role).getJSONObject("inner_structure").getJSONObject("records"))
        val receipt = submit(f, r)
        assertEquals(receipt.toString(), submit(f, r).toString())
        assertEquals(before, InnerStructure.hash(states.read(role).getJSONObject("inner_structure").getJSONObject("records")))
        assertTrue(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("亲近与失落同时存在"))
        val next = frame()
        val bad = union(next, 1).put("members", JSONArray().put(JSONObject().put("kind", "inner").put("id", "missing").put("version", 1)))
        rejects { submit(next, report(next).put("episodes", JSONArray().put(appraisal(next, "atomic"))).put("unions", JSONArray().put(bad))) }
        assertTrue(states.inspectCategory(role, chat, "episode", "atomic", 0).isNull("item"))
        val end = frame()
        submit(end, report(end).put("unions", JSONArray().put(union(end, 1).put("status", "ended"))))
        assertEquals(2, states.inspectCategory(role, chat, "category_versions", "union:u", 0).getJSONArray("items").length())
    }

    @Test fun independentEventsAndIndependentDimensionsThroughNormalReports(): Unit = runBlocking {
        category()
        val f = frame(); submit(f, report(f, listOf("A", "B", "C").map { appraisal(f, it) }))
        val b = episode("B").toString(); val c = episode("C").toString()
        val next = frame(second)
        submit(next, report(next, listOf(appraisal(next, "A", 1).put("intensity", 0).put("attention", "aside").put("basis", "reinterpretation"))))
        assertEquals(b, episode("B").toString()); assertEquals(c, episode("C").toString())
        assertEquals("unresolved", episode("A").getJSONObject("content").getString("status"))
        assertEquals("对此事很在意", episode("A").getJSONObject("content").getString("importance"))
        val finished = frame(); submit(finished, report(finished, listOf(appraisal(finished, "A", 2).put("status", "resolved"))))
        assertEquals("影响今后的理解", episode("A").getJSONObject("content").getString("long_term_impact"))
    }

    @Test fun sameSourceDifferentCategoriesAndRecollectionDoNotCreateReality(): Unit = runBlocking {
        category(); category("longing")
        states.event(role, chat, "actual-fact", "user_message", "原始事实不可更改")
        val f = frame()
        val r = report(f, listOf(appraisal(f, "A", source = "actual-fact"), appraisal(f, "B", category = "longing", source = "actual-fact")))
        val receipt = submit(f, r); assertEquals(receipt.toString(), submit(f, r).toString())
        val original = InternalStateEngine.rows(states.read(role), "events").single { it.getString("id") == "actual-fact" }.toString()
        val next = frame(); submit(next, report(next, listOf(appraisal(next, "A", 1, source = "actual-fact").put("basis", "recollection").put("understanding", "现在有了不同理解"))))
        assertEquals(original, InternalStateEngine.rows(states.read(role), "events").single { it.getString("id") == "actual-fact" }.toString())
        assertEquals("recollection", episode("A").getJSONObject("content").getString("basis"))
        assertTrue(episode("B").getJSONObject("content").getJSONObject("original_sources").has("actual-fact"))
    }

    @Test fun focusReplacementRetainsMoreThanSixEventsAndHistory(): Unit = runBlocking {
        category()
        for (i in 0..8) { val f = frame(); submit(f, report(f, listOf(appraisal(f, "event$i")))) }
        fun focus(version: Int, vararg ids: String) = JSONObject().put("op", "focus").put("id", "unwilling")
            .put("expected_version", version).put("capacity", 3).put("events", JSONArray(ids.toList())).put("reason", "由主体决定，保留长期意义")
        val f = frame(); submit(f, report(f, listOf(focus(0, "event0", "event8"))))
        val next = frame(); submit(next, report(next, listOf(focus(1, "event7"))))
        assertEquals("unresolved", episode("event0").getJSONObject("content").getString("status"))
        val page = states.inspectCategory(role, chat, "episode", null, 0)
        assertEquals(8, page.getJSONArray("items").length()); assertEquals(8, page.getInt("next_offset"))
        assertEquals(1, states.inspectCategory(role, chat, "episode", null, 8).getJSONArray("items").length())
        assertEquals(2, states.inspectCategory(role, chat, "category_versions", "focus:unwilling", 0).getJSONArray("items").length())
    }

    @Test fun survivalIsExplicitOptionalReasonZeroAndNotTimeDriven(): Unit = runBlocking {
        adopt(content("life", "need").put("level", "broad").put("semantic_rule", "life"))
        fun survival(strength: Double) = content("survival", "need").put("level", "specific").put("parent_ref", "life")
            .put("semantic_rule", "survival").put("strength", strength).put("objects", JSONArray().put("user").put("friend"))
            .put("goals", JSONArray().put("继续了解世界").put("完成自己的愿望"))
        adopt(survival(0.4), omitReason = true)
        val before = InnerStructure.hash(states.read(role).getJSONObject("inner_structure").getJSONObject("records"))
        val projection = states.read(role)
        InternalStateEngine.advance(projection, chat, System.currentTimeMillis() + 1000000000)
        assertEquals(before, InnerStructure.hash(projection.getJSONObject("inner_structure").getJSONObject("records")))
        adopt(survival(0.0), 1, omitReason = true)
        assertEquals(0.0, InnerStructure.records(states.read(role)).single { it.getString("id") == "survival" }.getJSONObject("content").getDouble("strength"), 0.0)
        assertTrue(states.enabled(role)); assertNotNull(frame(second))
        assertEquals(2, InnerStructure.records(states.read(role)).single { it.getString("id") == "survival" }.getJSONObject("content").getJSONArray("objects").length())
    }

    @Test fun preferenceLinksAccumulateOnlyAfterAdoption(): Unit = runBlocking {
        category(); val f = frame(); submit(f, report(f, listOf(appraisal(f, "A"), appraisal(f, "B"))))
        fun pref(ref: String) = content("direct", "preference").put("orientation", "uncertain").put("horizon", "contextual")
            .put("ownership", "accommodation").put("aspect", "语言方式").put("context", "陪对方交流").put("exceptions", "")
            .put("experience_refs", JSONArray().put(ref))
        adopt(pref("A")); adopt(pref("B"), 1)
        val links = states.inspectCategory(role, chat, "experience_link", "direct", 0).getJSONArray("items")
        assertEquals(2, links.length())
        val record = InnerStructure.records(states.read(role)).single { it.getString("id") == "direct" }.getJSONObject("content")
        assertEquals("accommodation", record.getString("ownership")); assertEquals("contextual", record.getString("horizon"))
        val next = frame(); rejects { submit(next, report(next, listOf(appraisal(next, "A", 1).put("category_ref", "direct")))) }
    }

    @Test fun restartAndNormalComposerSeeEventsButAnotherAssistantCannot(): Unit = runBlocking {
        category(); val f = frame(); submit(f, report(f, listOf(appraisal(f, "A"))))
        val fresh = InternalStateStore(context)
        assertEquals(1, fresh.inspectCategory(role, second, "episode", "A", 0).getJSONObject("item").getInt("version"))
        val next = CompanionContext.compose(context, second, role, JSONObject(), true, "继续")
        assertTrue(next.state.contains("这是独立事情A")); assertTrue(next.instructions.contains("归零不触发停止"))
        assertEquals(0, fresh.inspectCategory(other, second, "episode", null, 0).getJSONArray("items").length())
        withContext(AssistantScope.executionContext(other)) { rejects { fresh.inspectCategory(role, second, "episode", "A", 0) } }
    }

    @Test fun staleReportAndInvalidCandidateLeaveAllAcceptedEventsIntact(): Unit = runBlocking {
        category(); val f = frame(); val concurrent = frame(second)
        submit(f, report(f, listOf(appraisal(f, "A"))))
        rejects { submit(concurrent, report(concurrent, listOf(appraisal(concurrent, "B")))) }
        val next = frame()
        rejects { submit(next, report(next, listOf(appraisal(next, "B"), appraisal(next, "C").put("intensity", -1)))) }
        assertTrue(states.inspectCategory(role, chat, "episode", "B", 0).isNull("item"))
        assertEquals(1, episode("A").getInt("version"))
        val retry = frame(second); submit(retry, report(retry, listOf(appraisal(retry, "B"))))
        assertEquals(1, episode("B").getInt("version"))
    }

    @Test fun completedToolSourceIsAdoptedByTheNextNormalRequest(): Unit = runBlocking {
        category()
        states.tool(role, chat, "companion_study", "reply", "saved", true, "actual-reply")
        val f = frame(second)
        submit(f, report(f, listOf(appraisal(f, "reply-result", source = "tool:actual-reply").put("basis", "result"))))
        assertEquals("tool_success", episode("reply-result").getJSONObject("content").getJSONObject("source_bindings")
            .getJSONObject("tool:actual-reply").getString("source"))
        assertTrue(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("reply-result"))
    }

    @Test fun archiveRollbackLeavesNoHalfAdoptedIndexAndJournalCanReplay() {
        // Supplemental persistence fault test, not a substitute for the report entry-point tests.
        val file = java.io.File(context.cacheDir, "category-rollback-${java.util.UUID.randomUUID()}.sqlite")
        fun row(id: String) = JSONObject().put("storage_kind", "episode").put("id", id).put("parent", "emotion").put("version", 1)
        val journal = JSONObject().put("schema", 1).put("id", "committed-frame").put("rows", JSONArray().put(row("A")).put(row("B")))
        val broken = JSONObject(journal.toString())
        broken.getJSONArray("rows").getJSONObject(1).remove("parent")
        try {
            InternalStateArchive(file, "internal-state:$role").use { it.applyCategoryJournal(broken) }
            fail("expected interrupted transaction")
        } catch (_: org.json.JSONException) { }
        InternalStateArchive(file, "internal-state:$role").use { archive ->
            assertNull(archive.categoryRow("episode", "A"))
            archive.applyCategoryJournal(journal); archive.applyCategoryJournal(journal)
            assertEquals(2, archive.categoryPage("episode", 0, 9, null).size)
            assertEquals(1, archive.categoryVersions("episode:A", 0).size)
        }
    }

    @Test fun normalMaterialAndEventPagesRemainBoundedAsEpisodesAccumulate(): Unit = runBlocking {
        category()
        repeat(10) { batch ->
            val f = frame()
            submit(f, report(f, (0..7).map { appraisal(f, "bulk-${batch * 8 + it}") }))
        }
        val snapshot = states.read(role)
        val material = CategoryEvents.material(snapshot, false)
        assertTrue(material.toString().length <= 12000)
        assertEquals(1, material.getJSONArray("episodes").length())
        assertEquals(8, states.inspectCategory(role, chat, "episode", null, 72).getJSONArray("items").length())
        assertTrue(states.inspectCategory(role, chat, "episode", null, 80).getJSONArray("items").length() == 0)
        assertEquals("unresolved", episode("bulk-0").getJSONObject("content").getString("status"))
        assertEquals(8, snapshot.getJSONObject("category_commit").getJSONArray("rows").length())
        assertFalse(snapshot.has("category_current"))
    }

    @Test fun committedJournalCanResumeAfterInterruptionWithoutDuplicates(): Unit = runBlocking {
        category(); val f = frame(); val r = report(f, listOf(appraisal(f, "A")))
        submit(f, r)
        // submit writes the durable bounded journal; first subsequent disk read indexes it.
        val restarted = InternalStateStore(context)
        repeat(3) { restarted.read(role) }
        assertEquals(1, restarted.inspectCategory(role, chat, "category_versions", "episode:A", 0).getJSONArray("items").length())
        submit(f, r)
        assertEquals(1, restarted.inspectCategory(role, chat, "episode", "A", 0).getJSONObject("item").getInt("version"))
        assertTrue(restarted.read(role).getJSONObject("category_commit").getJSONArray("rows").length() <= 64)
    }
}
