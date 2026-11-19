package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AutonomousRuntimeControlTest {
    private lateinit var context: Context
    private lateinit var control: AutonomousRuntimeControl
    private lateinit var store: CompanionStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        CharacterCardManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        ChatHistoryManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        control = AutonomousRuntimeControl(context)
        store = CompanionStore(context)
        store.preferences.edit().remove("autonomous_enabled").remove("autonomous_pause_until").commit()
        store.update("autonomous-runtime") { it.put("phase", "waiting") }
    }
    @Test fun disabledAndTimedPauseBlockClaimsWithoutChangingPerFeaturePreferences() {
        store.preferences.edit().putBoolean("proactive_enabled", true).putBoolean("autonomous_enabled", false).commit()
        assertFalse(control.allowed(100))
        assertTrue(store.proactiveEnabled)
        store.preferences.edit().putBoolean("autonomous_enabled", true).putLong("autonomous_pause_until", 200).commit()
        assertFalse(control.allowed(199)); assertTrue(control.allowed(200))
        store.preferences.edit().putLong("autonomous_pause_until", Long.MAX_VALUE).commit()
        assertFalse(control.allowed(Long.MAX_VALUE))
    }
    @Test fun failedOrCrashedWorkPersistsPauseButOrdinaryCancellationDoesNot() {
        control.begin("a", "work")
        AutonomousRuntimeControl(context).recover()
        assertFalse(control.allowed())
        assertEquals("异常暂停", control.status("a").getString("state"))
        store.update("autonomous-runtime") { it.put("phase", "waiting"); it.remove("error") }
        control.begin("a", "work2"); control.finish("a", false, interrupted = true)
        assertTrue(control.allowed())
        control.begin("a", "work3"); control.finish("a", false)
        assertFalse(AutonomousRuntimeControl(context).allowed())
    }
    @Test fun idleDoesNotCatchUpAndPreservesDailyLimitAcrossRestart() {
        val now = System.currentTimeMillis()
        store.update("autonomous-idle:a") { it.put("enabled", true).put("interval_minutes", 10)
            .put("daily_limit", 1).put("next_at", now - 86400000) }
        assertNotNull(control.claimIdle("a", now))
        assertNull(control.claimIdle("a", now))
        assertNull(AutonomousRuntimeControl(context).claimIdle("a", now + 600001))
        assertEquals(now + 600000, control.idleConfig("a").getLong("next_at"))
        assertNull(control.claimIdle("unconfigured", now))
    }
    @Test fun modelCanPauseIdleWithoutDisablingLearningOrManufacturingState() {
        store.update("autonomous-idle:a") { it.put("enabled", true).put("interval_minutes", 10).put("daily_limit", 8).put("next_at", 1) }
        // Initial adoption creates the state file; subsequent reads expose its archive cursor.
        // Establish that existing storage projection before checking the idle operation.
        store.read("internal-state:a")
        val before = store.read("internal-state:a").toString()
        control.waitChoice("a", null, "暂时没有想继续的")
        assertNull(control.claimIdle("a", System.currentTimeMillis()))
        assertTrue(control.allowed())
        assertEquals(before, store.read("internal-state:a").toString())
        assertTrue(CompanionToolBudget(0).admit("companion_life", "runtime_wait"))
        assertFalse(CompanionToolBudget(0).admit("companion_life", "free_plan"))
    }
    @Test fun closingTurnPreservesExplicitContinuationAndEnd() {
        val life = SubjectLife(context)
        val ref = ActivityNotes.Ref("a", "chat", "activity")
        life.checkpoint(ref, "read", "executing")
        life.chooseActivity("a", "activity", "continue", "还有一点想接着看")
        life.checkpoint(ref, "read", "completed")
        assertEquals("ready", store.activityRead("a", "life_activity", "activity")!!.getString("parent"))
        life.checkpoint(ref, "read", "executing")
        life.chooseActivity("a", "activity", "end", "不继续了")
        life.checkpoint(ref, "read", "paused")
        assertEquals("ended", store.activityRead("a", "life_activity", "activity")!!.getString("parent"))
    }
    @Test fun clockSurvivesOtherWindowsAndHiddenNotesDoNotCountAsContact(): Unit = runBlocking {
        val cards = CharacterCardManager.getInstance(context)
        val role = cards.createCharacterCard(CharacterCard("", "时间", characterSetting = "初始"))
        val foreign = cards.createCharacterCard(CharacterCard("", "其他", characterSetting = "初始"))
        val history = ChatHistoryManager.getInstance(context)
        val chat = ChatHistory(title = "时间", messages = listOf(ChatMessage(sender = "user", content = "早上好", timestamp = 1000)), characterCardId = role)
        history.saveChatHistory(chat)
        history.saveChatHistory(ChatHistory(title = "另一窗口", messages = listOf(
            ChatMessage(sender = "ai", content = "随记", timestamp = 5000, displayMode = ChatMessageDisplayMode.HIDDEN_PLACEHOLDER)), characterCardId = role))
        history.saveChatHistory(ChatHistory(title = "他人", messages = listOf(ChatMessage(sender = "user", content = "其他", timestamp = 9000)), characterCardId = foreign))
        val clock = SubjectInteractionClock(context)
        clock.record(role, "user", 2000)
        clock.record(role, "contact", 3000)
        val actual = SubjectInteractionClock(context).snapshot(role, 6000)
        assertEquals(2000, actual.getLong("last_user"))
        assertEquals(4000, actual.getLong("last_user_elapsed_ms"))
        assertTrue(actual.isNull("last_exchange"))
        assertEquals(3000, actual.getLong("last_proactive_contact"))
        assertTrue(actual.isNull("last_assistant"))
        assertTrue(clock.snapshot(role, 1500).isNull("last_user_elapsed_ms"))
    }
}
