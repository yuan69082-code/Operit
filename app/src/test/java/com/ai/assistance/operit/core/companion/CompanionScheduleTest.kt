package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionScheduleTest {
    @Test fun runningCompletionAndExplicitPauseHaveDifferentVisibleStates() {
        val data = JSONObject().put("purpose", "留意进度").put("owner", "ai").put("at", 100)
        CompanionSchedule.claim(data, "one", 100)
        assertEquals("running", CompanionSchedule.status(data).getString("schedule_state"))
        assertTrue(CompanionSchedule.status(data).isNull("next_at"))
        CompanionSchedule.finish(data, "one", true, 200, continuous = true)
        assertEquals("not_scheduled", CompanionSchedule.state(data))
        assertFalse(CompanionSchedule.canResume(data))
        CompanionSchedule.cancel(data)
        assertEquals("paused", CompanionSchedule.state(data))
        assertFalse(CompanionSchedule.canResume(data))
    }

    @Test fun explicitResumeUsesTheOriginalPlanWithoutAutomaticRetry() {
        val data = JSONObject().put("purpose", "原来想留意的事").put("owner", "ai").put("at", 100)
        CompanionSchedule.claim(data, "old", 100)
        CompanionSchedule.finish(data, "old", false, 200, continuous = true)
        assertEquals("interrupted", CompanionSchedule.state(data))
        assertTrue(CompanionSchedule.canResume(data))
        assertTrue(data.getBoolean("paused"))
        assertFalse(CompanionSchedule.eligible(data, true))

        CompanionSchedule.resume(data)
        assertEquals(100L, data.getLong("at"))
        assertEquals("原来想留意的事", data.getString("purpose"))
        assertEquals("scheduled", CompanionSchedule.state(data))
        assertEquals(100L, CompanionSchedule.status(data).getLong("next_at"))
        assertTrue(CompanionSchedule.eligible(data, true))
        assertTrue(data.getBoolean("interrupted")) // The resumed turn must check actual prior results.
        assertFalse(CompanionSchedule.canResume(data))

        CompanionSchedule.claim(data, "resumed", 300)
        CompanionSchedule.finish(data, "resumed", false, 400, continuous = true)
        assertTrue(data.getBoolean("paused"))
        assertFalse(CompanionSchedule.eligible(data, true))
        assertEquals(100L, data.getLong("at"))
    }

    @Test fun cancellationAndFreshSchedulesRevokeTheInterruptedPlan() {
        val data = JSONObject().put("owner", "user").put("at", 100)
        CompanionSchedule.claim(data, "old", 100)
        CompanionSchedule.finish(data, "old", false, 200, continuous = true)
        CompanionSchedule.cancel(data)
        assertFalse(CompanionSchedule.canResume(data))
        assertThrows(IllegalStateException::class.java) { CompanionSchedule.resume(data) }
        CompanionSchedule.supersede(data)
        data.put("at", 900).put("paused", false)
        assertEquals("scheduled", CompanionSchedule.state(data))
        assertFalse(data.optBoolean("self_paused"))
        assertFalse(CompanionSchedule.canResume(data))
        val saved = data.toString()
        CompanionSchedule.finish(data, "old", false, 1000, continuous = true)
        assertEquals(saved, data.toString())
    }

    @Test fun continuousRecoveryRetainsUnknownWithoutCreatingJudgment() {
        val data = JSONObject().put("purpose", "继续已安排的事").put("owner", "ai").put("at", 100)
        CompanionSchedule.claim(data, "old", 100)
        CompanionSchedule.finish(data, "old", false, 200, continuous = true)
        assertTrue(data.getBoolean("paused"))
        assertEquals("unknown", data.getString("result_status"))
        assertFalse(CompanionSchedule.eligible(data, true))
        assertEquals(100L, data.getLong("at"))
        CompanionSchedule.supersede(data)
        data.put("at", 300).put("paused", false)
        assertTrue(CompanionSchedule.eligible(data, true))
        assertFalse(CompanionSchedule.eligible(JSONObject().put("owner", "initial").put("at", 100), true))
    }
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
