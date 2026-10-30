package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateUserEditTest {
    private val minute = 60000L
    private fun rules(condition: String = "always", amount: Double = 1.0) = JSONObject()
        .put("increase", JSONObject().put("condition", condition).put("reason", "增加原因")
            .put("interval_minutes", 1).put("amount", amount))
        .put("decrease", JSONObject().put("condition", "always").put("reason", "降低原因")
            .put("interval_minutes", 1).put("amount", 0))
        .put("floor", 10)
        .also { if (condition == "not_contacted") it.put("contact_window_minutes", 360) }

    private fun definition(id: String = "missing", scope: String = "global", timeRules: JSONObject? = null) = JSONObject()
        .put("id", id).put("name", "思念").put("description", "已有状态简介")
        .put("baseline", 50).put("min", 0).put("max", 100).put("scope", scope)
        .put("private", false).put("share", false).put("participates", true)
        .put("maxDelta", 1).put("halfLifeHours", 0).put("triggers", JSONArray()).put("effects", JSONArray())
        .also { if (timeRules != null) it.put("kind", "sentiment").put("time_rules", timeRules) }

    private fun data(vararg definitions: JSONObject): JSONObject = JSONObject().also { data ->
        InternalStateEngine.event(data, "message:user:0", "user_message", "chat", "真实消息", 0, applyTriggers = false, contactAt = 0)
        definitions.forEach { definition ->
            InternalStateEngine.evolve(data, "create", definition.getString("id"), 0, definition, null,
                "已有经历", listOf("message:user:0"), "chat", 0)
        }
    }

    private fun definitionOf(data: JSONObject, id: String = "missing") = InternalStateEngine.definitions(data).single { it.getString("id") == id }
    private fun state(data: JSONObject, id: String = "missing", scope: String = "global") =
        InternalStateEngine.rows(data, "instances").single { it.getString("dimension") == id && it.getString("scope") == scope }
    private fun edit(data: JSONObject, changes: JSONObject, reason: String = "用户修改原因", now: Long = 0,
                     reveal: Boolean = false, advanceTime: Boolean = true): JSONObject =
        InternalStateUserEdits.apply(data, "chat", "missing", definitionOf(data).getInt("version"),
            InternalStateEngine.semanticRevision(data), changes, reason, reveal, now, advanceTime)

    private fun frame(data: JSONObject, id: String = "frame") = InternalStateFrame(id, "role", "chat",
        InternalStateEngine.semanticRevision(data), 0, setOf("message:user:0"),
        InternalStateEngine.definitions(data).associate { it.getString("id") to it.getInt("version") }, data.getLong("sequence"))
    private fun report(frame: InternalStateFrame, value: Double = 80.0) = JSONObject().put("frame_id", frame.id)
        .put("updates", JSONArray().put(JSONObject().put("id", "missing").put("value", value).put("reason", "模型后续的理解")))
        .put("create", JSONArray())

    @Test fun userCanChangeLegacyValueReasonAndCategoryWithoutDefiningTimeRulesOrResettingHistory() {
        val data = data(definition(), definition("untouched"))
        val oldVersion = InternalStateEngine.rows(data, "versions").first().toString()
        val untouched = state(data, "untouched").toString()
        val revision = InternalStateEngine.semanticRevision(data)
        val observation = edit(data, JSONObject().put("value", 95).put("kind", "psychology"))
        assertEquals(95.0, state(data).getDouble("value"), 0.0)
        assertEquals("用户修改原因", state(data).getString("reason"))
        assertEquals("psychology", definitionOf(data).getString("kind"))
        assertEquals(2, definitionOf(data).getInt("version"))
        assertFalse(InternalStateTimeRules.isDefined(definitionOf(data)))
        assertEquals(50.0, definitionOf(data).getDouble("baseline"), 0.0)
        assertEquals(oldVersion, InternalStateEngine.rows(data, "versions").first().toString())
        assertEquals(untouched, state(data, "untouched").toString())
        assertEquals(revision + 1, observation.getLong("semantic_revision"))
        val event = InternalStateEngine.rows(data, "events").last()
        val experience = InternalStateEngine.rows(data, "experiences").single()
        assertEquals("user_edit", event.getString("source"))
        assertEquals("user_edit", experience.getString("source"))
        assertEquals(event.getString("id"), InternalStateEngine.experienceEventId(experience))
        assertEquals(event.getString("id"), state(data).getString("experience_event_id"))
        assertFalse(state(data).has("report_frame_id"))
        assertFalse(experience.has("frame_id"))
        assertEquals("user_edit", InternalStateEngine.rows(data, "versions").last().getString("source"))
    }

    @Test fun invalidAndStaleEditsLeaveEveryValueRuleAndClockUnchanged() {
        val data = data(definition(timeRules = rules()))
        val before = data.toString()
        val invalid = listOf(JSONObject().put("value", 101), JSONObject().put("value", "60"),
            JSONObject().put("kind", "custom"), JSONObject().put("time_rules", JSONObject()),
            JSONObject().put("private", false), JSONObject().put("scope", "session"))
        invalid.forEach { patch ->
            assertThrows(Exception::class.java) { edit(data, patch, now = 5 * minute) }
            assertEquals(before, data.toString())
        }
        assertThrows(IllegalArgumentException::class.java) { edit(data, JSONObject(), reason = " ") }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateUserEdits.apply(data, "chat", "missing", 1, InternalStateEngine.semanticRevision(data) - 1,
                JSONObject().put("value", 60), "过期界面", false, 5 * minute)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateUserEdits.apply(data, "chat", "missing", 0, InternalStateEngine.semanticRevision(data),
                JSONObject().put("value", 60), "过期规则", false, 5 * minute)
        }
        assertEquals(before, data.toString())
    }

    @Test fun zeroContactWindowStartsFutureAccrualWithoutPretendingTheEditIsNewContact() {
        val data = data(definition(timeRules = rules("not_contacted")))
        val editedRules = rules("not_contacted").put("contact_window_minutes", 0)
        edit(data, JSONObject().put("time_rules", editedRules), now = 5 * minute)
        assertEquals(50.0, state(data).getDouble("value"), 0.0)
        InternalStateEngine.advance(data, "chat", 6 * minute)
        assertEquals(51.0, state(data).getDouble("value"), 0.0001)
        assertEquals(1, InternalStateEngine.rows(data, "events").count { it.getString("source") == "user_message" })
        assertEquals(0L, InternalStateEngine.rows(data, "events").first().getLong("contact_at"))
    }

    @Test fun ruleRevisionSettlesEveryExistingSessionButPreservesUnrelatedStateAndOtherSessionReason() {
        val data = data(definition(scope = "session", timeRules = rules()), definition("untouched", timeRules = rules(amount = 9.0)))
        InternalStateEngine.snapshot(data, "other-chat", 0)
        val untouched = state(data, "untouched").toString()
        edit(data, JSONObject().put("time_rules", rules(amount = 2.0)), now = 3 * minute)
        assertEquals(53.0, state(data, scope = "chat").getDouble("value"), 0.0001)
        assertEquals(53.0, state(data, scope = "other-chat").getDouble("value"), 0.0001)
        assertFalse(state(data, scope = "other-chat").has("reason"))
        assertEquals(untouched, state(data, "untouched").toString())
        InternalStateEngine.advanceDimension(data, definitionOf(data), "other-chat", 4 * minute)
        assertEquals(55.0, state(data, scope = "other-chat").getDouble("value"), 0.0001)
        assertEquals(53.0, state(data, scope = "chat").getDouble("value"), 0.0001)
    }

    @Test fun pausedEditingChangesMetadataWithoutAccruingAnyPausedElapsedTime() {
        val data = data(definition(scope = "session", timeRules = rules()))
        InternalStateEngine.snapshot(data, "other-chat", 0)
        edit(data, JSONObject().put("kind", "emotion").put("time_rules", rules(amount = 3.0)),
            now = 10 * minute, advanceTime = false)
        assertEquals(50.0, state(data, scope = "chat").getDouble("value"), 0.0)
        assertEquals(50.0, state(data, scope = "other-chat").getDouble("value"), 0.0)
        assertEquals(0L, state(data, scope = "other-chat").getLong("at"))
        assertEquals("emotion", definitionOf(data).getString("kind"))
        assertTrue(InternalStateEngine.rows(data, "events").none { it.getString("source") == "time" })
    }

    @Test fun oldModelFrameCannotUndoUserEditAndNewModelReportRestoresItsOwnAttribution() {
        val data = data(definition())
        val stale = frame(data)
        edit(data, JSONObject().put("value", 70))
        val edited = data.toString()
        assertThrows(IllegalArgumentException::class.java) { InternalStateReports.apply(data, stale, report(stale), 0) }
        assertEquals(edited, data.toString())
        val fresh = frame(data, "fresh")
        InternalStateReports.apply(data, fresh, report(fresh), 0)
        assertEquals(80.0, state(data).getDouble("value"), 0.0)
        assertEquals("state_report", state(data).getString("experience_source"))
        assertEquals("state_report:fresh", state(data).getString("experience_event_id"))
        assertEquals("fresh", state(data).getString("report_frame_id"))
        assertEquals(2, InternalStateEngine.rows(data, "experiences").size)
    }

    @Test fun reasonOnlyEditRecordsEqualValuePointAndPreservesUnrelatedExperienceFields() {
        val data = data(definition())
        val frame = frame(data)
        val report = report(frame)
        report.getJSONArray("updates").getJSONObject(0).put("desire", "继续看书").put("choice", "暂时安静").put("unresolved", true)
        InternalStateReports.apply(data, frame, report, 0)
        val previous = InternalStateEngine.rows(data, "experiences").single().toString()
        val observation = edit(data, JSONObject(), reason = "更正当前原因")
        assertEquals("继续看书", state(data).getString("desire"))
        assertEquals("暂时安静", state(data).getString("choice"))
        assertTrue(state(data).getBoolean("unresolved"))
        assertEquals(1, definitionOf(data).getInt("version"))
        assertEquals(previous, InternalStateEngine.rows(data, "experiences").first().toString())
        val change = InternalStateEngine.rows(observation, "events").last().getJSONArray("changes").getJSONObject(0)
        assertEquals(change.getDouble("before"), change.getDouble("after"), 0.0)
        assertEquals("更正当前原因", change.getString("reason"))
        assertEquals("更正当前原因", InternalStateEngine.rows(observation, "experiences").last().getString("reason"))
    }

    @Test fun hiddenStatesCannotBeEditedAndPrivateEditReasonsStayPrivateAfterLaterPublication() {
        val data = data(definition().put("private", true).put("share", true))
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) { edit(data, JSONObject().put("value", 70)) }
        assertEquals(before, data.toString())
        edit(data, JSONObject().put("value", 70).put("kind", "sentiment"), reason = "私密手动原因", reveal = true)
        assertEquals(0, InternalStateEngine.observation(data, "chat", 0, false).getJSONArray("states").length())
        val published = JSONObject(definitionOf(data).toString()).put("private", false).apply { remove("version"); remove("active") }
        InternalStateEngine.evolve(data, "revise", "missing", 2, published, null, "公开后续规则", listOf("message:user:0"), "chat", 0)
        val visible = InternalStateEngine.observation(data, "chat", 0, false)
        assertEquals(1, visible.getJSONArray("states").length())
        assertFalse(visible.toString().contains("私密手动原因"))
        assertFalse(visible.getJSONArray("states").getJSONObject(0).has("experience_source"))
        assertEquals(0, visible.getJSONArray("experiences").length())
    }

    @Test fun changedCustomMeaningClearsOnlyItsConfirmationWhileCategoryKeepsBoth() {
        val timeRules = rules("custom")
        timeRules.getJSONObject("decrease").put("condition", "custom")
        val data = data(definition(timeRules = timeRules))
        InternalStateTimeRules.writeConditions(state(data), definitionOf(data), JSONObject().put("increase", true).put("decrease", false))
        edit(data, JSONObject().put("kind", "emotion"))
        assertTrue(state(data).getJSONObject("time_conditions").getBoolean("increase"))
        assertFalse(state(data).getJSONObject("time_conditions").getBoolean("decrease"))
        val changed = JSONObject(timeRules.toString())
        changed.getJSONObject("increase").put("reason", "完全不同的条件")
        edit(data, JSONObject().put("time_rules", changed))
        assertFalse(state(data).getJSONObject("time_conditions").has("increase"))
        assertFalse(state(data).getJSONObject("time_conditions").getBoolean("decrease"))
        assertEquals(1, state(data).getJSONObject("time_condition_versions").getInt("decrease"))
    }
}
