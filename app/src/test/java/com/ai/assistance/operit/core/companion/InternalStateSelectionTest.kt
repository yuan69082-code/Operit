package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateSelectionTest {
    private fun definition(id: String) = JSONObject()
        .put("id", id).put("name", "自己定义的$id").put("description", "保留原来的具体含义")
        .put("min", 0).put("max", 100).put("baseline", 50).put("maxDelta", 20).put("halfLifeHours", 17)
        .put("scope", "global").put("private", true).put("share", false).put("participates", true)
        .put("triggers", JSONArray()).put("effects", JSONArray()).put("active", true).put("version", 3)

    private fun todo(id: String, title: String = "待办$id", updated: Long = 0) = JSONObject()
        .put("id", id).put("action", "todo").put("title", title).put("next", "独立保存的下一步")
        .put("status", "open").put("available", true).put("updated", updated)

    @Test fun entireCatalogueSurvivesZeroDetailBudgetWithoutReclassifyingOrChangingStoredRules() {
        val definitions = (0 until 48).map { definition("d$it") }
        val data = JSONObject().put("definitions", JSONArray(definitions)).put("selection_cursor", 12)
        val before = data.toString()

        val selected = InternalStateSelection.select(data, "chat", 100, "当前输入", emptyList(), emptyList(), detailCharacters = 0)

        assertEquals(before, data.toString())
        assertEquals(48, selected.payload.getJSONArray("state_catalog").length())
        assertTrue(selected.selectedIds.isEmpty())
        assertEquals(12L, selected.nextCursor)
        val identity = selected.payload.getJSONArray("state_catalog").getJSONObject(0)
        assertEquals("unclassified", identity.getString("kind"))
        assertEquals("undefined", identity.getString("time_rules_status"))
        assertEquals(3, identity.getInt("version"))
        assertTrue(identity.getBoolean("private"))
        assertFalse(identity.getBoolean("share"))
        assertTrue(identity.getBoolean("participates"))
        assertEquals(17, definitions.first().getInt("halfLifeHours"))
        assertFalse(definitions.first().has("kind"))
        assertFalse(data.has("instances"))
    }

    @Test fun aRecordOnlyPrivateStateKeepsItsGatesWithoutAnyDetailedRecord() {
        val definition = definition("record_only").put("participates", false).put("share", true)
        val selected = InternalStateSelection.select(JSONObject().put("definitions", JSONArray().put(definition)),
            "chat", 100, "", emptyList(), emptyList(), detailCharacters = 0)
        val identity = selected.payload.getJSONArray("state_catalog").getJSONObject(0)
        assertTrue(identity.getBoolean("private"))
        assertTrue(identity.getBoolean("share"))
        assertFalse(identity.getBoolean("participates"))
        assertTrue(selected.selectedIds.isEmpty())
    }

    @Test fun relevantOldTodoIsFoundBeforeBudgetingInsteadOfBeingDroppedByANewestThirtyTwoLimit() {
        val candidates = (0 until 50).map { todo("t%02d".format(it), updated = 1000L + it) } +
            todo("z_old", "黄昏潮汐的长期问题", updated = 1)
        val selected = InternalStateSelection.select(JSONObject(), "chat", 2000,
            "我想继续黄昏潮汐这个问题", candidates, emptyList(), detailCharacters = 700)

        assertTrue("candidate:todo:z_old" in selected.selectedIds)
        assertEquals(candidates.size, selected.payload.getJSONObject("selection").getInt("candidate_count"))
        assertTrue(selected.payload.getJSONObject("selection").getInt("selected_record_characters") <= 700)
    }

    @Test fun rotationGivesNeglectedCandidatesAnOpportunityEvenWhenTheSameInputRemainsRelevant() {
        val data = JSONObject()
        val candidates = (0 until 12).map { todo("t%02d".format(it), "相同长度事项") }
        val ranked = InternalStateEngine.rank(emptyList(), listOf(candidates.first())).single()
        val oneRecordBudget = JSONObject(ranked.toString()).put("selection_reason", "ROTATION").toString().length
        val seen = mutableSetOf<String>()

        repeat(candidates.size) {
            val selected = InternalStateSelection.select(data, "chat", 100, "一直关注t00", candidates, emptyList(),
                detailCharacters = oneRecordBudget)
            assertEquals(1, selected.selectedIds.size)
            seen += selected.selectedIds
            // Only the actual-send host path persists this cursor; select itself stays pure.
            data.put("selection_cursor", selected.nextCursor)
        }
        assertEquals(candidates.map { "candidate:todo:${it.getString("id")}" }.toSet(), seen)
    }

    @Test fun anOversizedCausalRecordIsOmittedWholeAndItsIdentityStillExists() {
        val reason = "完整前因：" + "未解决的具体经过。".repeat(180) + "因此我仍然在意这个后果。"
        val data = JSONObject().put("definitions", JSONArray().put(definition("unfinished")))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "unfinished").put("scope", "global")
                .put("value", 65).put("at", 10).put("since", 10).put("reason", reason)
                .put("unresolved", true).put("experience_at", 10)))
        val before = data.toString()
        val small = InternalStateSelection.select(data, "chat", 20, "未解决", emptyList(), emptyList(), detailCharacters = 300)

        assertEquals(before, data.toString())
        assertEquals(0, small.payload.getJSONArray("states").length())
        assertEquals("unfinished", small.payload.getJSONArray("state_catalog").getJSONObject(0).getString("id"))
        val decision = InternalStateEngine.rows(JSONObject().put("rows", small.decisions), "rows").single()
        assertEquals("DETAIL_EXCEEDS_BUDGET", decision.getString("reason"))
        assertFalse(small.payload.toString().contains("完整前因"))

        val enough = InternalStateSelection.select(data, "chat", 20, "未解决", emptyList(), emptyList(), detailCharacters = 5000)
        assertEquals(reason, enough.payload.getJSONArray("states").getJSONObject(0).getString("reason"))
    }

    @Test fun currentInputWithNoNumericChangeStillProvidesARealReportSource() {
        val data = JSONObject()
        InternalStateEngine.event(data, "input-one", "user_message", "chat", "本轮原话", 100, applyTriggers = false)
        assertEquals(0, InternalStateEngine.rows(data, "events").single().getJSONArray("changes").length())
        val before = data.toString()

        val selected = InternalStateSelection.select(data, "chat", 100, "本轮原话", emptyList(), emptyList(),
            currentEventId = "input-one", detailCharacters = 0)

        assertEquals(setOf("input-one"), selected.eventIds)
        assertEquals(before, data.toString())
        assertTrue(selected.selectedIds.isEmpty())
    }

    @Test fun sourceIdsMustExistAndCannotComeFromTheFuture() {
        val data = JSONObject()
        InternalStateEngine.event(data, "later", "wake", "chat", "稍后的实际唤醒", 200, applyTriggers = false)
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateSelection.select(data, "chat", 100, "当前输入", emptyList(), emptyList(), currentEventId = "later")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateSelection.select(data, "chat", 100, "当前输入", emptyList(), emptyList(), currentEventId = "invented")
        }
    }

    @Test fun differentObjectsKeepSeparateIdentitiesEvenWithTheSameDisplayName() {
        val first = definition("toward_a").put("name", "在意").put("object_ref", "person:a")
        val second = definition("toward_b").put("name", "在意").put("object_ref", "person:b")
        val selected = InternalStateSelection.select(JSONObject().put("definitions", JSONArray().put(first).put(second)),
            "chat", 100, "person:b", emptyList(), emptyList(), detailCharacters = 0)
        val catalogue = InternalStateEngine.rows(selected.payload, "state_catalog")
        assertEquals(setOf("toward_a", "toward_b"), catalogue.map { it.getString("id") }.toSet())
        assertEquals(setOf("person:a", "person:b"), catalogue.map { it.getString("object_ref") }.toSet())
    }

    @Test fun catalogueObjectRetainsItsPrivateOriginAfterTheCurrentDefinitionBecomesPublic() {
        val original = definition("toward_a").put("version", 1).put("object_ref", "person:a")
        val current = JSONObject(original.toString()).put("version", 2).put("private", false).put("share", true)
        val data = JSONObject().put("definitions", JSONArray().put(current))
            .put("versions", JSONArray().put(JSONObject().put("dimension", "toward_a").put("version", 1).put("after", original)))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "toward_a").put("scope", "global")
                .put("value", 50).put("at", 0).put("since", 0).put("object_ref", "person:a")
                .put("semantic_versions", JSONObject().put("object_ref", 1))))
        val selected = InternalStateSelection.select(data, "chat", 100, "", emptyList(), emptyList(), detailCharacters = 0)
        val identity = selected.payload.getJSONArray("state_catalog").getJSONObject(0)
        assertFalse(identity.getBoolean("private"))
        val origin = identity.getJSONObject("disclosure").getJSONObject("fields").getJSONObject("object_ref")
        assertTrue(origin.getBoolean("private"))
        assertFalse(origin.getBoolean("share"))
        assertEquals(1, origin.getInt("origin_version"))
        assertTrue(selected.selectedIds.isEmpty())
    }

    @Test fun directlyRelatedSunkenThoughtCanReturnWithoutRevivingDroppedThoughts() {
        val thoughts = listOf(
            JSONObject().put("id", "related").put("text", "潮汐现象的问题").put("state", "sink").put("surface_at", 10000).put("updated", 1),
            JSONObject().put("id", "dropped").put("text", "潮汐现象的问题").put("state", "drop").put("updated", 1),
            JSONObject().put("id", "not_due").put("text", "暂时沉下的其他问题").put("state", "sink").put("surface_at", 10000).put("updated", 1),
        )
        val before = JSONArray(thoughts).toString()
        val selected = InternalStateSelection.select(JSONObject(), "chat", 100, "潮汐现象", emptyList(), thoughts)
        assertEquals(listOf("related"), InternalStateEngine.rows(selected.payload, "thoughts").map { it.getString("id") })
        assertEquals(before, JSONArray(thoughts).toString())
    }
}
