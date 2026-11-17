package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DreamDesignTest {
    @Test fun drawsCoverThemesAndBothBasesWithoutFollowingTheOldChosenTheme() {
        val found = mutableSetOf<String>()
        val bases = mutableSetOf<String>()
        repeat(200) { seed ->
            val result = DreamDesign.draw(JSONObject().put("theme_mode", "single").put("theme", "forced"), Random(seed))
            assertEquals(1, result.getJSONArray("themes").length())
            found += result.getJSONArray("themes").getString(0)
            bases += result.getString("basis")
        }
        assertEquals(DreamDesign.themes.toSet(), found)
        assertTrue("春梦" in found && "冒险" in found)
        assertEquals(setOf("materials", "inner_state"), bases)
    }
    @Test fun multipleThemesAreUniqueAndAreNotATimeline() {
        val result = DreamDesign.draw(JSONObject().put("theme_mode", "multiple").put("theme_count", 4), Random(17))
        val themes = result.getJSONArray("themes")
        assertEquals(4, themes.length())
        assertEquals(4, (0 until themes.length()).map { themes.getString(it) }.toSet().size)
        assertTrue(result.getBoolean("not_a_sequence"))
        assertTrue(result.getBoolean("may_change_during_dream"))
    }
    @Test fun rhythmVariesWithinTheExistingCallBudgetWithoutAssigningThemesToSlots() {
        val lengths = mutableSetOf<Int>()
        val paces = mutableSetOf<String>()
        repeat(100) { seed ->
            val d = DreamDesign.draw(JSONObject().put("steps", 6).put("theme_mode", "multiple"), Random(seed))
            lengths += d.getInt("interactions")
            val pace = d.getJSONArray("pace")
            assertEquals(d.getInt("interactions"), pace.length())
            for (i in 0 until pace.length()) paces += pace.getString(i)
            assertTrue(d.getInt("interactions") in 1..6)
            assertTrue(d.getBoolean("not_a_sequence"))
        }
        assertEquals(setOf(2, 4, 6), lengths)
        assertEquals(setOf("linger", "flow", "jump"), paces)
    }
    @Test fun materialsAreBoundedShuffledAndDoNotMutateTheirOrigins() {
        val source = JSONObject().put("states", JSONArray((0..15).map { JSONObject().put("id", "state$it").put("value", it)
            .put("understanding", "当前具体的内在感受。".repeat(80)) }))
        val original = source.toString()
        val rows = (0..15).map { DreamDesign.excerpt("m$it", listOf("memory", "chat", "reading", "summary")[it % 4],
            "来源$it。这段原文可以留在原处，不要全部塞进梦境。".repeat(40), JSONObject().put("id", it)) }
        val firstKinds = mutableSetOf<String>()
        for (basis in listOf("materials", "inner_state")) repeat(20) { seed ->
            val result = DreamDesign.select(JSONObject().put("basis", basis), source, rows, 1536, Random(seed))
            assertTrue(SubjectMaterialBudget.estimate(result.toString()) < 1800)
            assertTrue(result.getJSONArray("inner_state").length() > 0)
            assertTrue(result.getJSONArray("materials").length() > 0)
            firstKinds += result.getJSONArray("materials").getJSONObject(0).getString("kind")
            val fit = DreamDesign.fit(result, 400)
            assertTrue(SubjectMaterialBudget.estimate(fit.toString()) <= 400)
        }
        assertTrue(firstKinds.size > 1)
        assertEquals(original, source.toString())
        assertEquals(600, rows.first().getString("text").length)
    }
    @Test fun plainRefusalOrWrongActorCannotBecomeACompletedDream() {
        assertThrows(DreamResponse.Invalid::class.java) { DreamResponse.parse("I cannot participate. <dream_end/>", "subject") }
        assertThrows(DreamResponse.Invalid::class.java) { DreamResponse.parse(response("environment", "continue"), "subject") }
        assertThrows(DreamResponse.Invalid::class.java) { DreamResponse.parse(response("subject", "completed"), "subject") }
        assertEquals("unavailable", DreamResponse.parse(response("subject", "unavailable"), "subject").outcome)
        assertEquals("end", DreamResponse.parse(response("subject", "end"), "subject").outcome)
    }
    @Test fun adoptedLongRecordFragmentsRemainAvailableWithoutPendingCandidates() {
        val record = JSONObject().put("record_id", "self-long").put("version", 3).put("offset", 400)
            .put("hash", "original-record-hash").put("text", "我对自己的认识，保留实际读到的原文片段。".repeat(10))
        val seed = JSONObject().put("inner_structure", JSONObject().put("record_fragments", JSONArray().put(record))
            .put("pending", JSONArray().put(JSONObject().put("id", "unadopted").put("text", "尚未采用"))))
        val rows = DreamDesign.stateRows(seed)
        assertEquals(1, rows.size)
        assertEquals("self-long", rows.single().getString("id"))
        val selected = DreamDesign.select(JSONObject().put("basis", "inner_state"), seed, emptyList(), 1536, Random(8))
            .getJSONArray("inner_state").getJSONObject(0)
        assertTrue(selected.getString("text").startsWith("我对自己的认识"))
        assertEquals(400, selected.getJSONObject("locator").getInt("offset"))
        assertTrue(selected.getBoolean("excerpt"))
    }
    private fun response(actor: String, outcome: String) = JSONObject().put("actor", actor).put("outcome", outcome).put("text", "本步内容").toString()
}
