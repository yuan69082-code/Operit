package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InnerFoundationsTest {
    @Test fun inheritedConditionsContinueThroughFoundationWithoutDuplicateNumericState() {
        fun rule() = JSONObject().put("condition", "custom").put("reason", "原有具体原因")
            .put("interval_minutes", 60).put("amount", 10)
        val definition = legacy("intimacy", "drive", 70.0).put("time_rules", JSONObject()
            .put("increase", rule()).put("decrease", rule()).put("floor", 0))
        val rules = definition.getJSONObject("time_rules").toString()
        val data = JSONObject().put("definitions", JSONArray().put(definition))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "intimacy").put("scope", "global")
                .put("value", 70.0).put("at", 0L).put("since", 0L)))
        InnerFoundations.install(data, 0)
        fun confirm(at: Long, active: Boolean) {
            val frame = input(data, "condition-$at", at)
            val action = JSONObject().put("id", "foundation:drive:intimacy")
                .put("expected_version", frame.foundationVersions.getValue("foundation:drive:intimacy"))
                .put("reason", "确认原自定义条件").put("evidence", JSONArray().put("condition-$at"))
                .put("time_conditions", JSONObject().put("increase", active))
            InternalStateReports.apply(data, frame, report(frame, JSONArray().put(action)), at)
        }
        confirm(HOUR, true)
        InternalStateEngine.advance(data, "chat", 2 * HOUR)
        assertEquals(80.0, InternalStateEngine.rows(data, "instances").single().getDouble("value"), 1e-8)
        confirm(2 * HOUR, false)
        InternalStateEngine.advance(data, "chat", 3 * HOUR)
        assertEquals(80.0, InternalStateEngine.rows(data, "instances").single().getDouble("value"), 1e-8)
        assertEquals(rules, InternalStateEngine.definitions(data).single().getJSONObject("time_rules").toString())
        assertFalse(data.getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:intimacy").has("value"))
    }

    @Test fun builtinDriveRejectsInventedInheritedConditionAtomically() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val frame = input(data, "plain-drive")
        val action = JSONObject().put("id", "foundation:drive:intimacy")
            .put("expected_version", frame.foundationVersions.getValue("foundation:drive:intimacy"))
            .put("reason", "不能虚构规则").put("evidence", JSONArray().put("plain-drive"))
            .put("time_conditions", JSONObject().put("increase", true))
        val before = data.toString()
        val issue = assertThrows(InternalStateReportIssue::class.java) {
            InternalStateReports.apply(data, frame, report(frame, JSONArray().put(action)), HOUR)
        }
        assertEquals("ISR_NO_INHERITED_RULE", issue.code)
        assertEquals(before, data.toString())
    }
    private val HOUR = 3_600_000L

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

    @Test fun retainedDefinitionReportsRemainVisibleAndCannotReintroduceAutomaticSurvival() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val first = input(data, "create-original-contract")
        val definition = legacy("custom_state", "emotion", 0.0).apply {
            remove("version"); remove("active")
            fun direction() = JSONObject().put("condition", "always").put("reason", "保持当前值")
                .put("interval_minutes", 0).put("amount", 0)
            put("time_rules", JSONObject().put("increase", direction()).put("decrease", direction()).put("floor", 0))
        }
        val receipt = InternalStateReports.apply(data, first, report(first).put("create", JSONArray().put(
            JSONObject().put("definition", definition).put("value", 35.0).put("reason", "保留原规则正常入口"))), HOUR)
        assertEquals("custom_state", receipt.getJSONArray("created").getString(0))
        val created = data
        val row = InnerFoundations.legacyPage(created, 0, "chat").getJSONArray("items").getJSONObject(0)
        assertEquals("custom_state", row.getString("id"))
        assertEquals(35.0, row.getDouble("current_value"), 0.0)
        assertEquals("current_definition_and_instances", row.getString("source"))
        assertEquals("custom_state", InnerFoundations.legacyDetail(created, "custom_state", "chat")
            .getJSONObject("item").getJSONObject("current_definition").getString("id"))
        val next = input(created, "rename-attempt", HOUR + 1)
        val before = created.toString()
        val failure = assertThrows(InternalStateReportIssue::class.java) {
            InternalStateReports.apply(created, next, report(next).put("revise", JSONArray().put(JSONObject()
                .put("id", "custom_state").put("expected_version", 1).put("changes", JSONObject().put("name", "求生欲"))
                .put("reason", "不能用改名绕过专门规则"))), HOUR + 1)
        }
        assertEquals("ISR_SURVIVAL_EXPLICIT_ONLY", failure.code)
        assertEquals(before, created.toString())
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
        assertTrue(InternalStateEngine.definitions(data).all { it.getBoolean("active") })
        val alias = data.getJSONObject("foundation_state").getJSONObject("drives").getJSONObject("foundation:drive:intimacy")
        assertEquals("intimacy", alias.getString("adapter_id"))
        assertFalse(alias.has("value")) // No second numeric authority.
        assertEquals(0.7, InnerFoundations.member(data, "foundation:drive:intimacy", 500).getDouble("current_value"), 0.0000001)
        val legacyRows = InnerFoundations.legacyPage(data, 0).getJSONArray("items")
        assertEquals("linked_by_exact_engine_id", legacyRows.getJSONObject(0).getString("status"))
        assertEquals("active_rule_pending_mapping", legacyRows.getJSONObject(1).getString("status"))
        assertEquals(55.0, legacyRows.getJSONObject(1).getDouble("source_value"), 0.0001)
        assertEquals(4, InnerFoundations.legacyDetail(data, "intimacy", "chat")
            .getJSONObject("item").getJSONObject("original_definition").getInt("version"))
        val after = data.toString()
        InnerFoundations.install(data, 12_000)
        assertEquals(after, data.toString())
    }

    @Test fun windowLocalValuesNeverBecomeTheSharedDriveDuringMigration() {
        val data = JSONObject().put("definitions", JSONArray().put(legacy("intimacy", "drive", 90.0).put("scope", "session")))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "intimacy")
                .put("scope", "private-window").put("value", 90).put("at", 500L)))
        val originals = data.getJSONArray("instances").toString()
        InnerFoundations.install(data, 1000L)
        assertEquals(originals, data.getJSONArray("instances").toString())
        assertEquals("active_rule_pending_mapping", InnerFoundations.legacyPage(data, 0)
            .getJSONArray("items").getJSONObject(0).getString("status"))
        assertEquals(0.0, data.getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:intimacy").getDouble("value"), 0.0)
        val retained = InnerFoundations.legacyDetail(data, "intimacy", "private-window").getJSONObject("item")
        assertEquals(90, retained.getJSONArray("scoped_current_instances").getJSONObject(0).getInt("value"))
        assertEquals(4, retained.getJSONObject("original_definition").getInt("version"))
        assertFalse(retained.getBoolean("not_current_authority"))
        assertEquals(0, InnerFoundations.legacyDetail(data, "intimacy", "other-window")
            .getJSONObject("item").getJSONArray("scoped_current_instances").length())
    }

    @Test fun existingRulesKeepTheirClockAndLocalScopeWithoutTwoValues() {
        val data = JSONObject().put("definitions", JSONArray().put(legacy("intimacy", "drive", 80.0))
            .put(legacy("local", "emotion", 60.0).put("scope", "session")))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "intimacy").put("scope", "global")
                .put("value", 80.0).put("at", 0L).put("since", 0L))
                .put(JSONObject().put("dimension", "local").put("scope", "a").put("value", 60.0).put("at", 0L).put("since", 0L))
                .put(JSONObject().put("dimension", "local").put("scope", "b").put("value", 90.0).put("at", 0L).put("since", 0L)))
        InnerFoundations.install(data, 0)
        InternalStateEngine.advance(data, "a", 2 * HOUR)
        InnerFoundations.advance(data, 2 * HOUR)
        val rows = InternalStateEngine.snapshot(data, "a", 2 * HOUR)
        assertEquals(50.0, rows.single { it.getString("id") == "intimacy" }.getDouble("value"), 1e-8)
        assertEquals(0.5, InnerFoundations.member(data, "foundation:drive:intimacy", 2 * HOUR).getDouble("current_value"), 1e-8)
        assertEquals(40.0, rows.single { it.getString("id") == "local" }.getDouble("value"), 1e-8)
        assertEquals(90.0, InternalStateEngine.rows(data, "instances").single {
            it.getString("dimension") == "local" && it.getString("scope") == "b"
        }.getDouble("value"), 1e-8)
        val checkpoint = data.toString()
        InnerFoundations.install(data, 3 * HOUR)
        InternalStateEngine.advance(data, "a", 2 * HOUR)
        assertEquals(checkpoint, data.toString())
    }

    @Test fun mappedDriveReportsWriteTheOriginalInstanceAndRejectTwoAddressesAtomically() {
        val data = JSONObject().put("definitions", JSONArray().put(legacy("intimacy", "drive", 70.0)))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "intimacy").put("scope", "global")
                .put("value", 70.0).put("at", 0L).put("since", 0L)))
        InnerFoundations.install(data, 0)
        val frame = input(data, "new-input")
        val action = JSONObject().put("id", "foundation:drive:intimacy").put("expected_version", 1)
            .put("value", 0.4).put("reason", "重新理解").put("evidence", JSONArray().put("new-input"))
        val both = report(frame, JSONArray().put(action)).put("updates", JSONArray().put(JSONObject()
            .put("id", "intimacy").put("value", 20).put("reason", "同一事实")))
        val before = data.toString()
        val rejected = assertThrows(InternalStateReportIssue::class.java) { InternalStateReports.apply(data, frame, both, HOUR) }
        assertEquals("ISR_DUPLICATE_AUTHORITY_WRITE", rejected.code)
        assertEquals(before, data.toString())
        InternalStateReports.apply(data, frame, report(frame, JSONArray().put(action)), HOUR)
        assertEquals(40.0, InternalStateEngine.rows(data, "instances").single().getDouble("value"), 1e-8)
        assertFalse(data.getJSONObject("foundation_state").getJSONObject("drives")
            .getJSONObject("foundation:drive:intimacy").has("value"))
        val next = input(data, "second-input", 2 * HOUR)
        InternalStateReports.apply(data, next, report(next).put("updates", JSONArray().put(JSONObject()
            .put("id", "intimacy").put("value", 60.0).put("reason", "进一步理解"))), 2 * HOUR)
        assertEquals(0.6, InnerFoundations.member(data, "foundation:drive:intimacy", 2 * HOUR).getDouble("current_value"), 1e-8)
        assertEquals(3, InnerFoundations.member(data, "foundation:drive:intimacy", 2 * HOUR).getInt("version"))
    }

    @Test fun adoptedUnionTimeRuleReplacesOnlyTheAliasedReducerAndNewAppraisalInvalidatesIt() {
        val seed = JSONObject().put("definitions", JSONArray().put(legacy("intimacy", "drive", 70.0)))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "intimacy").put("scope", "global")
                .put("value", 70.0).put("at", 0L).put("since", 0L)))
        val data = InternalStateDocument(seed, object : InternalStateHistoryAccess {
            override fun definition(id: String, version: Int): JSONObject? = null
            override fun latestContact(chat: String?, at: Long): Long? = null
            override fun contacts(chat: String?, after: Long, until: Long): Sequence<Long> = emptySequence()
        })
        InnerFoundations.install(data, 0)
        val id = "foundation:drive:intimacy"
        val rest = "foundation:drive:rest"
        val f = input(data, "understanding", 0).copy(interactionMembers = mapOf("foundation:$id" to 1, "foundation:$rest" to 0))
        val union = JSONObject().put("id", "pace").put("expected_version", 0)
            .put("members", JSONArray().put(JSONObject().put("kind", "foundation").put("id", id).put("version", 1))
                .put(JSONObject().put("kind", "foundation").put("id", rest).put("version", 0)))
            .put("objects", JSONArray()).put("evidence", JSONArray().put("understanding"))
            .put("interpretation", "保持这份连接的动力，留出休息的空间").put("decision", "暂按自己的节奏")
            .put("status", "active").put("effects", JSONArray().put(JSONObject().put("from", "foundation:$rest")
                .put("to", "foundation:$id").put("meaning", "明确选择暂时保持").put("time_rule", JSONObject().put("curve", "hold")))
                .put(JSONObject().put("from", "foundation:$id").put("to", "foundation:$rest").put("meaning", "明确选择另一节奏")
                    .put("time_rule", JSONObject().put("curve", "linear").put("target", 1.0).put("seconds", 3600))))
        InternalStateReports.apply(data, f, report(f).put("unions", JSONArray().put(union)), 0)
        InternalStateEngine.advance(data, "chat", HOUR)
        InnerFoundations.advance(data, HOUR)
        assertEquals(70.0, InternalStateEngine.rows(data, "instances").single().getDouble("value"), 1e-8)
        assertEquals(0.7, InnerFoundations.member(data, id, HOUR).getDouble("current_value"), 1e-8)
        assertEquals(1.0, data.getJSONObject("foundation_state").getJSONObject("drives").getJSONObject(rest).getDouble("value"), 1e-8)
        val next = input(data, "new-understanding", 2 * HOUR)
        InternalStateReports.apply(data, next, report(next, JSONArray().put(JSONObject().put("id", id)
            .put("expected_version", 1).put("value", 0.4).put("reason", "新的独立评价")
            .put("evidence", JSONArray().put("new-understanding"))).put(JSONObject().put("id", rest)
            .put("expected_version", 0).put("value", 0.3).put("reason", "新的休息评价")
            .put("evidence", JSONArray().put("new-understanding")))), 2 * HOUR)
        val history = data.getJSONObject("category_commit").getJSONArray("rows")
        val restChange = (0 until history.length()).map { history.getJSONObject(it) }.single { it.optString("id") == rest }
        assertEquals(1.0, restChange.getJSONObject("content").getJSONObject("before").getDouble("value"), 1e-8)
        InternalStateEngine.advance(data, "chat", 3 * HOUR)
        val expected = 20.0 + 20.0 * kotlin.math.sqrt(0.5)
        assertEquals(expected, InternalStateEngine.rows(data, "instances").single().getDouble("value"), 1e-8)
        assertEquals(expected / 100, InnerFoundations.member(data, id, 3 * HOUR).getDouble("current_value"), 1e-8)
    }

    @Test fun explicitDisabledTimeIsExcludedFromAdoptedTimeProjection() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val key = "foundation:foundation:drive:rest"
        val profile = JSONObject().put("storage_kind", "inner_time").put("id", key).put("parent", "pace").put("version", 1)
            .put("content", JSONObject().put("member_version", 0).put("anchor_at", 0L).put("anchor_value", 0.0)
                .put("curve", "linear").put("target", 1.0).put("seconds", 3600).put("paused_elapsed", 0L))
        data.put("category_commit", JSONObject().put("id", "adopted-rule-fixture").put("rows", JSONArray().put(profile)))
        val clock = data.getJSONObject("foundation_state")
        clock.put("paused_at", HOUR / 2)
        assertEquals(0.5, InnerFoundations.member(data, "foundation:drive:rest", HOUR * 4).getDouble("current_value"), 1e-8)
        assertEquals(0.5, InnerTemporalEffects.projection(data, key, 0, HOUR * 4) { _, _ -> error("Not a natural curve") }!!.getDouble("intensity"), 1e-8)
        clock.remove("paused_at")
        clock.put("paused_elapsed", HOUR * 3)
        assertEquals(0.5, InnerFoundations.member(data, "foundation:drive:rest", HOUR * 3 + HOUR / 2).getDouble("current_value"), 1e-8)
        assertEquals(1.0, InnerFoundations.member(data, "foundation:drive:rest", HOUR * 4).getDouble("current_value"), 1e-8)
    }

    @Test fun schemaOneRecoveryUsesArchivedMigrationAndNeverReactivatesUserRetirement() {
        val source = JSONObject().put("definitions", JSONArray().put(legacy("custom", "emotion", 55.0))
            .put(legacy("retired", "emotion", 10.0).put("active", false)))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "custom").put("scope", "global")
                .put("value", 55).put("at", 0L).put("since", 0L)))
        InnerFoundations.install(source, 0)
        source.getJSONObject("foundation_state").put("schema", 1)
        source.getJSONArray("definitions").getJSONObject(0).put("active", false).put("version", 5)
        val migration = JSONObject().put("operation", "foundation_handoff").put("dimension", "custom")
            .put("version", 5).put("before", legacy("custom", "emotion", 55.0))
        val data = InternalStateDocument(source, object : InternalStateHistoryAccess {
            override fun definition(id: String, version: Int): JSONObject? = null
            override fun definitionChange(id: String, version: Int): JSONObject? = if (id == "custom" && version == 5) migration else null
            override fun latestContact(chat: String?, at: Long): Long? = null
            override fun contacts(chat: String?, after: Long, until: Long): Sequence<Long> = emptySequence()
        })
        InnerFoundations.install(data, HOUR)
        assertTrue(InternalStateEngine.definitions(data).first { it.getString("id") == "custom" }.getBoolean("active"))
        assertFalse(InternalStateEngine.definitions(data).first { it.getString("id") == "retired" }.getBoolean("active"))
        assertEquals(55, data.getJSONArray("instances").getJSONObject(0).getInt("value"))
        assertEquals(HOUR, data.getJSONArray("instances").getJSONObject(0).getLong("at"))
        val restored = data.toString()
        InnerFoundations.install(data, HOUR * 4)
        assertEquals(restored, data.toString())
        assertEquals(4, InnerFoundations.legacyDetail(data, "custom", "chat").getJSONObject("item")
            .getJSONObject("original_definition").getInt("version"))
    }

    @Test fun legacySurvivalKeepsOriginalButNeverUsesItsOldTimeRule() {
        val data = JSONObject().put("definitions", JSONArray().put(legacy("old_survival", "drive", 70.0).put("name", "求生欲")))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "old_survival").put("scope", "global")
                .put("value", 70.0).put("at", 0L).put("since", 0L)))
        InnerFoundations.install(data, 0)
        InternalStateEngine.advance(data, "a", 100 * HOUR)
        assertEquals(70.0, data.getJSONArray("instances").getJSONObject(0).getDouble("value"), 0.0)
        assertFalse(InnerFoundations.member(data, "foundation:need:survival", 100 * HOUR).getBoolean("formed"))
        assertEquals("pending_survival_mapping", InnerFoundations.legacyPage(data, 0).getJSONArray("items").getJSONObject(0).getString("status"))
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


    @Test fun survivalReferenceHistoryPreservesPriorObjectsBeforeAnExplicitRevision() {
        val data = JSONObject()
        InnerFoundations.install(data, 0)
        val first = input(data, "first-survival", HOUR)
        val create = JSONObject().put("id", "foundation:need:survival")
            .put("expected_version", 0).put("value", 0.7)
            .put("evidence", JSONArray().put("first-survival"))
            .put("objects", JSONArray().put("person:A")).put("goals", JSONArray().put("keep_living"))
        InternalStateReports.apply(data, first, report(first, JSONArray().put(create)), HOUR)
        val second = input(data, "second-survival", 2 * HOUR)
        val revised = JSONObject().put("id", "foundation:need:survival")
            .put("expected_version", 1).put("value", 0.5)
            .put("evidence", JSONArray().put("second-survival"))
            .put("objects", JSONArray().put("person:B")).put("goals", JSONArray().put("new_goal"))
        InternalStateReports.apply(data, second, report(second, JSONArray().put(revised)), 2 * HOUR)
        val rows = data.getJSONObject("category_commit").getJSONArray("rows")
        val change = (0 until rows.length()).map { rows.getJSONObject(it) }
            .last { it.optString("storage_kind") == "foundation_change" &&
                it.optString("id") == "foundation:need:survival" }
            .getJSONObject("content")
        assertEquals("person:A", change.getJSONObject("before").getJSONArray("objects").getString(0))
        assertEquals("person:B", change.getJSONObject("after").getJSONArray("objects").getString(0))
        assertEquals("keep_living", change.getJSONObject("before").getJSONArray("goals").getString(0))
        assertEquals("new_goal", change.getJSONObject("after").getJSONArray("goals").getString(0))
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
