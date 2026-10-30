package com.ai.assistance.operit.ui.features.settings.screens

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateTimelineTest {
    private fun state(id: String = "longing", scope: String = "global") = JSONObject()
        .put("id", id).put("instance_scope", scope).put("name", "思念")

    private fun change(id: String, scope: String = "global", before: Double = 20.0, after: Double = 22.0) = JSONObject()
        .put("dimension", id).put("scope", scope).put("version", 1).put("before", before).put("after", after)
        .put("continuous_from_previous", false)

    private fun event(id: String, source: String, at: Long, vararg changes: JSONObject) = JSONObject()
        .put("id", id).put("source", source).put("at", at).put("changes", JSONArray(changes.toList()))

    private fun entry(key: String, at: Long, before: Double, after: Double, source: String = "state_report",
                      continuous: Boolean = false, from: Long? = null) = StateTimelineEntry(
        key, key, at, source, before, after, 1, continuous, from, emptyList(), null)

    @Test fun timelineFiltersOtherDimensionsAndOtherSessionInstances() {
        val observed = JSONObject().put("events", JSONArray().put(event("one", "reading", 20,
            change("longing", "chat-a"), change("longing", "chat-b"), change("anger", "chat-a"))))
        val result = stateTimeline(observed, state(scope = "chat-a"))
        assertEquals(1, result.size)
        assertEquals("one:0", result.single().key)
    }

    @Test fun renamedStateKeepsItsOriginalTimelineAndOwnExperience() {
        val observed = JSONObject().put("events", JSONArray().put(event("state_report:frame-a", "state_report", 30,
            change("longing"), change("anger"))))
            .put("experiences", JSONArray()
                .put(JSONObject().put("dimension", "longing").put("scope", "global").put("frame_id", "frame-a").put("reason", "想念的理由"))
                .put(JSONObject().put("dimension", "anger").put("scope", "global").put("frame_id", "frame-a").put("reason", "另一个状态的理由")))
        val result = stateTimeline(observed, state().put("name", "牵挂"))
        assertEquals("想念的理由", result.single().experience!!.getString("reason"))
    }

    @Test fun manualReasonChangeUsesItsUserEventWithoutRequiringAModelFrame() {
        val observed = JSONObject().put("events", JSONArray().put(event("user_edit:one", "user_edit", 35,
            change("longing", before = 20.0, after = 20.0))))
            .put("experiences", JSONArray().put(JSONObject().put("dimension", "longing").put("scope", "global")
                .put("source", "user_edit").put("event_id", "user_edit:one").put("reason", "园园修改了原因")))
        val result = stateTimeline(observed, state()).single()
        assertEquals("user_edit", result.source)
        assertEquals("园园修改了原因", result.experience!!.getString("reason"))
        assertEquals(20.0, result.before, 0.0)
        assertEquals(20.0, result.after, 0.0)
    }

    @Test fun splitTimeChangesKeepTheirOwnBoundaryAndDistinctMarker() {
        val first = change("longing").put("interval_start", 10).put("interval_end", 20).put("time_reasons", JSONArray().put("未联系"))
        val second = change("longing", before = 22.0, after = 21.0).put("interval_start", 20).put("interval_end", 30)
            .put("continuous_from_previous", true)
        val observed = JSONObject().put("events", JSONArray().put(event("time:1", "time", 30, first, second)))
        val result = stateTimeline(observed, state())
        assertEquals(listOf(20L, 30L), result.map { it.at })
        assertEquals(listOf("time:1:0", "time:1:1"), result.map { it.key })
        assertEquals(listOf("未联系"), result.first().timeReasons)
        assertEquals(10L, result.first().intervalStart!!)
    }

    @Test fun hiddenHistoryNeverGetsAConnectingSegmentEvenIfValuesMatch() {
        val first = entry("one", 10, 20.0, 25.0)
        val second = entry("two", 30, 25.0, 30.0, continuous = false)
        val chart = stateChartGeometry(listOf(first, second), 30.0, 30)
        assertEquals(2, chart.segments.size)
        assertTrue(chart.segments.all { it.fromAt == it.toAt })
        assertFalse(chart.segments.any { it.fromAt == 10L && it.toAt == 30L })
    }

    @Test fun eventJumpDoesNotSmearAcrossTimeWhileRealTimeIntervalDoes() {
        val first = entry("one", 10, 20.0, 25.0)
        val timed = entry("two", 30, 25.0, 30.0, "time", continuous = true, from = 20)
        val next = entry("three", 40, 30.0, 12.0, continuous = true)
        val chart = stateChartGeometry(listOf(first, timed, next), 12.0, 40)
        assertTrue(chart.segments.contains(StateChartSegment(10, 25.0, 20, 25.0)))
        assertTrue(chart.segments.contains(StateChartSegment(20, 25.0, 30, 30.0)))
        assertTrue(chart.segments.contains(StateChartSegment(40, 30.0, 40, 12.0)))
        assertFalse(chart.segments.contains(StateChartSegment(30, 30.0, 40, 12.0)))
    }

    @Test fun legacyTimeSamplesConnectOnlyAcrossVerifiedVisibleContinuity() {
        val first = entry("one", 10, 20.0, 25.0)
        val timed = entry("two", 30, 25.0, 26.0, "time", continuous = true)
        val chart = stateChartGeometry(listOf(first, timed), 26.0, 30)
        assertTrue(chart.segments.contains(StateChartSegment(10, 25.0, 30, 26.0)))
        assertFalse(stateChartGeometry(listOf(first, timed.copy(continuousFromPrevious = false)), 26.0, 30)
            .segments.any { it.fromAt == 10L && it.toAt == 30L })
    }

    @Test fun smallRealChangesGetUsefulScaleWithoutInventingWaves() {
        val chart = stateChartGeometry(listOf(entry("one", 10, 30.0, 30.1)), 30.1, 10)
        assertTrue(chart.low < 30.0 && chart.high > 30.1)
        assertTrue(chart.high - chart.low < 0.2)
        assertEquals(1, chart.segments.size)
        val flat = stateChartGeometry(emptyList(), 0.0, 10)
        assertTrue(flat.low < flat.high)
        assertTrue(flat.segments.isEmpty())
    }
}
