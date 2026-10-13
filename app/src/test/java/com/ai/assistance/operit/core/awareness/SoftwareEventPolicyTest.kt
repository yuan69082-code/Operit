package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SoftwareEventPolicyTest {
    private fun event(id: Int, state: String = "pending") = JSONObject().put("sequence", id).put("at", id)
        .put("kind", "model").put("summary", "新模型$id").put("actor", "software_change")
        .put("detail", JSONObject().put("large_data", "not-in-prompt")).put("dispatch", state)

    @Test fun burstsAreOneBoundedBriefWithQueryableRange() {
        val data = JSONObject().put("events", JSONArray((1..40).map { event(it) }))
        val batch = SoftwareEventPolicy.claim(data, 1000, 60000)!!
        assertEquals(40, batch.getInt("count"))
        assertEquals(8, batch.getJSONArray("recent").length())
        assertEquals(1L, batch.getLong("first_sequence"))
        assertEquals(40L, batch.getLong("last_sequence"))
        assertFalse(batch.toString().contains("not-in-prompt"))
        assertNull(SoftwareEventPolicy.claim(data, 90000, 60000))
    }
    @Test fun cooldownRetainsPendingInsteadOfDroppingIt() {
        val data = JSONObject().put("last_wake", 1000).put("events", JSONArray().put(event(1)))
        assertNull(SoftwareEventPolicy.claim(data, 15000, 60000))
        assertEquals("pending", SoftwareEventPolicy.rows(data).single().getString("dispatch"))
        assertNotNull(SoftwareEventPolicy.claim(data, 61000, 60000))
    }
    @Test fun failedCancelledAndInterruptedWorkIsNotAutomaticallyRetried() {
        val data = JSONObject().put("events", JSONArray(listOf(event(1, "failed"), event(2, "cancelled"), event(3, "interrupted"))))
        assertNull(SoftwareEventPolicy.claim(data, 1000, 60000))
    }
    @Test fun onlyExplicitObserverInOwnPrivateChatCanRead() {
        assertTrue(SoftwareEventPolicy.mayRead(true, "a", "a", "a", null))
        assertFalse(SoftwareEventPolicy.mayRead(true, "a", "b", "b", null))
        assertFalse(SoftwareEventPolicy.mayRead(true, "a", "a", "b", null))
        assertFalse(SoftwareEventPolicy.mayRead(true, "a", "a", "a", "group"))
        assertFalse(SoftwareEventPolicy.mayRead(true, null, "a", "a", null))
        assertFalse(SoftwareEventPolicy.mayRead(false, "a", "a", "a", null))
    }
}
