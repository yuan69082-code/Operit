package com.ai.assistance.operit.core.tasks

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResearchTaskReceiptTest {
    @Test fun laterSavedTaskAndItsActualScheduleRemainIdentifiableWithoutHistoryInjection() {
        val rows = JSONArray()
        repeat(25) { TaskRecords.save(rows, "owner", null, JSONObject().put("title", "既有事项$it"), setOf("owner"), 1) }
        val saved = TaskRecords.save(rows, "owner", null, JSONObject().put("title", "星空研究")
            .put("origin", "实际聊天来源").put("goal", "理解误差").put("next", "比较观测"), setOf("owner"), 2)
        val id = saved.getString("id")
        assertFalse((0 until 20).any { rows.getJSONObject(it).getString("id") == id })
        assertEquals(id, TaskRecords.receipt(saved).getString("id"))
        assertFalse(TaskRecords.receipt(saved).has("dispatch"))
        TaskRecords.submit(saved, "owner", "owner", 15, 3)
        val receipt = TaskRecords.receipt(saved)
        assertEquals(900003L, receipt.getJSONObject("dispatch").getLong("at"))
        assertEquals("pending", receipt.getJSONObject("dispatch").getString("state"))
        repeat(70) { TaskRecords.comment(saved, "owner", "阶段记录".repeat(500), 4 + it.toLong()) }
        val material = TaskRecords.material(saved)
        assertTrue(material.getBoolean("material_excerpt"))
        assertTrue(material.getString("locator").contains(id))
        assertFalse(material.has("history"))
        assertTrue(material.toString().length < 1500)
        assertTrue(TaskRecords.detail(saved, 0).getInt("history_total") >= 70)
        assertEquals(2000, TaskRecords.detail(saved, 0).getJSONArray("history").getJSONObject(0).getString("text").length)
    }

    @Test fun savingAnIdeaDoesNotScheduleItAndPausingCancelsOnlyItsOwnPendingDispatch() {
        val rows = JSONArray()
        val saved = TaskRecords.save(rows, "owner", null, JSONObject().put("title", "可以研究的想法"), setOf("owner"), 1)
        assertFalse(saved.has("dispatch"))
        TaskRecords.submit(saved, "owner", "owner", 10, 2)
        val paused = TaskRecords.save(rows, "owner", saved.getString("id"), JSONObject().put("status", "paused"), setOf("owner"), 3)
        assertEquals("cancelled", paused.getJSONObject("dispatch").getString("state"))
        assertFalse(TaskRecords.claim(paused, paused.getJSONObject("dispatch").getString("id"), 9999999))
    }
}
