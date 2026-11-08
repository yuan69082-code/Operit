package com.ai.assistance.operit.core.companion

import java.io.StringReader
import java.io.StringWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateHistoryTest {
    @Test fun legacyHistoryIsStreamedWithoutLosingCurrentStateOrOldRows() {
        val input = JSONObject().put("sequence", 6500).put("semantic_revision", 41)
            .put("instances", JSONArray().put(JSONObject().put("value", 93.5).put("reason", "保留当前体验")))
            .put("definitions", JSONArray().put(JSONObject().put("id", "attachment")))
            .put("events", JSONArray((1..6500).map { JSONObject().put("id", "event:$it").put("sequence", it) }))
        val archived = mutableListOf<Int>()
        val result = InternalStateHistory.read(StringReader(input.toString())) { kind, row ->
            assertEquals("events", kind)
            archived.add(row.getInt("sequence"))
        }
        assertEquals((1..3500).toList(), archived)
        assertEquals(3000, result.getJSONArray("events").length())
        assertEquals(3501, result.getJSONArray("events").getJSONObject(0).getInt("sequence"))
        assertEquals(41, result.getInt("semantic_revision"))
        assertEquals(input.getJSONArray("instances").toString(), result.getJSONArray("instances").toString())
        assertEquals(input.getJSONArray("definitions").toString(), result.getJSONArray("definitions").toString())
    }

    @Test fun byteBudgetBoundsLargeRowsEvenBeforeCountLimit() {
        val input = JSONObject().put("experiences", JSONArray((1..100).map {
            JSONObject().put("id", it).put("reason", "字".repeat(40000))
        }))
        val archived = mutableListOf<JSONObject>()
        val result = InternalStateHistory.read(StringReader(input.toString())) { _, row -> archived.add(row) }
        val retained = result.getJSONArray("experiences")
        assertTrue(retained.length() < 100)
        assertEquals(100, archived.size + retained.length())
        assertEquals(100, retained.getJSONObject(retained.length() - 1).getInt("id"))
    }

    @Test fun archiveFailureAbortsCompactionBeforeRemovingRows() {
        val data = JSONObject().put("semantic_reports", JSONArray((1..301).map { JSONObject().put("frame_id", "$it") }))
        assertThrows(java.io.IOException::class.java) {
            InternalStateHistory.compact(data) { _, _ -> throw java.io.IOException("disk full") }
        }
        assertEquals(301, data.getJSONArray("semantic_reports").length())
    }

    @Test fun streamingWriterPreservesNumbersEscapesAndNulls() {
        val source = JSONObject().put("time", 1794106447815L).put("value", -1.25)
            .put("text", "园园\n\"你好\"\\").put("null", JSONObject.NULL)
            .put("items", JSONArray().put(true).put(JSONObject().put("name", "状态")))
        val writer = StringWriter()
        InternalStateHistory.write(source, writer)
        val copy = JSONObject(writer.toString())
        assertEquals(source.getLong("time"), copy.getLong("time"))
        assertEquals(-1.25, copy.getDouble("value"), 0.0)
        assertEquals(source.getString("text"), copy.getString("text"))
        assertTrue(copy.isNull("null"))
        assertEquals(source.getJSONArray("items").toString(), copy.getJSONArray("items").toString())
    }
}
