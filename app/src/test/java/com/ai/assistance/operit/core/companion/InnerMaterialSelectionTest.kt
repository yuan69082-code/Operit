package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InnerMaterialSelectionTest {
    private fun row(id: String, family: String, description: String) = JSONObject()
        .put("id", id).put("version", 1).put("origin", "subject_development").put("source", "subject_adoption")
        .put("content", JSONObject().put("id", id).put("family", family).put("kind", "一般认识")
            .put("description", description).put("status", "active"))

    @Test fun matchingDescriptionBeyondRotatingPageIsSelectedFirstWithoutChangingOriginals() {
        val data = JSONObject()
        val saved = InnerStructure.root(data).getJSONObject("records")
        for (family in listOf("personality", "preference")) repeat(24) { index ->
            val id = "$family-$index"
            saved.put(id, row(id, family, "安静阅读"))
        }
        val rows = InnerRows(data, false)
        val expected = listOf("identity", "other").map { parent ->
            val visible = rows.rotating(0, parent).map { it.getString("id") }.toSet()
            val id = saved.keys().asSequence().first {
                it !in visible && InnerRows.parentOf(saved.getJSONObject(it), false) == parent
            }
            saved.getJSONObject(id).getJSONObject("content").put("description", "对天文和星空有长期兴趣")
            id
        }
        val before = InnerStructure.hash(data)
        val result = InnerStructure.material(data, "今晚一起看星空吧", 0)
        assertEquals(expected[0], result.getJSONArray("identity").getJSONObject(0).getString("id"))
        assertEquals(expected[1], result.getJSONArray("selected").getJSONObject(0).getString("id"))
        assertEquals(8, result.getJSONArray("identity").length())
        assertEquals(8, result.getJSONArray("selected").length())
        assertEquals(before, InnerStructure.hash(data))
    }

    @Test fun retrievalReadsContextAndExceptionsButNeverSourceEvidenceOrRetiredContent() {
        val matching = row("matching", "preference", "安静阅读").apply {
            getJSONObject("content").put("context", "周末野餐").put("exceptions", "下雨时留在家里")
        }
        val retired = row("retired", "preference", "周末野餐").apply { getJSONObject("content").put("status", "retired") }
        val evidenceOnly = row("evidence-only", "preference", "安静阅读").put("evidence", JSONObject().put("fact", "周末野餐"))
        val entries = sequenceOf(matching, retired, evidenceOnly).mapNotNull(InnerMaterialSelection::entry)
        assertEquals(listOf("matching"), InnerMaterialSelection.ranked(entries,
            InnerMaterialSelection.terms("周末去野餐，下雨怎么办"), "other", 8))
    }

    @Test fun englishMatchingIsCaseInsensitiveAndEqualScoresHaveStableIdOrder() {
        val entries = sequenceOf(row("z", "personality", "Hiking"), row("a", "personality", "hiking"))
            .mapNotNull(InnerMaterialSelection::entry)
        assertEquals(listOf("a", "z"), InnerMaterialSelection.ranked(entries,
            InnerMaterialSelection.terms("HIKING?"), "identity", 8))
    }

    @Test fun longOffPageMatchSurvivesSharedDefaultBudgetAndNonzeroLaneRotation() {
        val data = JSONObject()
        val saved = InnerStructure.root(data).getJSONObject("records")
        for (family in listOf("personality", "preference")) repeat(24) { index ->
            val id = "$family-$index"
            saved.put(id, row(id, family, "安静阅读".repeat(20)))
        }
        val cursor = 5L
        val rows = InnerRows(data, false)
        val targets = listOf("identity", "other").map { parent ->
            val page = rows.rotating(cursor, parent).map { it.getString("id") }.toSet()
            saved.keys().asSequence().first { it !in page && InnerRows.parentOf(saved.getJSONObject(it), false) == parent }
        }
        saved.getJSONObject(targets[0]).getJSONObject("content")
            .put("description", "星空观察" + "完整保留的具体自我认识".repeat(600))
        saved.getJSONObject(targets[1]).getJSONObject("content").put("description", "星空观察使我感到平静")
        val original = InnerStructure.hash(data)
        val source = JSONObject().put("inner_structure", InnerStructure.material(data, "今晚看星空", cursor))
            .put("material_selection_cursor", cursor)
            .put("category_events", JSONObject().put("episodes", JSONArray()).put("focus", JSONArray()))
            .put("unions", JSONObject().put("items", JSONArray()))
        for (key in listOf("retrieved_materials", "host_materials", "states", "important_changes", "experiences",
            "thoughts", "candidates", "state_catalog")) {
            source.put(key, JSONArray((0..2).map { JSONObject().put("id", "$key-$it").put("text", "其他已有材料".repeat(35)) }))
        }

        val selected = SubjectMaterialBudget.select(source)
        val inner = selected.getJSONObject("inner_structure")
        val fragments = inner.getJSONArray("record_fragments")
        val matching = (0 until fragments.length()).map { fragments.getJSONObject(it) }
            .filter { it.getString("record_id") == targets[0] }
        assertEquals(1, matching.size)
        val part = matching.single()
        val text = InnerStructure.serialized(saved.getJSONObject(targets[0]))
        assertEquals(text.substring(part.getInt("offset"), part.getInt("end")), part.getString("text"))
        val preferences = inner.getJSONArray("selected")
        assertEquals(1, (0 until preferences.length()).count { preferences.getJSONObject(it).getString("id") == targets[1] })
        assertFalse(inner.has("_relevance_first"))
        assertTrue(source.getJSONObject("inner_structure").has("_relevance_first"))
        assertEquals(original, InnerStructure.hash(data))
        assertTrue(SubjectMaterialBudget.estimate(selected.toString()) + SubjectMaterialBudget.ENVELOPE_RESERVE <= 2048)
    }
}
