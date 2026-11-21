package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DreamTriggerTest {
    private fun sleep() = JSONObject().put("state", "resting").put("mode", "sleep")
    private fun plan() = JSONObject().put("steps", 2)

    @Test fun lotteryAdmitsHalfAndExtensionCannotChangeEitherResult() {
        for (won in listOf(true, false)) {
            val initial = sleep().put("dream_plan", plan())
            var calls = 0
            DreamTrigger.HALF.apply(initial, null, JSONObject(), 100L) { calls++; won }
            assertEquals(won, initial.has("dream_plan"))
            val extended = sleep().put("dream_plan", JSONObject().put("steps", 1))
            DreamTrigger.STIMULUS.apply(extended, initial, JSONObject(), 200L) { fail("Second draw"); false }
            assertEquals(DreamTrigger.HALF.name, extended.getString("dream_trigger"))
            assertEquals(won, extended.has("dream_plan"))
            if (won) assertEquals(2, extended.getJSONObject("dream_plan").getInt("steps"))
            assertEquals(1, calls)
        }
    }

    @Test fun subjectChoiceNeedsAPlanAndStimulusModeNeedsAnAdoptedStrongEpisode() {
        val choseNoDream = sleep()
        val choseDream = sleep().put("dream_plan", plan())
        val stimulusWithoutEvent = sleep().put("dream_plan", plan())
        DreamTrigger.SUBJECT.apply(choseNoDream, null, JSONObject(), 100L) { fail("Random draw"); false }
        DreamTrigger.SUBJECT.apply(choseDream, null, JSONObject(), 100L) { fail("Random draw"); false }
        DreamTrigger.STIMULUS.apply(stimulusWithoutEvent, null, JSONObject(), 100L) { fail("Random draw"); false }
        assertFalse(choseNoDream.has("dream_plan"))
        assertTrue(choseDream.has("dream_plan"))
        assertFalse(stimulusWithoutEvent.has("dream_plan"))
    }

    @Test fun strengthMatchesRecordedIntensityAttentionAndDate() {
        val row = JSONObject().put("storage_kind", "episode").put("at", 100L)
        val content = JSONObject().put("attention", "foreground").put("intensity", 0.75)
        row.put("content", content)
        assertTrue(qualifiesDreamStimulus(row, 80L, 120L))
        assertFalse(qualifiesDreamStimulus(row, 101L, 120L))
        assertFalse(qualifiesDreamStimulus(row, 80L, 99L))
        content.put("attention", "background")
        assertFalse(qualifiesDreamStimulus(row, 80L, 120L))
        content.put("attention", "foreground").put("intensity", 0.74)
        assertFalse(qualifiesDreamStimulus(row, 80L, 120L))
    }
}
