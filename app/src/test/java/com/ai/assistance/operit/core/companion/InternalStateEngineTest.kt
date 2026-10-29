package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateEngineTest {
    private val hour = 3600000L
    private fun definition(id: String = "curiosity", scope: String = "global") = JSONObject()
        .put("id", id).put("name", "自己定义的 $id").put("description", "反复遇见未解的问题")
        .put("min", 0).put("max", 100).put("baseline", 50).put("maxDelta", 20).put("halfLifeHours", 1)
        .put("scope", scope).put("private", false).put("share", false).put("participates", true)
        .put("triggers", JSONArray().put(JSONObject().put("source", "reading").put("delta", 20)))
        .put("effects", JSONArray().put(JSONObject().put("action", "explore").put("weight", 10).put("cue", "高于基准时继续追问，低时休息"))
            .put(JSONObject().put("action", "contact").put("weight", -5).put("cue", "投入探索时少联系")))
    private fun data(d: JSONObject = definition()): JSONObject = JSONObject().also {
        InternalStateEngine.event(it, "first", "user_message", "a", "真实的新消息", 0)
        create(it, d)
    }
    private fun create(data: JSONObject, d: JSONObject) = InternalStateEngine.evolve(data, "create", d.getString("id"), 0,
        d, null, "这个经历需要独立维度表达", listOf("first"), "a", 0)
    private fun value(data: JSONObject, chat: String = "a", id: String = "curiosity", now: Long = 0) =
        InternalStateEngine.snapshot(data, chat, now).single { it.getString("id") == id }.getDouble("value")
    private fun candidates() = listOf("contact", "explore", "rest", "reply", "tool", "todo", "defer", "drop").map {
        JSONObject().put("action", it).put("id", it).put("available", true)
    }

    @Test fun promptProjectionPreservesDecisionAndDoesNotMutateStoredDefinitions() {
        val data = data()
        InternalStateEngine.event(data, "read", "reading", "a", "read", 0)
        val decision = InternalStateEngine.decision(data, "a", 0, candidates())
            .put("thoughts", JSONArray().put(JSONObject().put("id", "t").put("text", "想继续的问题")))
        val before = decision.toString()
        val compact = InternalStateEngine.promptPayload(decision)
        assertEquals(before, decision.toString())
        assertEquals(decision.getJSONArray("candidates").toString(), compact.getJSONArray("candidates").toString())
        assertEquals(decision.getJSONArray("important_changes").toString(), compact.getJSONArray("important_changes").toString())
        assertEquals(decision.getJSONArray("thoughts").toString(), compact.getJSONArray("thoughts").toString())
        val original = decision.getJSONArray("states").getJSONObject(0)
        val state = compact.getJSONArray("states").getJSONObject(0)
        for (key in original.keys()) {
            if (key in setOf("triggers", "maxDelta", "halfLifeHours")) assertFalse(state.has(key))
            else if (original.get(key) is Number) {
                // JSON round trips normalize 70.0 to 70; compare values, not formatting.
                assertEquals(key, original.getDouble(key), state.getDouble(key), 0.0)
            } else assertEquals(key, original.get(key).toString(), state.get(key).toString())
        }
        assertTrue(original.has("triggers"))
        assertTrue(compact.toString().length < before.length)
        assertTrue(InternalStateEngine.prompt(decision).contains(compact.toString()))
    }

    @Test fun arbitraryDimensionsAndNoFixedEmotionEnumeration() {
        val data = data(definition("unfinished_question"))
        create(data, definition("creative_tension"))
        assertEquals(setOf("unfinished_question", "creative_tension"), InternalStateEngine.definitions(data).map { it.getString("id") }.toSet())
    }
    @Test fun ownActionsChangeValuesAndMessagesAreIdempotent() {
        val data = data()
        InternalStateEngine.event(data, "read1", "reading", "a", "companion_reading/get", 0)
        InternalStateEngine.event(data, "read1", "reading", "a", "same retry", hour)
        assertEquals(70.0, value(data), 0.00001)
        assertEquals(2, InternalStateEngine.rows(data, "events").size)
    }
    @Test fun everyHostSourceCanDriveDynamicRules() {
        (InternalStateEngine.sources - "time").forEach { source ->
            val data = data(definition().put("triggers", JSONArray().put(JSONObject().put("source", source).put("delta", 3))))
            InternalStateEngine.event(data, "actual:$source", source, "a", "actual observation", 0)
            assertEquals(source, 53.0, value(data), 1e-9)
        }
    }
    @Test fun eventDeltaIsCappedAcrossMatchingRules() {
        val d = definition()
        d.getJSONArray("triggers").put(JSONObject().put("source", "reading").put("delta", 20))
        val data = data(d)
        repeat(20) { InternalStateEngine.event(data, "read$it", "reading", "a", "read", 0) }
        assertEquals(100.0, value(data), 1e-9)
        val first = InternalStateEngine.rows(InternalStateEngine.rows(data, "events")[1], "changes").single()
        assertEquals(20.0, first.getDouble("after") - first.getDouble("before"), 1e-9)
    }
    @Test fun timeRecoveryAndDriftAreIndependentOfPollingFrequency() {
        val d = definition().apply { getJSONArray("triggers").put(JSONObject().put("source", "time").put("delta", 2)) }
        val once = data(d); val often = JSONObject(once.toString())
        InternalStateEngine.event(once, "r", "reading", "a", "read", 0)
        InternalStateEngine.event(often, "r", "reading", "a", "read", 0)
        InternalStateEngine.advance(once, "a", hour)
        (1..60).forEach { InternalStateEngine.advance(often, "a", it * 60000L) }
        assertEquals(value(once), value(often), 1e-8)
        assertTrue(value(once) in 60.0..63.0)
    }
    @Test fun halfLifeRecoversWithoutNewUserMessage() {
        val data = data()
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        InternalStateEngine.advance(data, "a", hour)
        assertEquals(60.0, value(data), 1e-8)
        assertEquals("time", InternalStateEngine.rows(data, "events").last().getString("source"))
    }
    @Test fun globalSharesAcrossWindowsAndSessionDoesNot() {
        val data = data(); create(data, definition("local", "session"))
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        assertEquals(70.0, value(data, "b"), 1e-8)
        assertEquals(50.0, value(data, "b", "local"), 1e-8)
        assertEquals(70.0, value(data, "a", "local"), 1e-8)
    }
    @Test fun revisionPreservesValueAndOldRuleAndRejectsStaleVersion() {
        val data = data()
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        val updated = definition().put("baseline", 10).put("name", "新名字")
        InternalStateEngine.evolve(data, "revise", "curiosity", 1, updated, null, "依据阅读修订", listOf("r"), "a", 0)
        assertEquals(70.0, value(data), 1e-9)
        assertEquals(50.0, InternalStateEngine.rows(data, "versions").first().getJSONObject("after").getDouble("baseline"), 1e-9)
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateEngine.evolve(data, "revise", "curiosity", 1, updated, null, "旧版本", listOf("r"), "a", 0)
        }
    }
    @Test fun revisionSettlesOtherSessionsWithOldRules() {
        val data = data(definition(scope = "session"))
        InternalStateEngine.event(data, "b", "reading", "b", "read", 0)
        InternalStateEngine.evolve(data, "revise", "curiosity", 1, definition(scope = "session").put("halfLifeHours", 0),
            null, "有经过", listOf("b"), "a", hour)
        assertEquals(60.0, value(data, "b"), 1e-8)
    }
    @Test fun mergeAndDeactivateRetainHistory() {
        val data = data(); create(data, definition("other"))
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        InternalStateEngine.evolve(data, "merge", "curiosity", 1, null, "other", "区分不再有意义", listOf("r"), "a", 0)
        assertEquals(2, InternalStateEngine.definitions(data).size)
        assertEquals(1, InternalStateEngine.snapshot(data, "a", 0).size)
        assertEquals("other", InternalStateEngine.definitions(data).first().getString("merged_into"))
        InternalStateEngine.evolve(data, "deactivate", "other", 1, null, null, "暂不需要", listOf("r"), "a", 0)
        assertEquals(4, InternalStateEngine.rows(data, "versions").size)
        assertTrue(InternalStateEngine.snapshot(data, "a", 0).isEmpty())
        assertEquals(2, InternalStateEngine.rows(data, "instances").size)
    }
    @Test fun privateVisibilityRequiresBothGatesForCardsEventsAndRules() {
        for (share in listOf(false, true)) for (reveal in listOf(false, true)) {
            val data = data(definition("secret").put("private", true).put("share", share))
            InternalStateEngine.event(data, "r", "reading", "a", "secret reading", 0)
            val observation = InternalStateEngine.observation(data, "a", 0, reveal)
            listOf("states", "events", "versions").forEach { assertEquals(share && reveal, InternalStateEngine.rows(observation, it).isNotEmpty()) }
            assertEquals(70.0, value(data, id = "secret"), 1e-8)
        }
    }
    @Test fun revokingShareHidesOldHistoryToo() {
        val d = definition().put("private", true).put("share", true)
        val data = data(d)
        InternalStateEngine.event(data, "r", "reading", "a", "private", 0)
        InternalStateEngine.evolve(data, "revise", "curiosity", 1, d.put("share", false), null, "收回公开", listOf("r"), "a", 0)
        assertEquals(0, InternalStateEngine.observation(data, "a", 0, true).getJSONArray("events").length())
        assertEquals(0, InternalStateEngine.observation(data, "a", 0, true).getJSONArray("versions").length())
    }
    @Test fun makingDimensionPublicDoesNotExposePreviouslyUnsharedPrivateHistory() {
        val d = definition().put("private", true)
        val data = data(d)
        InternalStateEngine.event(data, "r", "reading", "a", "private", 0)
        InternalStateEngine.evolve(data, "revise", "curiosity", 1, d.put("private", false), null, "从现在起公开", listOf("r"), "a", 0)
        val observed = InternalStateEngine.observation(data, "a", 0, false)
        assertEquals(1, observed.getJSONArray("states").length())
        assertEquals(0, observed.getJSONArray("events").length())
    }
    @Test fun wakeEventChangesDecisionBeforeSendingAndAllActionDirectionsCanBeRanked() {
        val d = definition().put("triggers", JSONArray().put(JSONObject().put("source", "wake").put("delta", 15)))
        d.put("effects", JSONArray(InternalStateEngine.actions.mapIndexed { index, action ->
            JSONObject().put("action", action).put("weight", index + 1).put("cue", "按此状态重新考虑 $action")
        }))
        val data = data(d)
        val before = InternalStateEngine.prompt(InternalStateEngine.decision(data, "a", 0, candidates()))
        InternalStateEngine.event(data, "wake-1", "wake", "a", "定时醒来", hour)
        val decision = InternalStateEngine.decision(data, "a", hour, candidates())
        assertNotEquals(before, InternalStateEngine.prompt(decision))
        assertTrue(InternalStateEngine.rows(decision, "candidates").all { it.getDouble("score") > 0 })
        assertEquals("rest", decision.getJSONArray("candidates").getJSONObject(0).getString("id"))
    }
    @Test fun differentStatesChangeActualDecisionPayloadRankingAndToneCues() {
        val d = definition().apply { getJSONArray("effects").put(JSONObject().put("action", "reply").put("weight", 6).put("cue", "多讲问题本身，表达更专注")) }
        val data = data(d)
        val neutral = InternalStateEngine.decision(data, "a", 0, candidates())
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        val engaged = InternalStateEngine.decision(data, "a", 0, candidates())
        assertNotEquals(InternalStateEngine.prompt(neutral), InternalStateEngine.prompt(engaged))
        assertEquals("explore", engaged.getJSONArray("candidates").getJSONObject(0).getString("id"))
        val ranked = InternalStateEngine.rows(engaged, "candidates")
        assertTrue(ranked.single { it.getString("id") == "contact" }.getDouble("score") < 0)
        assertTrue(ranked.single { it.getString("id") == "reply" }.getJSONArray("reasons").toString().contains("更专注"))
        assertEquals("r", engaged.getJSONArray("important_changes").getJSONObject(0).getString("id"))
    }
    @Test fun sustainedStateAndTargetedTodoChangePriorityWithoutForcingChoice() {
        val d = definition().put("halfLifeHours", 0)
        d.getJSONArray("effects").put(JSONObject().put("action", "todo").put("target", "todo-b").put("weight", 10).put("cue", "先继续这个问题"))
        val data = data(d)
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        val candidates = listOf("todo-a", "todo-b").map { JSONObject().put("action", "todo").put("id", it) }
        val first = InternalStateEngine.rank(InternalStateEngine.snapshot(data, "a", 0), candidates)
        val later = InternalStateEngine.rank(InternalStateEngine.snapshot(data, "a", 24 * hour), candidates)
        assertEquals("todo-b", first.first().getString("id"))
        assertEquals(2 * first.first().getDouble("score"), later.first().getDouble("score"), 1e-9)
        assertEquals(2, later.size)
    }
    @Test fun recordOnlyDimensionCannotAffectDecision() {
        val data = data(definition().put("participates", false))
        InternalStateEngine.event(data, "r", "reading", "a", "read", 0)
        assertTrue(InternalStateEngine.rank(InternalStateEngine.snapshot(data, "a", 0), candidates()).all { it.getDouble("score") == 0.0 })
    }
    @Test fun cannotWriteValueOrInventEvidenceOrUseNonFiniteNumbers() {
        assertThrows(IllegalArgumentException::class.java) { InternalStateEngine.validate(definition().put("value", 99)) }
        assertThrows(IllegalArgumentException::class.java) { InternalStateEngine.validate(definition().put("maxDelta", -1)) }
        assertThrows(IllegalArgumentException::class.java) { InternalStateEngine.validate(definition().put("scope", "somebody-else")) }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateEngine.evolve(data(), "deactivate", "curiosity", 1, null, null, "伪造经过", listOf("invented"), "a", 0)
        }
    }
    @Test fun backwardsClockDoesNotReverseTimeOrDuplicateMessage() {
        val data = data()
        InternalStateEngine.event(data, "r", "reading", "a", "read", hour)
        InternalStateEngine.advance(data, "a", 0)
        assertEquals(hour, InternalStateEngine.snapshot(data, "a", 0).single().getLong("at"))
    }
}
