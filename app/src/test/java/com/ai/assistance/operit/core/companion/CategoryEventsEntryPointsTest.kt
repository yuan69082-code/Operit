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
        val prepared = frame()
        val f = if (expected == 0) prepared else nativeInnerRead(context, prepared, "inner", c.getString("id"))
        submit(f, report(f, inner = listOf(p)))
        val a = JSONObject().put("op", "adopt").put("id", id)
        if (!omitReason) a.put("reason", "明确采用")
        val next = nativeInnerRead(context, frame(second), "inner_pending", id)
        submit(next, report(next, inner = listOf(a)))
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
    private fun nativeRead(section: String, id: String): com.ai.assistance.operit.data.model.ToolResult {
        val handler = com.ai.assistance.operit.core.tools.AIToolHandler.getInstance(context)
        CompanionTools.register(handler, context)
        return handler.executeTool(com.ai.assistance.operit.data.model.AITool("companion_internal_state",
            mapOf("action" to "get", "section" to section, "id" to id,
                "__operit_package_chat_id" to chat, "__operit_package_caller_card_id" to role)
                .map { com.ai.assistance.operit.data.model.ToolParameter(it.key, it.value) }))
            .also { assertTrue(it.error, it.success) }
    }

    @Test fun unionAdoptionSharesTheReportTransactionAndDoesNotReplayMembers(): Unit = runBlocking {
        category(); category("longing")
        suspend fun unionFrame(): InternalStateFrame {
            val sections = InnerStructureContext.prepare(context, role, chat, true, input = "继续理解")
            val original = requireNotNull(sections.internalStateFrame)
            val reads = listOf("unwilling", "longing").filter { "inner:$it" !in original.interactionMembers }
                .map { nativeRead("inner", it) }
            if (reads.isEmpty()) return original
            val turns = com.ai.assistance.operit.core.chat.CurrentRequestContext.stage(
                listOf(com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.USER, "继续理解")), sections) +
                com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.TOOL_RESULT,
                    com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager.buildToolResultMessage(reads), "companion_internal_state")
            return requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(
                SubjectToolMaterials.attach(turns, System.currentTimeMillis(), reads)))
        }
        fun union(f: InternalStateFrame, version: Int) = JSONObject().put("id", "u").put("expected_version", version)
            .put("members", JSONArray(listOf("unwilling", "longing").map {
                JSONObject().put("kind", "inner").put("id", it).put("version", f.interactionMembers.getValue("inner:$it"))
            })).put("objects", JSONArray().put("user")).put("evidence", JSONArray().put(f.eventIds.first()))
            .put("interpretation", "亲近与失落同时存在").put("decision", "尚未决定行动").put("status", "active")
            .put("effects", JSONArray().put(JSONObject().put("from", "inner:unwilling").put("to", "inner:longing").put("meaning", "重新理解，暂不改值")))
        val f = unionFrame()
        val r = report(f).put("unions", JSONArray().put(union(f, 0)))
        val before = InnerStructure.hash(JSONArray(InnerStructure.records(states.read(role))))
        val receipt = submit(f, r)
        assertEquals(receipt.toString(), submit(f, r).toString())
        assertEquals(before, InnerStructure.hash(JSONArray(InnerStructure.records(states.read(role)))))
        assertTrue(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("亲近与失落同时存在"))
        val next = unionFrame()
        val bad = union(next, 1).put("members", JSONArray().put(JSONObject().put("kind", "inner").put("id", "missing").put("version", 1)))
        rejects { submit(next, report(next).put("episodes", JSONArray().put(appraisal(next, "atomic"))).put("unions", JSONArray().put(bad))) }
        assertTrue(states.inspectCategory(role, chat, "episode", "atomic", 0).isNull("item"))
        val end = unionFrame()
        submit(end, report(end).put("unions", JSONArray().put(union(end, 1).put("status", "ended"))))
        assertEquals(2, states.inspectCategory(role, chat, "category_versions", "union:u", 0).getJSONArray("items").length())
    }

    @Test fun nativeOnDemandReadSuppliesOmittedMembersForNormalUnionReport(): Unit = runBlocking {
        states.materialBudget(role, chat, 16384)
        for (id in listOf("left", "right"))
            adopt(content(id).put("description", "持续内容".repeat(150)).put("unresolved", true).put("current_state", "保留独立理解"))
        states.materialBudget(role, chat, 2048)
        val sections = InnerStructureContext.prepare(context, role, chat, true, input = "取回这两种理解")
        val first = requireNotNull(sections.internalStateFrame)
        assertTrue(listOf("left", "right").any { "inner:$it" !in first.interactionMembers })
        val results = listOf("left", "right").map { nativeRead("inner", it) }
        val turns = com.ai.assistance.operit.core.chat.CurrentRequestContext.stage(
            listOf(com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.USER, "取回这两种理解")), sections) +
            com.ai.assistance.operit.core.chat.hooks.PromptTurn(
                com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.TOOL_RESULT,
                com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager.buildToolResultMessage(results), "companion_internal_state")
        val read = SubjectToolMaterials.attach(turns, System.currentTimeMillis(), results)
        val f = requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(read))
        val u = JSONObject().put("id", "on-demand").put("expected_version", 0)
            .put("members", JSONArray(listOf("left", "right").map {
                JSONObject().put("kind", "inner").put("id", it).put("version", f.interactionMembers.getValue("inner:$it"))
            })).put("objects", JSONArray()).put("evidence", JSONArray().put(f.materialSources.keys.last()))
            .put("interpretation", "实际取回后共同理解").put("decision", "暂不行动").put("status", "active").put("effects", JSONArray())
        submit(f, report(f).put("unions", JSONArray().put(u)))
        states.materialBudget(role, chat, 16384)
        assertTrue(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("实际取回后共同理解"))
        assertEquals(1, states.inspectCategory(role, chat, "union", "on-demand", 0).getJSONObject("item").getInt("version"))
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

    @Test fun opposingChangesAndCyclicMeaningsCommitOnceWithoutReplayingOldUnion(): Unit = runBlocking {
        states.materialBudget(role, chat, 16384)
        category(); category("longing")
        val first = frame()
        submit(first, report(first, listOf(appraisal(first, "left"), appraisal(first, "right", category = "longing"))))
        fun union(f: InternalStateFrame) = JSONObject().put("id", "two-way").put("expected_version", 0)
            .put("members", JSONArray(listOf("left", "right").map {
                JSONObject().put("kind", "episode").put("id", it).put("version", f.interactionMembers.getValue("episode:$it"))
            })).put("objects", JSONArray().put("user")).put("evidence", JSONArray().put(f.eventIds.first()))
            .put("interpretation", "亲近和失落相互影响但方向不同").put("decision", "尚未决定").put("status", "active")
            .put("effects", JSONArray().put(JSONObject().put("from", "episode:left").put("to", "episode:right").put("meaning", "重新认识亲近"))
                .put(JSONObject().put("from", "episode:right").put("to", "episode:left").put("meaning", "仍然在意此事")))
        val f = frame()
        val stale = frame(second)
        val candidate = report(f, listOf(appraisal(f, "left", 1).put("intensity", 0.9),
            appraisal(f, "right", 1, "longing").put("intensity", 0.2))).put("unions", JSONArray().put(union(f)))
        val receipt = submit(f, candidate)
        assertEquals(receipt.toString(), submit(f, candidate).toString())
        rejects { submit(stale, report(stale, listOf(appraisal(stale, "left", 1)))) }
        assertEquals(0.9, episode("left").getJSONObject("content").getDouble("intensity"), 0.0001)
        assertEquals(0.2, episode("right").getJSONObject("content").getDouble("intensity"), 0.0001)
        val next = frame(second)
        submit(next, report(next, listOf(appraisal(next, "left", 2).put("intensity", 0.3))))
        repeat(3) { CompanionContext.compose(context, second, role, JSONObject(), false) }
        assertEquals(0.3, episode("left").getJSONObject("content").getDouble("intensity"), 0.0001)
        assertEquals(1, states.inspectCategory(role, chat, "union", "two-way", 0).getJSONObject("item").getInt("version"))
        assertEquals(3, states.inspectCategory(role, chat, "category_versions", "episode:left", 0).getJSONArray("items").length())
        assertTrue(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("亲近和失落相互影响"))
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
        states.materialBudget(role, chat, 16384)
        adopt(content("life", "need").put("level", "broad").put("semantic_rule", "life"))
        fun survival(strength: Double) = content("survival", "need").put("level", "specific").put("parent_ref", "life")
            .put("semantic_rule", "survival").put("strength", strength).put("objects", JSONArray().put("user").put("friend"))
            .put("goals", JSONArray().put("继续了解世界").put("完成自己的愿望"))
        adopt(survival(0.4), omitReason = true)
        val f = frame()
        val union = JSONObject().put("id", "survival-union").put("expected_version", 0)
            .put("members", JSONArray(listOf("life", "survival").map {
                JSONObject().put("kind", "inner").put("id", it).put("version", f.interactionMembers.getValue("inner:$it"))
            })).put("objects", JSONArray().put("user")).put("evidence", JSONArray().put(f.eventIds.first()))
            .put("interpretation", "生的需要与求生欲有联系但不相互代替").put("decision", "无需行动").put("status", "active")
            .put("effects", JSONArray().put(JSONObject().put("from", "inner:life").put("to", "inner:survival").put("meaning", "理解关联，未提出强度变化")))
        submit(f, report(f).put("unions", JSONArray().put(union)))
        val before = InnerStructure.hash(JSONArray(InnerStructure.records(states.read(role))))
        val projection = states.read(role)
        InternalStateEngine.advance(projection, chat, System.currentTimeMillis() + 1000000000)
        assertEquals(before, InnerStructure.hash(JSONArray(InnerStructure.records(projection))))
        adopt(survival(0.0), 1, omitReason = true)
        assertEquals(0.0, InnerStructure.records(states.read(role)).single { it.getString("id") == "survival" }.getJSONObject("content").getDouble("strength"), 0.0)
        assertTrue(states.enabled(role)); assertNotNull(frame(second))
        assertEquals(2, InnerStructure.records(states.read(role)).single { it.getString("id") == "survival" }.getJSONObject("content").getJSONArray("objects").length())
        CompanionContext.compose(context, second, role, JSONObject(), false)
        assertEquals(0.0, InnerStructure.records(states.read(role)).single { it.getString("id") == "survival" }.getJSONObject("content").getDouble("strength"), 0.0)
    }

    @Test fun unionsPageHistoryWithoutEmbeddingAllRowsInHotState(): Unit = runBlocking {
        states.materialBudget(role, chat, 16384)
        category(); category("longing")
        repeat(5) { batch ->
            val f = frame()
            val unions = JSONArray((0..3).map { n ->
                JSONObject().put("id", "history-${batch * 4 + n}").put("expected_version", 0)
                    .put("members", JSONArray(listOf("unwilling", "longing").map {
                        JSONObject().put("kind", "inner").put("id", it).put("version", f.interactionMembers.getValue("inner:$it"))
                    })).put("objects", JSONArray()).put("evidence", JSONArray().put(f.eventIds.first()))
                    .put("interpretation", "不同理解${batch * 4 + n}").put("decision", "未决定").put("status", "active").put("effects", JSONArray())
            })
            submit(f, report(f).put("unions", unions))
        }
        val fresh = InternalStateStore(context)
        assertEquals(8, fresh.inspectCategory(role, chat, "union", null, 0).getJSONArray("items").length())
        assertEquals(4, fresh.inspectCategory(role, chat, "union", null, 16).getJSONArray("items").length())
        assertTrue(fresh.read(role).getJSONObject("category_commit").getJSONArray("rows").length() <= 4)
        assertEquals(1, fresh.inspectCategory(role, chat, "union", "history-0", 0).getJSONObject("item").getInt("version"))
    }

    @Test fun sessionUnionDoesNotCrossWindowsAndRetiredMemberDoesNotBreakReading(): Unit = runBlocking {
        states.materialBudget(role, chat, 16384)
        category()
        states.event(role, chat, "definition-input", "user_message", "真实配置输入")
        fun direction() = JSONObject().put("condition", "always").put("reason", "本规则暂停")
            .put("interval_minutes", 0).put("amount", 0)
        val definition = JSONObject().put("id", "local").put("name", "局部").put("description", "窗口局部体验")
            .put("kind", "emotion").put("baseline", 0).put("min", 0).put("max", 100).put("scope", "session")
            .put("private", false).put("share", false).put("participates", true).put("effects", JSONArray())
            .put("time_rules", JSONObject().put("increase", direction()).put("decrease", direction()).put("floor", 0))
        states.evolve(role, chat, "create", "local", 0, definition, null, "明确建立", listOf("definition-input"))
        val f = frame()
        val union = JSONObject().put("id", "local-union").put("expected_version", 0)
            .put("members", JSONArray().put(JSONObject().put("kind", "state").put("id", "local").put("version", f.interactionMembers.getValue("state:local")))
                .put(JSONObject().put("kind", "inner").put("id", "unwilling").put("version", f.interactionMembers.getValue("inner:unwilling"))))
            .put("objects", JSONArray()).put("evidence", JSONArray().put(f.eventIds.first())).put("interpretation", "仅此窗口的理解")
            .put("decision", "未决定").put("status", "active").put("effects", JSONArray())
        submit(f, report(f).put("unions", JSONArray().put(union)))
        assertTrue(CompanionContext.compose(context, chat, role, JSONObject(), false).state.contains("仅此窗口的理解"))
        assertFalse(CompanionContext.compose(context, second, role, JSONObject(), false).state.contains("仅此窗口的理解"))
        rejects { states.inspectCategory(role, second, "union", "local-union", 0) }
        rejects { states.inspectCategory(role, second, "category_versions", "union:local-union", 0) }
        assertEquals(0, states.inspectCategory(role, second, "union", null, 0).getJSONArray("items").length())
        states.evolve(role, chat, "deactivate", "local", 1, null, null, "明确停用但保留经历", listOf("definition-input"))
        assertTrue(CompanionContext.compose(context, chat, role, JSONObject(), false).state.contains("仅此窗口的理解"))
        assertEquals(1, states.inspectCategory(role, chat, "union", "local-union", 0).getJSONObject("item").getInt("version"))
        val sections = InnerStructureContext.prepare(context, role, chat, true, input = "结束已不适用的联合")
        val result = nativeRead("state", "local")
        val request = com.ai.assistance.operit.core.chat.CurrentRequestContext.stage(
            listOf(com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.USER, "结束")), sections) +
            com.ai.assistance.operit.core.chat.hooks.PromptTurn(com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.TOOL_RESULT,
                com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager.buildToolResultMessage(listOf(result)), "companion_internal_state")
        val afterRead = requireNotNull(com.ai.assistance.operit.core.chat.CurrentRequestContext.internalStateFrame(
            SubjectToolMaterials.attach(request, System.currentTimeMillis(), listOf(result))))
        val end = JSONObject(union.toString()).put("expected_version", 1).put("status", "ended")
            .put("evidence", JSONArray().put(afterRead.materialSources.keys.last()))
        end.getJSONArray("members").getJSONObject(0).put("version", afterRead.interactionMembers.getValue("state:local"))
        rejects { submit(afterRead, report(afterRead).put("unions", JSONArray().put(JSONObject(end.toString()).put("status", "active")))) }
        submit(afterRead, report(afterRead).put("unions", JSONArray().put(end)))
        assertEquals("ended", states.inspectCategory(role, chat, "union", "local-union", 0).getJSONObject("item").getJSONObject("content").getString("status"))
        assertFalse(InternalStateEngine.definitions(states.read(role)).single { it.getString("id") == "local" }.getBoolean("active"))
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
        rejects { fresh.inspectCategory(other, second, "episode", null, 0) }
        val otherWindow = ChatHistory(title = "另一助手", messages = emptyList(), characterCardId = other)
        ChatHistoryManager.getInstance(context).saveChatHistory(otherWindow)
        assertEquals(0, fresh.inspectCategory(other, otherWindow.id, "episode", null, 0).getJSONArray("items").length())
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
        // Batch4 allows multiple members from one bounded disk page; the shared final
        // token selector, not this per-module character cap, controls the actual request.
        assertTrue(material.toString().length <= 8 * 16000)
        assertEquals(8, material.getJSONArray("episodes").length())
        val actual = CompanionContext.compose(context, chat, role, JSONObject(), false).state
        assertTrue(SubjectMaterialBudget.estimate(actual) <= SubjectMaterialBudget.DEFAULT_TOKENS)
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
