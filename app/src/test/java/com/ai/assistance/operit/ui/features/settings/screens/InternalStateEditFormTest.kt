package com.ai.assistance.operit.ui.features.settings.screens

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateEditFormTest {
    private fun state() = JSONObject().put("value", 20.0).put("min", 0.0).put("max", 100.0)
        .put("kind", "sentiment").put("reason", "原来的原因")

    private fun timedState() = state().put("time_rules", JSONObject()
        .put("increase", JSONObject().put("condition", "not_contacted").put("reason", "未联系时积累")
            .put("interval_minutes", 1.0).put("amount", 1.0))
        .put("decrease", JSONObject().put("condition", "always").put("reason", "暂不随时间下降")
            .put("interval_minutes", 0.0).put("amount", 0.0))
        .put("floor", 30.0).put("contact_window_minutes", 360.0))

    @Test fun categoryEditDoesNotResubmitElapsedValueOrDefineMissingRules() {
        val state = state().also { it.remove("kind") }
        val result = stateEditSubmission(state, stateEditDraft(state).copy(kind = "psychology"))
        assertEquals(1, result.changes.length())
        assertEquals("psychology", result.changes.getString("kind"))
        assertFalse(result.changes.has("value"))
        assertFalse(result.changes.has("time_rules"))
    }

    @Test fun shorteningSixHourWaitToZeroDoesNotPauseTheIncreaseInterval() {
        val state = timedState()
        val draft = stateEditDraft(state)
        val result = stateEditSubmission(state, draft.copy(time = draft.time!!.copy(contactWindow = "0")))
        val rules = result.changes.getJSONObject("time_rules")
        assertEquals(0.0, rules.getDouble("contact_window_minutes"), 0.0)
        assertEquals(1.0, rules.getJSONObject("increase").getDouble("interval_minutes"), 0.0)
        assertFalse(result.changes.has("value"))
        assertFalse(result.changes.has("kind"))
    }

    @Test fun reasonOnlyAndFormattingChangesDoNotOverwriteNumericFields() {
        val state = timedState()
        val draft = stateEditDraft(state)
        val time = draft.time!!
        val result = stateEditSubmission(state, draft.copy(value = "20.000", reason = "用户重新解释原因",
            time = time.copy(increase = time.increase.copy(interval = "1.000"))))
        assertEquals(0, result.changes.length())
        assertEquals("用户重新解释原因", result.reason)
    }

    @Test fun existingDormantContactSettingIsNotChangedByCurrentValueEdit() {
        val state = timedState()
        state.getJSONObject("time_rules").getJSONObject("increase").put("condition", "always")
        val result = stateEditSubmission(state, stateEditDraft(state).copy(value = "0"))
        assertEquals(1, result.changes.length())
        assertEquals(0.0, result.changes.getDouble("value"), 0.0)
        assertFalse(result.changes.has("time_rules"))
        assertEquals(360.0, state.getJSONObject("time_rules").getDouble("contact_window_minutes"), 0.0)
    }

    @Test fun explicitlyDefinedRulesAcceptZeroAndCustomValuesBeyondThirty() {
        val state = state()
        val time = StateTimeDraft(
            StateDirectionDraft("custom", "正在回忆刚才的告别", "1000000", "0.25"),
            StateDirectionDraft("always", "暂停自动下降", "0", "0"), "0")
        val result = stateEditSubmission(state, stateEditDraft(state).copy(time = time))
        val rules = result.changes.getJSONObject("time_rules")
        assertEquals(1000000.0, rules.getJSONObject("increase").getDouble("interval_minutes"), 0.0)
        assertEquals(0.25, rules.getJSONObject("increase").getDouble("amount"), 0.0)
        assertEquals(0.0, rules.getDouble("floor"), 0.0)
        assertFalse(rules.has("contact_window_minutes"))
    }

    @Test fun optingIntoNewRulesRequiresCompleteDefinition() {
        val state = state()
        try {
            stateEditSubmission(state, stateEditDraft(state).copy(time = StateTimeDraft()))
            fail("Partial rules must not replace existing evolution")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("条件"))
        }
        assertFalse(state.has("time_rules"))
    }

    @Test fun invalidCurrentValuesAreRejectedWithoutClampingOrAcceptingText() {
        val state = state()
        listOf("101", "-1", "NaN", "Infinity", "1f", "不是数字").forEach { input ->
            try {
                stateEditSubmission(state, stateEditDraft(state).copy(value = input))
                fail("Invalid value accepted: " + input)
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(20.0, state.getDouble("value"), 0.0)
    }
}
