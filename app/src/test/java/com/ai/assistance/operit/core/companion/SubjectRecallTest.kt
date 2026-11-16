package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** In-memory archive port for reducer/selection tests; not Android storage or model evidence. */
internal class RecallTestHistory : InternalStateHistoryAccess {
    val rows = linkedMapOf<Pair<String, String>, JSONObject>()
    override fun definition(id: String, version: Int): JSONObject? = null
    override fun latestContact(chat: String?, at: Long): Long? = null
    override fun contacts(chat: String?, after: Long, until: Long): Sequence<Long> = emptySequence()
    override fun categoryRow(kind: String, id: String) = rows[kind to id]?.let { JSONObject(it.toString()) }
    override fun categoryPage(kind: String, offset: Int, limit: Int, parent: String?) = rows.values
        .filter { it.getString("storage_kind") == kind && (parent == null || it.getString("parent") == parent) }
        .sortedBy { it.getString("id") }.drop(offset).take(limit).map { JSONObject(it.toString()) }
    override fun categoryCount(kind: String, parent: String?) = rows.values.count {
        it.getString("storage_kind") == kind && (parent == null || it.getString("parent") == parent)
    }.toLong()
    override fun relevantSubjectRows(kind: String, input: String, chat: String, limit: Int) = SubjectRecall.ranked(
        rows.values.asSequence().filter { it.getString("storage_kind") == kind }.mapNotNull(SubjectRecall::entry),
        input, chat, limit).mapNotNull { categoryRow(kind, it) }
    fun commit(data: JSONObject) {
        val journal = data.optJSONObject("category_commit")?.getJSONArray("rows") ?: return
        for (i in 0 until journal.length()) {
            val row = journal.getJSONObject(i)
            rows[row.getString("storage_kind") to row.getString("id")] = JSONObject(row.toString())
        }
    }
}

class SubjectRecallTest {
    private fun row(id: String, kind: String = "feedback", meaning: String = "旧方法") = JSONObject()
        .put("storage_kind", kind).put("id", id).put("version", 1).put("at", 1).put("owner", "owner")
        .put("parent", if (kind == "union") "owner" else "private")
        .put("content", JSONObject().put("meaning", meaning).put("status", "active"))

    @Test fun offPageFeedbackIsRetrievedAndUnrelatedRowsStillRotate() {
        val history = RecallTestHistory()
        repeat(80) { i -> history.rows["feedback" to "r%03d".format(i)] = row("r%03d".format(i)) }
        history.rows["feedback" to "r079"] = row("r079", meaning = "星空观测需要校准镜头")
        val data = InternalStateDocument(JSONObject(), history)
        val result = ActionUnderstanding.materials(data, "继续星空观测", "chat")
        assertEquals(8, result.size)
        assertEquals("r079", result.first().getJSONObject("locator").getString("id"))
        assertEquals("CURRENT_INPUT_RELEVANCE", result.first().getString("selection_reason"))
        data.put("material_query_cursor", 24)
        assertTrue(ActionUnderstanding.materials(data).any { it.getJSONObject("locator").getString("id") == "r024" })
        assertEquals(80, history.rows.size)
    }

    @Test fun matchingDoesNotTrustEvidenceAndRespectsLocalityAndEndedUnions() {
        val actual = row("actual", "union", "星空观测与独处的拉扯")
        val evidenceOnly = row("evidence", "union", "做饭").apply {
            getJSONObject("content").put("source_bindings", JSONObject().put("old", "星空观测"))
        }
        val local = row("local", "union", "星空观测").put("local_chat", "other-chat")
        val ended = row("ended", "union", "星空观测").apply { getJSONObject("content").put("status", "ended") }
        assertEquals(listOf("actual"), SubjectRecall.ranked(sequenceOf(actual, evidenceOnly, local, ended)
            .mapNotNull(SubjectRecall::entry), "星空观测", "chat", 8))
    }

    @Test fun manyRecordsDoNotExpandDeliveredMaterialOrRewriteOriginals() {
        val history = RecallTestHistory()
        repeat(5000) { i -> history.rows["feedback" to "r%04d".format(i)] = row("r%04d".format(i), meaning = "无关经验") }
        val target = row("z-target", meaning = "星空需要重新校准，不应沿用上次的误差")
        history.rows["feedback" to "z-target"] = target
        val original = target.toString()
        val data = InternalStateDocument(JSONObject(), history)
        val source = JSONObject().put("inner_structure", JSONObject())
            .put("category_events", JSONObject()).put("unions", JSONObject().put("items", JSONArray()))
            .put("retrieved_materials", JSONArray(ActionUnderstanding.materials(data, "星空校准", "chat")))
        val selected = SubjectMaterialBudget.select(source)
        assertTrue(selected.getJSONArray("retrieved_materials").toString().contains("z-target"))
        assertTrue(SubjectMaterialBudget.estimate(selected.toString()) + SubjectMaterialBudget.ENVELOPE_RESERVE <= 2048)
        assertTrue(selected.getJSONArray("retrieved_materials").length() <= 8)
        assertEquals(original, target.toString())
        assertEquals(5001, history.rows.size)
    }
}
