package com.ai.assistance.operit.core.group

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupMemoryRulesTest {
    private fun state() = JSONObject().also {
        GroupMemoryRules.observe(it, "a", listOf(10L to "old", 20L to "latest"))
        GroupMemoryRules.observe(it, "b", listOf(15L to "other"))
    }

    @Test fun editingPreservesProvenanceAndOtherMembers() {
        val state = state()
        GroupMemoryRules.save(state, "a", "edited", false, "user", 30)
        val own = state.getJSONObject("seen").getJSONObject("a")
        assertEquals(10L, own.getLong("first"))
        assertEquals(20L, own.getLong("through"))
        assertEquals("old\nlatest", own.getString("excerpt"))
        assertEquals("edited", GroupMemoryRules.recalled(own))
        assertEquals("other", GroupMemoryRules.recalled(state.getJSONObject("seen").getJSONObject("b")))
    }

    @Test fun protectedMemoryRequiresExplicitUnprotection() {
        val state = state()
        GroupMemoryRules.save(state, "a", "keep", false, "a", 30)
        try { GroupMemoryRules.delete(state, "a"); fail("protected memory must survive") }
        catch (_: IllegalStateException) { }
        assertTrue(state.getJSONObject("seen").has("a"))
        GroupMemoryRules.save(state, "a", "keep", true, "user", 40)
        GroupMemoryRules.delete(state, "a")
        assertFalse(state.getJSONObject("seen").has("a"))
        assertTrue(state.getJSONObject("seen").has("b"))
    }

    @Test fun deletedMemoryDoesNotReturnFromRepeatedHistorySnapshots() {
        val state = state()
        state.put("game", JSONObject().put("phase", "ended"))
        GroupMemoryRules.delete(state, "a")
        GroupMemoryRules.observe(state, "a", listOf(10L to "old", 20L to "latest"))
        assertFalse(state.getJSONObject("seen").has("a"))
        GroupMemoryRules.observe(state, "a", listOf(10L to "old", 20L to "latest", 40L to "new"))
        val own = state.getJSONObject("seen").getJSONObject("a")
        assertEquals(40L, own.getLong("first"))
        assertEquals("new", GroupMemoryRules.recalled(own))
        assertEquals("ended", state.getJSONObject("game").getString("phase"))
    }

    @Test fun observationsKeepEditsAndCannotRegressTheWatermark() {
        val state = state()
        GroupMemoryRules.save(state, "a", "", true, "user", 30)
        GroupMemoryRules.observe(state, "a", listOf(40L to "fresh"))
        GroupMemoryRules.observe(state, "a", listOf(10L to "stale"))
        val own = state.getJSONObject("seen").getJSONObject("a")
        assertEquals("", GroupMemoryRules.recalled(own))
        assertEquals(40L, own.getLong("through"))
        assertEquals("fresh", own.getString("excerpt"))
    }
}
