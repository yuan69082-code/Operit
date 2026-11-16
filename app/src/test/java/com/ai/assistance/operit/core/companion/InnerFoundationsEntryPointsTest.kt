package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
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

/**
 * Exercises a real role store, the real request selector and final-report submission.
 * Outputs are test fixtures, NOT real-model or physical-device evidence.
 */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InnerFoundationsEntryPointsTest {
    private lateinit var context: Context
    private lateinit var role: String
    private lateinit var secondRole: String
    private lateinit var chat: String
    private lateinit var secondChat: String
    private lateinit var states: InternalStateStore

    @Before fun setup(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        val cards = CharacterCardManager.getInstance(context)
        role = cards.createCharacterCard(CharacterCard("", "已有主体", characterSetting = "初始人设参考"))
        secondRole = cards.createCharacterCard(CharacterCard("", "其他独立助手"))
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit()
            .putBoolean("internal_state:$role", true).putBoolean("internal_state:$secondRole", true).commit()
        suspend fun window() = ChatHistory(title = "真实入口测试", messages = emptyList(), characterCardId = role).also {
            ChatHistoryManager.getInstance(context).saveChatHistory(it)
        }.id
        chat = window()
        secondChat = window()
        states = InternalStateStore(context)
    }

    private suspend fun frame(window: String, input: String): InternalStateFrame {
        val prepared = InnerStructureContext.prepare(context, role, window, true, input = input)
        assertTrue(prepared.state.contains("foundation"))
        return requireNotNull(prepared.internalStateFrame)
    }

    private fun report(frame: InternalStateFrame, episodes: List<JSONObject> = emptyList(), foundations: List<JSONObject> = emptyList()) =
        JSONObject().put("frame_id", frame.id).put("updates", JSONArray()).put("create", JSONArray())
            .put("episodes", JSONArray(episodes)).put("foundation", JSONArray(foundations))

    private fun episode(frame: InternalStateFrame, id: String, version: Int = 0, understanding: String = "当下有些难受") =
        JSONObject().put("op", "appraise").put("id", id).put("expected_version", version)
            .put("category_ref", "foundation:emotion:anger")
            .put("objects", JSONArray().put(if (id == "A") "matter:A" else "matter:B"))
            .put("goals", JSONArray().put("弄清事件"))
            .put("intensity", if (id == "A") 0.8 else 0.4)
            .put("importance", "重要但不强迫做出决定")
            .put("long_term_impact", "可能影响后续理解")
            .put("attention", "foreground").put("status", "unresolved")
            .put("understanding", understanding).put("basis", "input")
            .put("reason", "这次实际输入的主体解释")
            .put("evidence", JSONArray().put(frame.eventIds.first()))

    @Test fun twoAngerMattersEvolveIndependentlyAndRemainAcrossWindows(): Unit = runBlocking {
        val first = frame(chat, "事情A让我很在意，事情B也还没解决")
        assertTrue(first.foundationVersions.containsKey("foundation:drive:intimacy"))
        val receipt = InnerStructureContext.submit(context, first, report(first,
            listOf(episode(first, "A"), episode(first, "B"))))
        assertTrue(receipt.getBoolean("episodes_adopted"))
        val a = states.inspectCategory(role, chat, "episode", "A", 0).getJSONObject("item")
        val b = states.inspectCategory(role, chat, "episode", "B", 0).getJSONObject("item")
        assertEquals(0.8, a.getJSONObject("content").getDouble("intensity"), 0.0001)
        assertEquals(0.4, b.getJSONObject("content").getDouble("intensity"), 0.0001)
        val second = frame(secondChat, "现在对A有了新的认识，B仍然没有结束")
        val updatedA = episode(second, "A", 1, "我现在改变了对A的解释")
            .put("basis", "reinterpretation").put("intensity", 0.2).put("status", "processing")
        InnerStructureContext.submit(context, second, report(second, listOf(updatedA)))
        assertEquals(0.2, states.inspectCategory(role, secondChat, "episode", "A", 0)
            .getJSONObject("item").getJSONObject("content").getDouble("intensity"), 0.0001)
        assertEquals(b.getJSONObject("content").toString(), states.inspectCategory(role, secondChat,
            "episode", "B", 0).getJSONObject("item").getJSONObject("content").toString())
        val grouped = states.inspectCategory(role, chat, "episode_category", "foundation:emotion:anger", 0)
        assertTrue(grouped.getJSONArray("items").length() >= 2)
        assertFalse(states.read(secondRole).has("foundation_state"))
    }

    @Test fun malformedNewReportKeepsAuthorityAndNextValidTurnMayCommit(): Unit = runBlocking {
        val first = frame(chat, "这里发生真实输入")
        val before = states.read(role).getJSONObject("foundation_state").toString()
        val change = JSONObject().put("id", "foundation:drive:expression")
            .put("expected_version", 0).put("value", "bad-number")
            .put("reason", "想表达新想法").put("evidence", JSONArray().put(first.eventIds.first()))
        val fail = assertThrows(InternalStateReportIssue::class.java) {
            runBlocking { InnerStructureContext.submit(context, first, report(first, foundations = listOf(change))) }
        }
        assertEquals("foundation[0].value", fail.path)
        assertEquals(before, states.read(role).getJSONObject("foundation_state").toString())
        val next = nativeInnerRead(context, frame(secondChat, "现在仍想表达，但仍要实际自己决定"),
            "foundation", "foundation:drive:expression")
        val good = JSONObject(change.toString()).put("value", 0.6)
            .put("evidence", JSONArray().put(next.eventIds.first()))
        InnerStructureContext.submit(context, next, report(next, foundations = listOf(good)))
        assertEquals(1, states.read(role).getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:expression").getInt("version"))
    }

    @Test fun realFoundationMembersCanFormAConflictWithoutAutomaticAction(): Unit = runBlocking {
        var first = frame(chat, "亲近和休息都值得考虑，先理解真实情况")
        val intimacy = "foundation:drive:intimacy"
        val rest = "foundation:drive:rest"
        first = nativeInnerRead(context, first, "foundation", intimacy)
        first = nativeInnerRead(context, first, "foundation", rest)
        assertEquals(0, first.interactionMembers["foundation:$intimacy"])
        assertEquals(0, first.interactionMembers["foundation:$rest"])
        val members = JSONArray(listOf(intimacy, rest).map { id ->
            JSONObject().put("kind", "foundation").put("id", id).put("version", 0)
        })
        val union = JSONObject().put("id", "tradeoff-1").put("expected_version", 0)
            .put("members", members).put("objects", JSONArray().put("self"))
            .put("evidence", JSONArray().put(first.eventIds.first()))
            .put("interpretation", "我同时想亲近，也想休息，暂时没有强制答案")
            .put("decision", "允许我先整理一下，再决定如何表达")
            .put("status", "active")
            .put("effects", JSONArray().put(JSONObject()
                .put("from", "foundation:$intimacy").put("to", "foundation:$rest")
                .put("meaning", "两者存在拉扯，但并不决定最后选择")))
        val r = report(first).put("unions", JSONArray().put(union))
        val output = InnerStructureContext.submit(context, first, r)
        assertTrue(output.getBoolean("unions_adopted"))
        val adopted = states.inspectCategory(role, chat, "union", "tradeoff-1", 0).getJSONObject("item")
        assertEquals("active", adopted.getJSONObject("content").getString("status"))
        assertEquals(0, states.read(role).getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject(intimacy).getInt("version"))
        // Persisted interpretation is present for the next real selector; no tool is executed.
        val second = InnerStructureContext.prepare(context, role, secondChat, true,
            input = "现在继续考虑上次的拉扯")
        assertTrue(second.state.contains("foundation"))
    }

    @Test fun anUnscoredFeelingSurvivesSubmissionReadingAndLaterClarification(): Unit = runBlocking {
        val first = frame(chat, "有点空，也有点酸，但说不清强度")
        val feeling = episode(first, "uncertain")
            .put("category_ref", "foundation:feeling:loneliness")
            .put("understanding", "有点空，也有点酸，但说不清强度")
            .put("intensity", JSONObject.NULL)
        InnerStructureContext.submit(context, first, report(first, listOf(feeling)))
        val restarted = InternalStateStore(context)
        val saved = restarted.inspectCategory(role, secondChat, "episode", "uncertain", 0)
            .getJSONObject("item").getJSONObject("content")
        assertTrue(saved.has("intensity"))
        assertTrue(saved.isNull("intensity"))
        assertEquals(feeling.getString("understanding"), saved.getString("understanding"))
        val next = InnerStructureContext.prepare(context, role, secondChat, true, input = "后来更明白了这种感觉")
        assertTrue(next.state.contains("有点空，也有点酸，但说不清强度"))
        val nextFrame = requireNotNull(next.internalStateFrame)
        val clarified = JSONObject(feeling.toString()).put("expected_version", 1)
            .put("intensity", 0.3).put("understanding", "现在更能分辨这种感觉了")
            .put("evidence", JSONArray().put(nextFrame.eventIds.first()))
        InnerStructureContext.submit(context, nextFrame, report(nextFrame, listOf(clarified)))
        val history = restarted.inspectCategory(role, secondChat, "category_versions", "episode:uncertain", 0)
            .getJSONArray("items")
        assertEquals(2, history.length())
        assertTrue((0 until history.length()).any { history.getJSONObject(it).getJSONObject("content").isNull("intensity") })
        assertFalse(restarted.read(secondRole).has("foundation_state"))
    }

    @Test fun unknownIntensityCannotBypassOtherCategoriesOrCommitHalfAReport(): Unit = runBlocking {
        val first = frame(chat, "这次输入需要保留真实的校验结果")
        val bad = episode(first, "invalid").put("intensity", JSONObject.NULL)
        val good = episode(first, "not-partially-saved")
        val failure = assertThrows(InternalStateReportIssue::class.java) {
            runBlocking { InnerStructureContext.submit(context, first, report(first, listOf(good, bad))) }
        }
        assertEquals("episodes[1].intensity", failure.path)
        assertEquals("ISR_UNKNOWN_INTENSITY_REQUIRES_FEELING", failure.code)
        assertTrue(states.inspectCategory(role, chat, "episode", "not-partially-saved", 0).isNull("item"))
        val corrected = episode(first, "valid")
        InnerStructureContext.submit(context, first, report(first, listOf(corrected)))
        assertFalse(states.inspectCategory(role, chat, "episode", "valid", 0).isNull("item"))
    }

    @Test fun aReportCanPreserveMoreThanEightIndependentMatters(): Unit = runBlocking {
        val first = frame(chat, "这些不同事情都需要分别承接")
        val changes = (1..12).map { episode(first, "matter-$it") }
        InnerStructureContext.submit(context, first, report(first, changes))
        changes.forEach { change ->
            assertEquals(1, states.inspectCategory(role, secondChat, "episode", change.getString("id"), 0)
                .getJSONObject("item").getInt("version"))
        }
    }

    @Test fun everyInstalledExperientialCategoryUsesTheNormalReportAndArchivePath(): Unit = runBlocking {
        // These are controlled provider-style candidates, not claims that a real subject
        // ought to experience every feeling. No final database state is injected.
        val categories = InnerFoundations.kinds.filter { it.family in setOf("drive", "need", "desire", "emotion", "sentiment", "feeling") }
        for (batch in categories.chunked(10)) {
            val f = frame(chat, "核对这些输入的承接：" + batch.joinToString { it.name })
            val changes = batch.map { kind -> episode(f, "coverage-${kind.family}-${kind.key}")
                .put("category_ref", kind.id).put("understanding", "受控输出：仍在理解具体事情，并未决定行动")
                .put("intensity", if (kind.family == "feeling") JSONObject.NULL else 0.25) }
            val receipt = InnerStructureContext.submit(context, f, report(f, changes))
            assertTrue(receipt.getBoolean("episodes_adopted"))
        }
        val restored = InternalStateStore(context)
        categories.forEach { kind ->
            val id = "coverage-${kind.family}-${kind.key}"
            val saved = restored.inspectCategory(role, secondChat, "episode", id, 0).getJSONObject("item")
            assertEquals(kind.id, saved.getString("parent"))
            assertEquals(1, saved.getInt("version"))
            assertEquals("unresolved", saved.getJSONObject("content").getString("status"))
            assertTrue(saved.getJSONObject("content").getJSONObject("original_sources").length() > 0)
            assertEquals(1, restored.inspectCategory(role, secondChat, "category_versions", "episode:$id", 0)
                .getJSONArray("items").length())
        }
        val next = InnerStructureContext.prepare(context, role, secondChat, true, input = "继续理解之前的事情")
        assertTrue(next.state.contains("受控输出：仍在理解具体事情"))
        assertFalse(restored.read(secondRole).has("foundation_state"))
    }

    @Test fun adoptedUnionTimeRuleAffectsLaterReadsWithoutOverwritingNewMemberAppraisals(): Unit = runBlocking {
        val first = frame(chat, "两件事情带来了不同体验")
        InnerStructureContext.submit(context, first, report(first, listOf(episode(first, "A"), episode(first, "B"))))
        suspend fun readMembers(): InternalStateFrame {
            var f = frame(secondChat, "继续理解这两件事之间的作用")
            f = nativeInnerRead(context, f, "episode", "A")
            return nativeInnerRead(context, f, "episode", "B")
        }
        val f = readMembers()
        val union = JSONObject().put("id", "time-union").put("expected_version", 0)
            .put("members", JSONArray(listOf("A", "B").map { id -> JSONObject()
                .put("kind", "episode").put("id", id).put("version", 1) }))
            .put("objects", JSONArray().put("self")).put("evidence", JSONArray().put(f.eventIds.first()))
            .put("interpretation", "本测试模拟主体明确给出节奏，不证明真实模型的心理结论")
            .put("decision", "暂未决定行动").put("status", "active")
            .put("effects", JSONArray().put(JSONObject().put("from", "episode:A").put("to", "episode:B")
                .put("meaning", "明确采用这次节奏，成员仍独立")
                .put("time_rule", JSONObject().put("curve", "linear").put("target", 0.9).put("seconds", 3600))))
        val submitted = report(f).put("unions", JSONArray().put(union))
        val receipt = InnerStructureContext.submit(context, f, submitted)
        assertEquals(receipt.toString(), InnerStructureContext.submit(context, f, submitted).toString())
        val data = InternalStateStore(context).read(role)
        val adopted = CategoryEvents.row(data, "union", "time-union")!!
        val b = CategoryEvents.row(data, "episode", "B")!!
        val projection = CategoryEvents.timeView(data, b, adopted.getLong("at") + 3_600_000L)
        assertEquals(0.9, projection.getJSONObject("time_projection").getDouble("intensity"), 0.00001)
        assertEquals(0.4, b.getJSONObject("content").getDouble("intensity"), 0.00001)
        assertEquals("unresolved", b.getJSONObject("content").getString("status"))
        assertEquals("subject_adopted_time_rule", states.inspectCategory(role, secondChat, "episode", "B", 0)
            .getJSONObject("item").getJSONObject("time_projection").getString("basis"))
        // The normal next request, not just the page, consumes the same projection.
        states.materialBudget(role, chat, 16384)
        val next = InnerStructureContext.prepare(context, role, chat, true, input = "继续这些已经形成的理解")
        assertTrue(next.state.contains("subject_adopted_time_rule"))
        val n = requireNotNull(next.internalStateFrame)
        InnerStructureContext.submit(context, n, report(n, listOf(episode(n, "B", 1).put("intensity", 0.2))))
        val after = states.inspectCategory(role, chat, "episode", "B", 0).getJSONObject("item")
        assertEquals(2, after.getInt("version"))
        assertEquals(0.2, after.getJSONObject("content").getDouble("intensity"), 0.00001)
        assertNotEquals("subject_adopted_time_rule", after.getJSONObject("time_projection").getString("basis"))
        assertEquals(1, states.inspectCategory(role, chat, "episode", "A", 0).getJSONObject("item").getInt("version"))
    }

    @Test fun unionTimeRulesCannotChangeSurvivalOrPartiallySaveOtherEffects(): Unit = runBlocking {
        var f = frame(chat, "允许考虑不同方面，但求生欲不能自动改变")
        val drive = "foundation:drive:rest"
        val survival = "foundation:need:survival"
        f = nativeInnerRead(context, f, "foundation", drive)
        f = nativeInnerRead(context, f, "foundation", survival)
        val union = JSONObject().put("id", "not-allowed").put("expected_version", 0)
            .put("members", JSONArray(listOf(drive, survival).map { JSONObject().put("kind", "foundation")
                .put("id", it).put("version", f.interactionMembers.getValue("foundation:$it")) }))
            .put("objects", JSONArray()).put("evidence", JSONArray().put(f.eventIds.first()))
            .put("interpretation", "尝试非法自动作用").put("decision", "没有外部操作").put("status", "active")
            .put("effects", JSONArray().put(JSONObject().put("from", "foundation:$drive").put("to", "foundation:$survival")
                .put("meaning", "此规则应被拒绝").put("time_rule", JSONObject().put("curve", "hold"))))
        assertThrows(InternalStateReportIssue::class.java) {
            runBlocking { InnerStructureContext.submit(context, f, report(f).put("unions", JSONArray().put(union))) }
        }
        assertTrue(states.inspectCategory(role, chat, "union", "not-allowed", 0).isNull("item"))
        assertFalse(states.inspectCategory(role, chat, "foundation", survival, 0).getJSONObject("item").getBoolean("formed"))
    }

    @Test fun foundationChangesAreArchivedAndReadAsVersionHistoryAfterRestart(): Unit = runBlocking {
        val id = "foundation:need:survival"
        repeat(2) { version ->
            val f = frame(chat, "明确调整求生欲，允许没有理由")
            val change = JSONObject().put("id", id).put("expected_version", version)
                .put("value", if (version == 0) 0.7 else 0.0)
                .put("objects", JSONArray().put("person:$version"))
                .put("evidence", JSONArray().put(f.eventIds.first()))
            InnerStructureContext.submit(context, f, report(f, foundations = listOf(change)))
        }
        val restarted = InternalStateStore(context)
        val rows = restarted.inspectCategory(role, secondChat, "foundation_history", id, 0).getJSONArray("items")
        assertEquals(2, rows.length())
        assertEquals(2, rows.getJSONObject(0).getInt("version"))
        val transition = rows.getJSONObject(0).getJSONObject("content")
        assertEquals("person:0", transition.getJSONObject("before").getJSONArray("objects").getString(0))
        assertEquals("person:1", transition.getJSONObject("after").getJSONArray("objects").getString(0))
        assertEquals(0.0, restarted.inspectCategory(role, secondChat, "foundation", id, 0)
            .getJSONObject("item").getDouble("current_value"), 0.0)
    }

    @Test fun unformedSurvivalDoesNotMaterializeAsAnInventedExperience(): Unit = runBlocking {
        frame(chat, "今天聊聊真实经历，但并没有报告求生欲")
        val survival = states.inspectCategory(role, chat, "foundation", "foundation:need:survival", 0).getJSONObject("item")
        assertEquals("not_formed", survival.getString("state"))
        assertFalse(survival.has("current_value"))
        assertEquals(9, states.inspectCategory(role, chat, "foundation", "emotion", 0)
            .getInt("count"))
    }
}
