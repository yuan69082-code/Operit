package com.ai.assistance.operit.core.tasks

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskRecordsTest {
    private val members = setOf("A", "B")
    private fun create(rows: JSONArray = JSONArray()) = TaskRecords.save(rows, "A", null,
        JSONObject().put("title", "我想读完这本书").put("assignee", "A").put("horizon", "long"), members, 1000)
    @Test fun personalIntentionDoesNotRequireUserApproval() {
        val row = create()
        TaskRecords.comment(row, "A", "这件事对我很重要", 2000)
        TaskRecords.submit(row, "A", "A", 0, 3000)
        assertEquals("A", row.getString("author"))
        assertEquals("", row.optString("needs_decision"))
        assertEquals("A", row.getJSONArray("history").getJSONObject(1).getString("actor"))
        assertEquals("pending", row.getJSONObject("dispatch").getString("state"))
    }
    @Test fun handingOffKeepsAuthorAndDoesNotClaimCompletion() {
        val rows = JSONArray()
        val row = create(rows)
        val next = TaskRecords.save(rows, "B", row.getString("id"), JSONObject().put("assignee", "B")
            .put("completed", "读了前三章").put("next", "讨论第四章"), members, 2000)
        assertEquals("A", next.getString("author"))
        assertEquals("B", next.getString("updated_by"))
        assertEquals("open", next.getString("status"))
    }
    @Test fun oldRecordsKeepContentAndUnknownAuthor() {
        val old = JSONObject().put("id", "old").put("title", "旧计划").put("detail", "保留内容")
            .put("urgency", "urgent").put("status", "open").put("horizon", "long")
        val rows = JSONArray().put(old)
        val updated = TaskRecords.save(rows, "A", "old", JSONObject().put("next", "重新看看"), members, 2000)
        assertEquals("保留内容", updated.getString("detail"))
        assertEquals("unknown", updated.getString("author"))
        assertEquals("P1", updated.getString("priority"))
    }
    @Test fun boardFieldsSaveAndBlockerMeansStuck() {
        val rows = JSONArray()
        val row = create(rows)
        assertEquals("todo", TaskRecords.tab(row))
        val doing = TaskRecords.save(rows, "A", row.getString("id"), JSONObject().put("status", "doing")
            .put("origin", "她 18:56 说：细节和原文必须存在").put("goal", "一眼看出卡在哪")
            .put("plan", "之后接群待办").put("materials", "/sdcard/a.png 小人设定图\nob:490b87cf7db8 今晚约定"), members, 2000)
        assertEquals("doing", TaskRecords.tab(doing))
        assertEquals("她 18:56 说：细节和原文必须存在", doing.getString("origin"))
        val stuck = TaskRecords.save(rows, "A", row.getString("id"), JSONObject().put("blocker", "等画图额度"), members, 3000)
        assertEquals("blocked", TaskRecords.tab(stuck))
        assertEquals("done", TaskRecords.tab(TaskRecords.save(rows, "A", row.getString("id"),
            JSONObject().put("status", "done").put("blocker", ""), members, 4000)))
    }
    @Test(expected = IllegalStateException::class) fun staleEditorCannotOverwriteNewNotes() {
        val rows = JSONArray()
        val row = create(rows)
        TaskRecords.comment(row, "B", "我补充了想法", 2000)
        TaskRecords.save(rows, "user", row.getString("id"), JSONObject().put("expected_version", 1).put("title", "过期修改"), members, 3000)
    }
    @Test(expected = IllegalArgumentException::class) fun cannotRewriteOriginalAuthor() {
        val rows = JSONArray(); val row = create(rows)
        TaskRecords.save(rows, "B", row.getString("id"), JSONObject().put("author", "B"), members, 2000)
    }
    @Test(expected = IllegalArgumentException::class) fun cannotAssignOutsidePrivateScope() {
        val rows = JSONArray(); val row = create(rows)
        TaskRecords.save(rows, "A", row.getString("id"), JSONObject().put("assignee", "B"), setOf("A"), 2000)
    }
    @Test(expected = IllegalArgumentException::class) fun linksCannotReferenceAnotherPrivateBoard() {
        val rows = JSONArray(); val row = create(rows)
        TaskRecords.save(rows, "A", row.getString("id"), JSONObject().put("linked_ids", JSONArray().put("other-private-task")), members, 2000)
    }
    @Test(expected = IllegalStateException::class) fun duplicateSubmissionDoesNotScheduleAgain() {
        val row = create()
        TaskRecords.submit(row, "A", "A", 1, 2000)
        TaskRecords.submit(row, "A", "A", 1, 2001)
    }
    @Test fun executingAssistantCanPlanLaterFollowup() {
        val row = create()
        TaskRecords.submit(row, "A", "A", 0, 2000)
        row.getJSONObject("dispatch").put("state", "running")
        TaskRecords.submit(row, "A", "B", 5, 3000)
        assertEquals(303000, row.getJSONObject("dispatch").getLong("followup_at"))
        assertEquals("B", row.getJSONObject("dispatch").getString("followup_recipient"))
    }
    @Test(expected = IllegalStateException::class) fun completedTaskMustBeReopenedBeforeExecuting() {
        val row = create().put("status", "done")
        TaskRecords.submit(row, "A", "A", 0, 2000)
    }

    @Test fun inactiveStatesCancelUnclaimedWork() {
        for (status in TaskRecords.inactiveStatuses) {
            val rows = JSONArray(); val row = create(rows)
            TaskRecords.submit(row, "A", "A", 0, 2000)
            val event = row.getJSONObject("dispatch").getString("id")
            val paused = TaskRecords.save(rows, "A", row.getString("id"), JSONObject().put("status", status), members, 3000)
            assertEquals("cancelled", paused.getJSONObject("dispatch").getString("state"))
            assertFalse(TaskRecords.claim(paused, event, 4000))
        }
    }
    @Test fun closingRunningTaskDropsFutureFollowup() {
        val rows = JSONArray(); val row = create(rows)
        TaskRecords.submit(row, "A", "A", 0, 2000)
        val event = row.getJSONObject("dispatch").getString("id")
        assertTrue(TaskRecords.claim(row, event, 3000))
        TaskRecords.submit(row, "A", "A", 5, 4000)
        val ended = TaskRecords.save(rows, "A", row.getString("id"), JSONObject().put("status", "done"), members, 5000)
        assertFalse(ended.getJSONObject("dispatch").has("followup_at"))
    }
    @Test fun anEventCanOnlyBeClaimedOnceAfterItsDueTime() {
        val row = create()
        TaskRecords.submit(row, "A", "A", 1, 2000)
        val event = row.getJSONObject("dispatch").getString("id")
        assertFalse(TaskRecords.claim(row, "wrong-event", 62000))
        assertFalse(TaskRecords.claim(row, event, 61999))
        assertTrue(TaskRecords.claim(row, event, 62000))
        assertFalse(TaskRecords.claim(row, event, 62001))
    }
    @Test fun listDoesNotRepeatDetailsOrFullHistory() {
        val row = create().put("detail", "full original record").put("needs_decision", "a choice")
        repeat(12) { TaskRecords.comment(row, "A", "note $it", 2000L + it) }
        val brief = TaskRecords.brief(row)
        assertFalse(brief.has("detail")); assertFalse(brief.has("history")); assertTrue(brief.getBoolean("has_decision"))
        val first = TaskRecords.detail(row, 0)
        val second = TaskRecords.detail(row, 5)
        assertEquals("full original record", first.getString("detail"))
        assertEquals(5, first.getJSONArray("history").length())
        assertEquals("note 11", first.getJSONArray("history").getJSONObject(0).getString("text"))
        assertEquals("note 6", second.getJSONArray("history").getJSONObject(0).getString("text"))
    }
}
