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
        val next = frame(secondChat, "现在仍想表达，但仍要实际自己决定")
        val good = JSONObject(change.toString()).put("value", 0.6)
            .put("evidence", JSONArray().put(next.eventIds.first()))
        InnerStructureContext.submit(context, next, report(next, foundations = listOf(good)))
        assertEquals(1, states.read(role).getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:expression").getInt("version"))
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
