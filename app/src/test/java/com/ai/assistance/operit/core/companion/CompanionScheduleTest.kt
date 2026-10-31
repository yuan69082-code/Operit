package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionScheduleTest {
    @Test fun interruptionSchedulesNewJudgmentWithoutChangingPurpose() {
        val data = JSONObject().put("purpose", "check progress")
        CompanionSchedule.claim(data, "old", 100)
        CompanionSchedule.finish(data, "old", false, 200)
        assertFalse(data.getBoolean("paused"))
        assertEquals(60_200L, data.getLong("at"))
        assertEquals("check progress", data.getString("purpose"))
        assertTrue(data.getBoolean("interrupted"))
        assertFalse(data.has("running_ticket"))
    }

    @Test fun explicitCancelOrNewScheduleCannotBeOverwrittenByOldFinally() {
        for (paused in listOf(true, false)) {
            val data = JSONObject()
            CompanionSchedule.claim(data, "old", 100)
            CompanionSchedule.supersede(data)
            data.put("paused", paused).put("at", 999).put("purpose", "new decision")
            val expected = data.toString()
            CompanionSchedule.finish(data, "old", false, 200)
            assertEquals(expected, data.toString())
        }
    }

    @Test fun finishedJudgmentWithoutAnotherScheduleRemainsPaused() {
        val data = JSONObject()
        CompanionSchedule.claim(data, "done", 100)
        CompanionSchedule.finish(data, "done", true, 200)
        assertTrue(data.getBoolean("paused"))
        assertFalse(data.has("running_ticket"))
        assertFalse(data.has("interrupted"))
    }

    @Test fun repeatedInterruptionsHaveBoundedBackoffAndStaleTicketsDoNothing() {
        val data = JSONObject()
        repeat(10) { index ->
            CompanionSchedule.claim(data, "$index", 0)
            CompanionSchedule.finish(data, "stale", false, 0)
            assertEquals("$index", data.getString("running_ticket"))
            CompanionSchedule.finish(data, "$index", false, 0)
            assertTrue(data.getLong("at") in 60_000L..900_000L)
        }
        assertEquals(900_000L, data.getLong("at"))
    }
}
