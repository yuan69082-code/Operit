package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic model outputs through the real report/selection reducers. No private chat fixture. */
class SubjectContractsTest {
    private val archive = RecallTestHistory()
    private fun data(): InternalStateDocument {
        val base = JSONObject()
        InnerStructure.root(base)
        return InternalStateDocument(base, archive).also {
            InnerFoundations.install(it, 1)
            InternalStateEngine.event(it, "input", "user_message", "chat", "既有经历原文", 10, applyTriggers = false)
        }
    }
    private fun frame(data: JSONObject, materials: List<JSONObject> = emptyList(), id: String = "round") =
        InternalStateFrame(id, "owner", "chat", InternalStateEngine.semanticRevision(data), 20,
            setOf("input"), emptyMap(), data.getLong("sequence"),
            innerVersions = InnerStructure.versions(data), innerPending = InnerStructure.pendingBindings(data),
            innerObjects = setOf("user"), foundationVersions = InnerFoundations.currentVersions(data),
            interactionMembers = InnerFoundations.currentVersions(data).mapKeys { "foundation:${it.key}" },
            materialSources = materials.associate { it.getString("id") to it.toString() })

    private fun material(text: String = "检索来源".repeat(900), private: Boolean = false) = JSONObject()
        .put("id", "material:tool:actual").put("type", "tool_result").put("owner", "owner")
        .put("author", "host_executor").put("at", 10).put("status", "retrieved").put("text", text)
        .put("subject_private_material", private).put("locator", JSONObject().put("tool", "companion_internal_state"))

    private fun report(f: InternalStateFrame, field: String, value: Any) = JSONObject()
        .put("frame_id", f.id).put("updates", JSONArray()).put("create", JSONArray()).put(field, value)

    private fun episode() = JSONObject().put("op", "appraise").put("id", "event")
        .put("expected_version", 0).put("category_ref", "foundation:feeling:guilt")
        .put("objects", JSONArray()).put("goals", JSONArray()).put("intensity", 0.8)
        .put("importance", "对这次认识重要").put("long_term_impact", "以后重新考虑")
        .put("understanding", "这是当前体验").put("reason", "来源支持这一认识")
        .put("attention", "foreground").put("status", "unresolved").put("basis", "reinterpretation")
        .put("evidence", JSONArray().put("input").put("material:tool:actual"))

    @Test fun shortEpisodeWithLargeHostProvenanceIsAdoptedAndProjectionIsLossless() {
        val d = data()
        val m = material(private = true)
        val f = frame(d, listOf(m))
        val payload = report(f, "episodes", JSONArray().put(episode()))
        assertTrue(payload.toString().length < 1500)
        val parser = InternalStateReportStream()
        parser.append("<operit_state_report>$payload</operit_state_report>正文")
        parser.finish()
        InternalStateReports.apply(d, f, requireNotNull(parser.report), 21)
        archive.commit(d)
        val saved = requireNotNull(CategoryEvents.row(d, "episode", "event"))
        val original = saved.toString()
        assertTrue(saved.getJSONObject("content").toString().length > 5000)
        val projection = CategoryEvents.requestProjection(saved)
        assertEquals(original, saved.toString())
        assertTrue(projection.optBoolean("subject_private_material"))
        for (key in listOf("source_bindings", "original_sources")) {
            val full = saved.getJSONObject("content").getJSONObject(key).getJSONObject(m.getString("id"))
            val projected = projection.getJSONObject("content").getJSONObject(key).getJSONObject(m.getString("id"))
            assertEquals(m.toString(), full.getJSONObject("snapshot").toString())
            assertEquals(full.getString("hash"), projected.getString("hash"))
            assertFalse(projected.has("snapshot"))
        }
        assertTrue(projection.toString().length < original.length / 2)
        assertEquals(original, CategoryEvents.row(d, "episode", "event")!!.toString())
    }

    @Test fun unionHostSourcesDoNotConsumeAuthoredQuotaOrMutateArchiveOnRead() {
        val d = data()
        val m = material("完整来源".repeat(3300), private = true)
        val f = frame(d, listOf(m))
        val members = JSONArray(listOf("exploration", "rest").map { key -> JSONObject()
            .put("kind", "foundation").put("id", "foundation:drive:$key")
            .put("version", f.interactionMembers.getValue("foundation:foundation:drive:$key")) })
        val union = JSONObject().put("id", "pacing").put("expected_version", 0).put("members", members)
            .put("objects", JSONArray()).put("evidence", JSONArray().put(m.getString("id")))
            .put("interpretation", "探索和休息之间的拉扯").put("decision", "这次先观察")
            .put("status", "active").put("effects", JSONArray())
        InternalStateReports.apply(d, f, report(f, "unions", JSONArray().put(union)), 21)
        archive.commit(d)
        val saved = CategoryEvents.row(d, "union", "pacing")!!.toString()
        assertTrue(saved.length > 12000)
        val projected = InnerInteractions.material(d, chat = "chat", at = 21).getJSONArray("items").getJSONObject(0)
        assertTrue(projected.optBoolean("subject_private_material"))
        assertFalse(projected.getJSONObject("content").has("transitions"))
        assertEquals(saved, CategoryEvents.row(d, "union", "pacing")!!.toString())
    }

