package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneEventQueueTest {
    private val now = 1_000_000L
    private fun event(at: Long = now - 6000, state: String = "pending") = JSONObject().put("at", at)
        .put("dispatch", state).put("kind", "app_enter").put("package", "test.app").put("app_name", "应用")
    @Test fun burstIsClaimedOnlyOnceAndPromptIsBounded() {
        val rows = JSONArray((1..60).map { event() })
        val data = JSONObject().put("events", rows)
        val batch = requireNotNull(PhoneEventQueue.claim(data, now, 30_000))
        assertEquals(60, batch.getInt("count"))
        assertEquals(10, batch.getJSONArray("observations").length())
        assertEquals("running", rows.getJSONObject(0).getString("dispatch"))
        assertNull(PhoneEventQueue.claim(data, now + 60_000, 30_000))
    }
    @Test fun minimumIntervalLeavesNewEventsPending() {
        val row = event()
        val data = JSONObject().put("events", JSONArray().put(row)).put("last_wake", now - 10_000)
        assertNull(PhoneEventQueue.claim(data, now, 30_000))
        assertEquals("pending", row.getString("dispatch"))
        assertNotNull(PhoneEventQueue.claim(data, now + 21_000, 30_000))
    }
    @Test fun oldOrFutureEventsDoNotCauseStaleInterventions() {
        val old = event(now - 301_000)
        val future = event(now + 500)
        val data = JSONObject().put("events", JSONArray().put(old).put(future))
        assertNull(PhoneEventQueue.claim(data, now, 5000))
        assertEquals("expired", old.getString("dispatch"))
        assertEquals("expired", future.getString("dispatch"))
    }
    @Test fun failedAndInterruptedWorkIsNeverReplayed() {
        val data = JSONObject().put("events", JSONArray(listOf("failed", "interrupted", "handled", "cancelled", "recorded").map { event(state = it) }))
        assertNull(PhoneEventQueue.claim(data, now, 5000))
        assertFalse(data.has("last_wake"))
    }
    @Test fun observationDoesNotEmbedPrivateContentOrOldDecisions() {
        val row = event().put("text", "私有页面正文").put("result", "旧决定")
        val data = JSONObject().put("events", JSONArray().put(row))
        val observation = PhoneEventQueue.claim(data, now, 5000)!!.getJSONArray("observations").getJSONObject(0)
        assertFalse(observation.has("text"))
        assertFalse(observation.has("result"))
        assertEquals("test.app", observation.getString("package"))
    }
    @Test fun entryWaitsForShortVisitWindowEvenOnFirstWake() {
        val data = JSONObject().put("events", JSONArray().put(event(now - 100)))
        assertNull(PhoneEventQueue.claim(data, now, 5000))
        assertNotNull(PhoneEventQueue.claim(data, now + 5000, 5000))
    }
    @Test fun briefDialogCannotWakeAloneAndIsBundledWithNextStableEntry() {
        val enter = event(now - 4000)
        val leave = event(now - 2000, "recorded").put("kind", "app_leave")
            .put("entered_at", now - 4000).put("duration_ms", 2000)
        val rows = JSONArray().put(enter).put(leave)
        val data = JSONObject().put("events", rows)
        assertNull(PhoneEventQueue.claim(data, now, 5000))
        assertEquals("coalesced", enter.getString("dispatch"))
        rows.put(event(now - 2000).put("package", "next.app"))
        val batch = requireNotNull(PhoneEventQueue.claim(data, now + 4000, 5000))
        assertEquals(1, batch.getInt("brief_visit_count"))
        val observation = batch.getJSONArray("observations").getJSONObject(0)
        assertEquals(2000L, observation.getJSONObject("previous_app").getLong("duration_ms"))
        assertTrue(batch.getJSONArray("completed_visits").getJSONObject(0).getBoolean("brief"))
        assertNull(PhoneEventQueue.claim(data, now + 60_000, 5000))
    }
    @Test fun switchedObservationIncludesPreviousAppsFullDuration() {
        val leave = event(now - 6000, "recorded").put("kind", "app_leave")
            .put("entered_at", now - 66_000).put("duration_ms", 60_000)
        val data = JSONObject().put("events", JSONArray().put(leave).put(event()))
        val batch = requireNotNull(PhoneEventQueue.claim(data, now, 5000))
        val previous = batch.getJSONArray("observations").getJSONObject(0).getJSONObject("previous_app")
        assertEquals(60_000L, previous.getLong("duration_ms"))
        assertEquals(now - 66_000, previous.getLong("entered_at"))
    }
    @Test fun unlockCanDeliverVisitClosedAtScreenOffWithoutCountingTheNight() {
        val leave = event(now - 3_600_000, "recorded").put("kind", "app_leave")
            .put("entered_at", now - 3_660_000).put("duration_ms", 60_000)
        val unlock = event(now - 100).put("kind", "unlock").put("urgent", true)
        val data = JSONObject().put("events", JSONArray().put(leave).put(unlock))
        val batch = requireNotNull(PhoneEventQueue.claim(data, now, 5000))
        assertEquals(60_000L, batch.getJSONArray("completed_visits").getJSONObject(0).getLong("duration_ms"))
    }
}
