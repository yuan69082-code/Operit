package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubjectRestPolicyTest {
    private fun change() = JSONObject().put("state", "resting")
    @Test fun oldRestMeansQuietNotFabricatedSleep() {
        val rest = SubjectRestPolicy.adopt(change(), null, 100, "f")
        assertEquals("quiet", rest.getString("mode"))
        assertFalse(SubjectRestPolicy.due(rest, Long.MAX_VALUE))
        assertEquals("buffer", SubjectRestPolicy.admission(rest, "user_message", 200))
    }
    @Test fun timedSleepExpiresWithoutClaimingSubjectWoke() {
        val rest = SubjectRestPolicy.adopt(change().put("mode", "sleep").put("until", 200), null, 100, "f")
        assertEquals("睡眠中", SubjectRestPolicy.label(rest, null, 199))
        assertEquals("休息时段已结束，等待续接", SubjectRestPolicy.label(rest, null, 200))
        assertEquals("resting", rest.getString("state"))
        assertFalse(SubjectRestPolicy.due(rest, 99))
    }
    @Test fun sourceChoiceDoesNotUseContentKeywordsAndExtensionKeepsStart() {
        val first = SubjectRestPolicy.adopt(change().put("mode", "sleep").put("until", 200)
            .put("wake_sources", JSONArray().put("study")).put("interruption", "judge"), null, 100, "one")
        assertEquals("judge", SubjectRestPolicy.admission(first, "study", 150))
        assertEquals("buffer", SubjectRestPolicy.admission(first, "phone", 150))
        val next = SubjectRestPolicy.adopt(change().put("mode", "sleep").put("until", 300), first, 190, "two")
        assertEquals(100, next.getLong("started_at"))
        assertEquals("one", next.getString("session_id"))
    }
    @Test fun sleepNeedsAnActualEndArrangement() {
        try { SubjectRestPolicy.adopt(change().put("mode", "sleep"), null, 100, "f"); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun nightmareAndNestedFormAreReachableWithoutForcedRatio() {
        val result = SubjectRestPolicy.adopt(change().put("until", 200).put("dream_plan", JSONObject()
            .put("steps", 2).put("tone", "恐惧与好奇交织").put("form", "嵌套")), null, 100, "f")
        assertEquals("恐惧与好奇交织", result.getJSONObject("dream_plan").getString("tone"))
        assertFalse(SubjectRestPolicy.adopt(change(), null, 100, "none").has("dream_plan"))
    }
}
