package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExplorationPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = "2026-10-14"
    private fun enabled(limit: Int = 4) = JSONObject().also {
        ExplorationPolicy.configure(it, true, setOf("reading"), limit, now)
    }

    @Test fun newAndLegacyConfigurationsNeverOptInImplicitly() {
        val data = JSONObject().put("proactive_enabled", true).put("learning_choices", JSONArray(listOf("web")))
            .put("chat", "private-a").put("at", now - 1).put("paused", false)
        assertFalse(ExplorationPolicy.enabled(JSONObject()))
        assertFalse(ExplorationPolicy.enabled(data))
        assertFalse(ExplorationPolicy.due(data, now, day))
        assertThrows(IllegalStateException::class.java) { ExplorationPolicy.schedule(data, 15, "继续", now) }
    }

    @Test fun eachAssistantRequiresItsOwnOptInAndSchedule() {
        val a = enabled()
        val b = JSONObject()
        assertFalse(ExplorationPolicy.due(a, now, day))
        assertTrue(ExplorationPolicy.due(a, now + 900_000, day))
        assertFalse(ExplorationPolicy.due(b, now + 900_000, day))
        ExplorationPolicy.configure(b, true, setOf("web"), 2, now)
        ExplorationPolicy.configure(a, false, emptySet(), 4, now)
        assertEquals(setOf("web"), ExplorationPolicy.choices(b))
        assertTrue(ExplorationPolicy.due(b, now + 900_000, day))
    }

    @Test fun disablingRevokesRunningWorkAndFutureScheduleWithoutDeletingRecords() {
        val a = enabled().put("saved_notes", "阅读后的随记")
        val revision = ExplorationPolicy.revision(a)
        assertTrue(ExplorationPolicy.claim(a, now + 900_000, day, "old", "private-a"))
        ExplorationPolicy.schedule(a, 60, "继续阅读", now + 900_000)
        assertTrue(ExplorationPolicy.valid(a, "private-a", revision, "old"))
        ExplorationPolicy.configure(a, false, setOf("reading"), 4, now + 900_001)
        assertFalse(a.has("at"))
        assertFalse(a.has("running"))
        assertFalse(ExplorationPolicy.valid(a, "private-a", revision, "old"))
        assertEquals("阅读后的随记", a.getString("saved_notes"))
        assertThrows(IllegalStateException::class.java) { ExplorationPolicy.schedule(a, 60, "过期继续", now) }
    }

    @Test fun reEnablingNeverResurrectsAnOldExecutionTicketOrResetsUsage() {
        val a = enabled()
        val revision = ExplorationPolicy.revision(a)
        ExplorationPolicy.claim(a, now + 900_000, day, "old", "private-a")
        ExplorationPolicy.configure(a, false, setOf("reading"), 4, now + 900_001)
        ExplorationPolicy.configure(a, true, setOf("reading"), 4, now + 900_002)
        assertFalse(ExplorationPolicy.valid(a, "private-a", revision, "old"))
        assertEquals(1, ExplorationPolicy.used(a, day))
        val freshAt = a.getLong("at")
        ExplorationPolicy.finish(a, "old", "failed")
        assertEquals(freshAt, a.getLong("at"))
        assertFalse(a.getBoolean("paused"))
    }

    @Test fun changingAssistantSettingsInvalidatesRunningWork() {
        val a = enabled()
        val revision = ExplorationPolicy.revision(a)
        ExplorationPolicy.claim(a, now + 900_000, day, "old", "private-a")
        ExplorationPolicy.configure(a, true, setOf("reading"), 4, now)
        assertFalse(ExplorationPolicy.valid(a, "private-a", revision, "old"))
        assertTrue(a.getBoolean("paused"))
    }

    @Test fun windowChangesKeepTheAssistantsScheduleAndDailyBudget() {
        val a = enabled()
        assertFalse(a.has("chat"))
        val revision = ExplorationPolicy.revision(a)
        val at = a.getLong("at")
        assertTrue(ExplorationPolicy.claim(a, at, day, "first", "private-a"))
        ExplorationPolicy.schedule(a, 30, "继续", at)
        ExplorationPolicy.finish(a, "first", "completed", at)
        val next = a.getLong("at")
        assertTrue(ExplorationPolicy.claim(a, next, day, "second", "private-b"))
        assertTrue(ExplorationPolicy.valid(a, "private-b", revision, "second"))
        assertFalse(ExplorationPolicy.valid(a, "private-a", revision, "second"))
        assertEquals(2, ExplorationPolicy.used(a, day))
        assertEquals("继续", a.getString("purpose"))
    }

    @Test fun claimedWorkCannotBeClaimedAgainAndUnscheduledTurnAutoContinues() {
        val a = enabled()
        assertTrue(ExplorationPolicy.claim(a, now + 900_000, day, "one", "private-a"))
        assertFalse(ExplorationPolicy.claim(a, now + 900_001, day, "two", "private-a"))
        ExplorationPolicy.finish(a, "one", "completed", now + 900_000)
        assertFalse(a.getBoolean("paused"))
        assertTrue(a.getBoolean("auto_continued"))
        assertEquals(now + 900_000 + ExplorationPolicy.AUTO_CONTINUE_MINUTES * 60_000L, a.getLong("at"))
        assertFalse(ExplorationPolicy.due(a, now + 900_001, day))
        assertTrue(ExplorationPolicy.due(a, a.getLong("at"), day))
    }

    @Test fun failuresConsumeBudgetAndBackOffLonger() {
        val a = enabled(1)
        ExplorationPolicy.claim(a, now + 900_000, day, "one", "private-a")
        ExplorationPolicy.finish(a, "one", "failed", now + 900_000)
        assertEquals(1, ExplorationPolicy.used(a, day))
        assertEquals(now + 900_000 + ExplorationPolicy.AUTO_CONTINUE_MINUTES * 2 * 60_000L, a.getLong("at"))
        ExplorationPolicy.schedule(a, 15, "手动重新安排", now + 900_000)
        assertFalse(ExplorationPolicy.due(a, now + 1_800_000, day))
        assertTrue(ExplorationPolicy.due(a, now + 1_800_000, "2026-10-15"))
    }

    @Test fun interruptedWorkKeepsTheAssistantsOwnPlan() {
        val a = enabled()
        ExplorationPolicy.claim(a, now + 900_000, day, "one", "private-a")
        ExplorationPolicy.schedule(a, 15, "未完成前预约", now + 900_000)
        assertTrue(ExplorationPolicy.recover(a))
        assertEquals("interrupted", a.getString("last_outcome"))
        assertEquals(now + 1_800_000, a.getLong("at"))
        assertFalse(a.optBoolean("auto_continued"))
        assertFalse(ExplorationPolicy.recover(a))
    }

    @Test fun pausingContinuationDoesNotInvalidateTheCurrentTurn() {
        val a = enabled()
        val revision = ExplorationPolicy.revision(a)
        ExplorationPolicy.claim(a, now + 900_000, day, "one", "private-a")
        ExplorationPolicy.selfPause(a)
        assertTrue(ExplorationPolicy.valid(a, "private-a", revision, "one"))
        ExplorationPolicy.finish(a, "one", "completed")
        assertFalse(ExplorationPolicy.due(a, now + 10_000_000, day))
    }

    @Test fun schedulesAndDailyLimitsStayWithinUserControls() {
        val a = enabled()
        val revision = ExplorationPolicy.revision(a)
        for (minutes in listOf(0, 14, 10081, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ExplorationPolicy.schedule(a, minutes, "继续", now) }
        }
        ExplorationPolicy.schedule(a, 10080, "下周继续", now)
        assertEquals(revision, ExplorationPolicy.revision(a))
        assertEquals(4, a.getInt("limit"))
        assertThrows(IllegalArgumentException::class.java) { ExplorationPolicy.configure(a, true, emptySet(), 4, now) }
        assertThrows(IllegalArgumentException::class.java) { ExplorationPolicy.configure(a, true, setOf("web"), 25, now) }
    }
}
