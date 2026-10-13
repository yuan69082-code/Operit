package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneEventQueueTest {
    private val now = 1_000_000L
    private fun event(at: Long = now - 100, state: String = "pending") = JSONObject().put("at", at)
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
}
