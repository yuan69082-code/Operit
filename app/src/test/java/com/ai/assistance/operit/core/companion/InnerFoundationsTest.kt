package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InnerFoundationsTest {
    private const val HOUR = 3_600_000L

    private fun legacy(id: String, kind: String, value: Double) =
        JSONObject().put("id", id).put("name", "旧版：$id").put("description", "原有值不可遗失")
            .put("kind", kind).put("active", true).put("version", 4)
            .put("min", 0.0).put("max", 100.0).put("baseline", 20.0).put("scope", "global")
            .put("private", false).put("share", true).put("participates", true)
            .put("maxDelta", 10.0).put("halfLifeHours", 2.0).put("triggers", JSONArray())
            .put("effects", JSONArray())

    private fun input(data: JSONObject, id: String, at: Long = HOUR): InternalStateFrame {
        InternalStateEngine.event(data, id, "user_message", "chat", "真实输入", at, applyTriggers = false)
        return InternalStateFrame("frame:$id", "assistant", "chat", InternalStateEngine.semanticRevision(data),
            at, setOf(id), InternalStateEngine.definitions(data).associate { it.getString("id") to it.getInt("version") },
            data.getLong("sequence"), foundationVersions = InnerFoundations.currentVersions(data))
    }

    private fun report(frame: InternalStateFrame, foundation: JSONArray = JSONArray()) =
        JSONObject().put("frame_id", frame.id).put("updates", JSONArray()).put("create", JSONArray())
            .put("foundation", foundation)

    @Test fun allRequiredKindsAreInstalledButExperiencesAreNotInvented() {
        val foundations = InnerFoundations.kinds
        assertEquals(7, foundations.count { it.family == "drive" })
        assertEquals(9, foundations.count { it.family == "emotion" })
        assertTrue(listOf("life", "mortality", "vision", "hearing", "smell", "taste", "touch",
            "thought", "sexual", "survival").all { key -> foundations.any { it.id == "foundation:need:$key" } })
        assertEquals("foundation:need:life", InnerFoundations.find("foundation:need:survival")?.parent)
        assertTrue(listOf("person_attraction", "person_affection", "person_partner", "person_sexual",
            "person_attachment", "endogenous", "obsession", "multiple", "unknown").all { key ->
            foundations.any { it.id == "foundation:desire:$key" }
        })
        assertTrue(listOf("jealousy", "possessive_jealousy", "hurt", "regret", "guilt",
            "unreconciled", "tension", "relaxation", "restlessness", "heaviness",
            "excitement", "warmth", "open").all { key -> foundations.any { it.id == "foundation:feeling:$key" } })
        assertTrue(listOf("love", "trust", "doubt", "hate", "loathing", "avoidance").all { key ->
            foundations.any { it.id == "foundation:sentiment:$key" }
        })
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        assertFalse(data.getJSONObject("foundation_state").getJSONObject("survival").getBoolean("formed"))
        assertEquals(0, data.optJSONArray("experiences")?.length() ?: 0)
        assertEquals("not_formed", InnerFoundations.kinds.first { it.id == "foundation:emotion:joy" }
            .asJson().getString("state"))
    }

    @Test fun legacyMigrationIsIdempotentAndPreservesUnmappedSources() {
        val data = JSONObject().put("definitions", JSONArray()
            .put(legacy("intimacy", "drive", 70.0))
            .put(legacy("custom_anger", "emotion", 55.0)))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "intimacy")
                .put("scope", "global").put("value", 70).put("at", 500L))
                .put(JSONObject().put("dimension", "custom_anger").put("scope", "global")
                    .put("value", 55).put("at", 600L)))
            .put("semantic_revision", 7L)
        val oldInstances = data.getJSONArray("instances").toString()
        InnerFoundations.install(data, 1000)
        assertEquals(oldInstances, data.getJSONArray("instances").toString())
        assertEquals(8, data.getLong("semantic_revision"))
        assertTrue(InternalStateEngine.definitions(data).none { it.getBoolean("active") })
        assertEquals(0.7, data.getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:intimacy").getDouble("value"), 0.0000001)
        val legacyRows = InnerFoundations.legacyPage(data, 0).getJSONArray("items")
        assertEquals("linked_by_exact_engine_id", legacyRows.getJSONObject(0).getString("status"))
        assertEquals("pending_semantic_mapping", legacyRows.getJSONObject(1).getString("status"))
        assertEquals(55.0, legacyRows.getJSONObject(1).getDouble("source_value"), 0.0001)
        assertEquals(4, data.getJSONArray("versions").getJSONObject(0).getJSONObject("before").getInt("version"))
        val after = data.toString()
        InnerFoundations.install(data, 12_000)
        assertEquals(after, data.toString())
    }

    @Test fun sevenDriveClocksAreContinuousNotAlarmsAndSurvivalNeverDecays() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val expected = listOf(6.0, 3.0, 12.0, 5.0, 4.0, 8.0, 6.0)
        assertEquals(expected, InnerFoundations.drives.map { it.hours })
        InnerFoundations.advance(data, 3 * HOUR)
        val drives = data.getJSONObject("foundation_state").getJSONObject("drives")
        val explore = drives.getJSONObject("foundation:drive:exploration").getDouble("value")
        val closeness = drives.getJSONObject("foundation:drive:intimacy").getDouble("value")
        assertTrue(explore > closeness)
        assertTrue(explore < 1.0)
        val before = data.toString()
        InnerFoundations.advance(data, 3 * HOUR)
        assertEquals(before, data.toString())
        assertEquals(0, data.getJSONObject("foundation_state").getJSONObject("survival").getInt("version"))
    }

    @Test fun strictIsrPathRejectsBeforeMutatingAndAllowsNextValidReport() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val frame = input(data, "input", HOUR)
        val bad = JSONObject().put("id", "foundation:drive:intimacy")
            .put("expected_version", 0).put("value", "HIGH")
            .put("evidence", JSONArray().put("input")).put("reason", "亲近")
        val before = data.toString()
        val fault = assertThrows(InternalStateReportIssue::class.java) {
            InternalStateReports.apply(data, frame, report(frame, JSONArray().put(bad)), HOUR)
        }
        assertEquals("ISR_FINITE_NUMBER", fault.code)
        assertEquals("foundation[0].value", fault.path)
        assertEquals(before, data.toString())
        val good = JSONObject(bad.toString()).put("value", 0.55)
        val receipt = InternalStateReports.apply(data, frame, report(frame, JSONArray().put(good)), HOUR)
        assertTrue(receipt.getBoolean("foundation_changed"))
        assertEquals(0.55, data.getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:intimacy").getDouble("value"), 0.000001)
    }

    @Test fun survivalCanReachZeroWithoutTimeOrForcedAction() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val frame = input(data, "human-source", HOUR)
        val change = JSONObject().put("id", "foundation:need:survival")
            .put("expected_version", 0).put("value", 0.0)
            .put("evidence", JSONArray().put("human-source"))
            .put("objects", JSONArray().put("user").put("personal_goal"))
            .put("goals", JSONArray().put("持续自己的生活"))
        val receipt = InternalStateReports.apply(data, frame, report(frame, JSONArray().put(change)), HOUR)
        assertTrue(receipt.getBoolean("foundation_changed"))
        val survival = data.getJSONObject("foundation_state").getJSONObject("survival")
        assertTrue(survival.getBoolean("formed"))
        assertEquals(0.0, survival.getDouble("value"), 0.0)
        assertEquals(2, survival.getJSONArray("objects").length())
        val before = survival.toString()
        InnerFoundations.advance(data, HOUR * 80)
        assertEquals(before, survival.toString())
        assertFalse(data.optBoolean("stopped"))
        assertFalse(data.optBoolean("deleted"))
    }
}