    @Test fun moreThanFourDecisionsAndFeedbackRemainAtomicAndHaveActualSources() {
        val d = data()
        val receipt = material("实际操作返回").put("type", "actual_operation")
            .put("locator", JSONObject().put("action_id", "actual"))
        val f = frame(d, listOf(receipt))
        val decisions = JSONArray((0..5).map { i -> JSONObject().put("id", "choice$i").put("expected_version", 0)
            .put("status", "chosen").put("meaning", "决定$i").put("evidence", JSONArray().put("input")) })
        val feedback = JSONArray((0..5).map { i -> JSONObject().put("id", "feedback$i").put("expected_version", 0)
            .put("action_id", "actual").put("result_source", receipt.getString("id"))
            .put("meaning", "当前理解$i").put("evidence", JSONArray().put(receipt.getString("id"))) })
        val payload = report(f, "decisions", decisions).put("action_feedback", feedback)
        val bad = JSONObject(payload.toString())
        bad.getJSONArray("decisions").getJSONObject(5).put("expected_version", 1)
        val before = d.toString()
        val error = assertThrows(InternalStateReportIssue::class.java) { InternalStateReports.apply(d, f, bad, 21) }
        assertEquals("decisions[5].expected_version", error.path)
        assertEquals(before, d.toString())
        InternalStateReports.apply(d, f, payload, 21)
        archive.commit(d)
        assertEquals(6L, archive.categoryCount("decision", "private"))
        assertEquals(6L, archive.categoryCount("feedback", "private"))
    }

    @Test fun actionAllowsEmptyOptionalReferencesAndExplainsCapabilityTypes() {
        val f = frame(data())
        val declaration = JSONObject().put("decision", "检查探索情况").put("intention_refs", JSONArray())
            .put("evidence", JSONArray()).put("capability", JSONObject().put("id", "research")
                .put("version", "1").put("stage", "run").put("target", "companion_explore status"))
        assertEquals(declaration.toString(), SubjectActions.parseDeclaration(declaration.toString(), f).toString())
        declaration.getJSONObject("capability").put("version", 1)
        val issue = assertThrows(InternalStateReportIssue::class.java) { SubjectActions.parseDeclaration(declaration.toString(), f) }
        assertEquals("__operit_action.capability.version", issue.path)
        assertEquals("EXPECTED_STRING", issue.rule)
        assertTrue(issue.hint.contains("字符串"))
        val malformed = assertThrows(InternalStateReportIssue::class.java) { SubjectActions.parseDeclaration("{私人原文", f) }
        assertFalse(malformed.message!!.contains("私人原文"))
        assertEquals("JSON_SHAPE", malformed.rule)
    }

    @Test fun dreamQueriesExplainMissingProcessAndKeepOptionalPagingWorking() {
        val missing = assertThrows(InternalStateReportIssue::class.java) { SubjectContracts.queryId("dream_step", null) }
        assertEquals("id", missing.path)
        assertTrue(missing.hint.contains("dream_process"))
        SubjectContracts.queryId("dream_step", "process")
        for (section in listOf("dream", "inner", "inner_pending", "experience_link")) SubjectContracts.queryId(section, null)
        val oldDream = assertThrows(InternalStateReportIssue::class.java) { SubjectContracts.dreamProcess(null) }
        assertEquals("DREAM_PROCESS_NOT_FOUND", oldDream.rule)
        assertTrue(oldDream.hint.contains("静态dream"))
    }

    @Test fun reportBudgetRemainsBoundedAndBadSourceDoesNotWriteAnything() {
        val d = data()
        val f = frame(d, listOf(material()))
        val before = d.toString()
        val tooLarge = report(f, "episodes", JSONArray().put(episode().put("understanding", "长".repeat(17000))))
        val size = assertThrows(InternalStateReportIssue::class.java) { InternalStateReports.apply(d, f, tooLarge, 21) }
        assertEquals("INPUT_SIZE", size.rule)
        assertEquals(before, d.toString())
        val invented = report(f, "episodes", JSONArray().put(episode().put("evidence", JSONArray().put("unread"))))
        assertThrows(IllegalArgumentException::class.java) { InternalStateReports.apply(d, f, invented, 21) }
        assertEquals(before, d.toString())
    }

    @Test fun experienceVersionAndTimeErrorsNameTheActualField() {
        val d = data()
        val f = frame(d)
        val row = JSONObject().put("kind", "experience").put("id", "method").put("expected_version", 0.5)
            .put("text", "认识").put("scope", "适用范围").put("uncertainty", "尚未充分验证")
            .put("status", "active").put("evidence", JSONArray().put("input"))
        val invalidVersion = assertThrows(InternalStateReportIssue::class.java) {
            InternalStateReports.apply(d, f, report(f, "life", JSONArray().put(row)), 21)
        }
        assertEquals("life[0].expected_version", invalidVersion.path)
        row.put("expected_version", 0).put("review_at", "999")
        val invalidTime = assertThrows(InternalStateReportIssue::class.java) {
            InternalStateReports.apply(d, f, report(f, "life", JSONArray().put(row)), 21)
        }
        assertEquals("life[0].review_at", invalidTime.path)
    }

    @Test fun contractsAreOnDemandAndStateTechnicalBoundsPrecisely() {
        val topics = SubjectContracts.query(null).getJSONArray("topics")
        for (i in 0 until topics.length()) {
            val c = SubjectContracts.query(topics.getString(i))
            assertEquals(16384, c.getInt("json_chars"))
            assertTrue(c.getString("contract").isNotBlank())
        }
        assertTrue(SubjectContracts.query("inner").getString("contract").contains("不能同轮提出并采用"))
        assertTrue(SubjectContracts.query("action").getString("contract").contains("不是version:1"))
        assertTrue(SubjectContracts.query("rest").getString("contract").contains("dream_step必须id="))
    }
}
