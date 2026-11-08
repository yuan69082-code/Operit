package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubjectStateBoundaryTest {
    @Test fun adoptionPreservesUnknownLegacyProvenanceAndRejectsDifferentOwner() {
        val data = JSONObject().put("instances", JSONArray().put(JSONObject().put("value", 81).put("scope", "window-1")))
            .put("semantic_revision", 18).put("unknown_future_field", "原文")
        assertTrue(SubjectStateBoundary.adopt(data, "a", "internal-state"))
        assertFalse(SubjectStateBoundary.adopt(data, "a", "internal-state"))
        assertEquals(81, data.getJSONArray("instances").getJSONObject(0).getInt("value"))
        assertEquals("原文", data.getString("unknown_future_field"))
        assertFalse(data.getJSONArray("instances").getJSONObject(0).has("reason"))
        assertThrows(IllegalStateException::class.java) { SubjectStateBoundary.adopt(data, "b", "internal-state") }
        assertThrows(IllegalArgumentException::class.java) { SubjectStateBoundary.authorize("a", "primary") }
    }

    @Test fun transactionVersionIsSeparateFromSemanticRevisionAndNoops() {
        val data = JSONObject().put("semantic_revision", 9)
        SubjectStateBoundary.adopt(data, "a", "thoughts")
        val before = JSONObject(data.toString())
        SubjectStateBoundary.committed(data, before, "retry")
        assertEquals(0L, SubjectStateBoundary.revision(data))
        data.put("items", JSONArray().put("想继续的事"))
        SubjectStateBoundary.committed(data, before, "assistant_thought")
        assertEquals(1L, SubjectStateBoundary.revision(data))
        assertEquals(9, data.getInt("semantic_revision"))
        assertEquals("assistant_thought", data.getJSONObject(SubjectStateBoundary.FIELD).getString("last_source"))
    }
}
