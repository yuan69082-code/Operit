package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PrivateTimePolicyTest {
    private fun config() = JSONObject("""{"enabled":true,"start":"23:00","end":"07:00","zone":"Asia/Shanghai"}""")
    private val w get() = PrivateTimePolicy.window(config(), LocalDate.of(2026, 11, 17))
    private fun item(id: String = "review") = JSONObject().put("id", id).put("start", w.start).put("end", w.end)
        .put("activity", "review").put("goal", "回看日记与资料，整理重复索引，有所得再提炼")
        .put("targets", JSONArray(listOf("diary", "files"))).put("checks", JSONArray(listOf(w.start + 60_000, w.start + 120_000)))
    private fun plan() = PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 0)
        .put("items", JSONArray().put(item())), null, w.start - 1).put("version", 1)
    private fun progress(status: String) = JSONObject().put("expected_version", 1).put("slot_id", "review")
        .put("status", status).put("note", "日记看完，接下来查资料与整理索引")
    private fun expectFailure(part: String, block: () -> Unit) {
        try { block(); fail("应拒绝：$part") } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(part)) }
    }
    @Test fun crossMidnightUsesTheSameWindowAndZone() {
        assertEquals(8 * 3_600_000L, w.end - w.start)
        assertEquals(w, PrivateTimePolicy.upcoming(config(), w.start + 3 * 3_600_000))
        assertEquals(w, PrivateTimePolicy.upcoming(config(), w.start - 3_600_000))
    }
    @Test fun noDefaultNoForcedTaskAndEmptyPlanIsARealChoice() {
        val p = PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 0)
            .put("items", JSONArray()), null, w.start - 1)
        assertNull(PrivateTimePolicy.due(p, w.start))
    }
    @Test fun delayedStartCoalescesMissedChecksAndNeverMeansCompleted() {
        val p = plan()
        assertNull(PrivateTimePolicy.due(p, w.start - 1))
        assertNotNull(PrivateTimePolicy.due(p, w.start + 180_000))
        assertNull(PrivateTimePolicy.due(p, w.start + 180_001))
        assertEquals("planned", p.getJSONArray("items").getJSONObject(0).getString("status"))
        assertNotNull(PrivateTimePolicy.due(p, w.end))
        assertNull(PrivateTimePolicy.due(p, w.end + 1))
    }
    @Test fun chosenCheckRestartsFromCheckpointNotFromOriginalInstructions() {
        val p = plan()
        PrivateTimePolicy.due(p, w.start)
        PrivateTimePolicy.progress(p, progress("working")) { null }
        val persisted = JSONObject(p.toString())
        assertNull(PrivateTimePolicy.due(persisted, w.start + 59_999))
        assertTrue(PrivateTimePolicy.due(persisted, w.start + 60_000)!!.text.contains("核对进度"))
        assertEquals("日记看完，接下来查资料与整理索引", persisted.getJSONArray("items").getJSONObject(0).getString("progress"))
    }
    @Test fun pausedAndCancelledDoNotReappearAtChecksOrEnd() {
        for (status in listOf("paused", "cancelled")) {
            val p = plan(); PrivateTimePolicy.progress(p, progress(status)) { null }
            assertNull(PrivateTimePolicy.due(p, w.start + 120_000))
            assertNull(PrivateTimePolicy.due(p, w.end))
        }
    }
    @Test fun unfinishedEarlierSlotDoesNotBlockTheNextScheduledSlot() {
        val boundary = w.start + 3_600_000
        val p = PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 0)
            .put("items", JSONArray().put(item("first").put("end", boundary).put("checks", JSONArray()))
                .put(item("later").put("start", boundary).put("checks", JSONArray()))), null, w.start - 1)
        assertEquals("first", PrivateTimePolicy.due(p, w.start)!!.slotId)
        // The first model round failed without submitting progress. Its end reminder is
        // still a one-shot opportunity; it cannot keep consuming the later slot forever.
        assertEquals("first", PrivateTimePolicy.due(p, boundary)!!.slotId)
        val restarted = JSONObject(p.toString())
        assertEquals("later", PrivateTimePolicy.due(restarted, boundary + 1)!!.slotId)
        assertEquals("planned", restarted.getJSONArray("items").getJSONObject(0).getString("status"))
    }
    @Test fun reviewRequiresSelectedAreasAndActualOrganizationOrReasons() {
        expectFailure("targets") { PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 0)
            .put("items", JSONArray().put(item().put("targets", JSONArray()))), null, w.start - 1) }
        expectFailure("每个选中范围") { PrivateTimePolicy.progress(plan(), progress("completed")) { null } }
        val results = JSONArray(listOf("diary", "files").map { target -> JSONObject().put("target", target)
            .put("reviewed", "已逐页读完本次选择的资料").put("organization", "not_needed")
            .put("reason", "当前索引已经清楚，希望保留这段原文，不需要再简化").put("refinement", "none") })
        assertEquals("completed", PrivateTimePolicy.progress(plan(), progress("completed").put("review_results", results)) { null }
            .getJSONArray("items").getJSONObject(0).getString("status"))
    }
    @Test fun readingCannotMasqueradeAsAnOrganizationReceipt() {
        val results = JSONArray(listOf("diary", "files").map { target -> JSONObject().put("target", target)
            .put("reviewed", "已读").put("organization", "changed").put("reason", "补索引")
            .put("action_ids", JSONArray(listOf("op"))).put("refinement", "none") })
        val a = progress("completed").put("review_results", results)
        val receipt = JSONObject().put("status", "completed").put("receipt", JSONObject().put("success", true))
            .put("request", JSONObject().put("tool", "companion_study").put("parameters", JSONArray().put(JSONObject().put("name", "action").put("value", "read"))))
        expectFailure("纯读取") { PrivateTimePolicy.progress(plan(), a) { receipt } }
        receipt.getJSONObject("request").put("tool", "mcp_ob_update_note")
        assertEquals("completed", PrivateTimePolicy.progress(plan(), a) { receipt }.getJSONArray("items").getJSONObject(0).getString("status"))
    }
    @Test fun scheduleEditPreservesProgressAndCannotEraseUnfinishedWork() {
        val old = plan(); PrivateTimePolicy.progress(old, progress("working")) { null }
        val changed = PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 1)
            .put("items", JSONArray().put(item().put("checks", JSONArray()))), old, w.start)
        assertTrue(changed.getJSONArray("items").getJSONObject(0).getString("progress").contains("接下来"))
        expectFailure("cancelled") { PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 1)
            .put("items", JSONArray()), old, w.start) }
    }
    @Test fun awakeAndDreamSchedulesCannotOverlap() {
        expectFailure("重叠") { PrivateTimePolicy.plan(config(), JSONObject().put("date", w.date).put("expected_version", 0)
            .put("items", JSONArray().put(item()).put(item("sleep").put("activity", "sleep"))), null, w.start - 1) }
    }
}
