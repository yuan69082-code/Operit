package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** Small retrieval cards and subject-chosen filing, on the existing dream archive. */
internal object DreamShelf {
    val reviewFields = setOf("retention", "retain_until", "review_at", "keywords")
    fun archived(row: JSONObject, now: Long): Boolean {
        val review = row.optJSONObject("dream_review") ?: return false
        return review.optString("retention") == "archive" ||
            (review.optLong("retain_until") > 0 && now >= review.getLong("retain_until"))
    }
    fun review(original: JSONObject, change: JSONObject, now: Long): JSONObject? {
        if (reviewFields.none(change::has)) return null
        val r = original.optJSONObject("dream_review")?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (change.has("retention")) r.put("retention", SubjectContracts.choice(change, "retention", setOf("active", "archive")))
        if (change.optString("retention") == "active" && !change.has("retain_until")) r.remove("retain_until")
        for (field in listOf("retain_until", "review_at")) if (change.has(field)) {
            val raw = change.opt(field)
            SubjectContracts.expect(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble() &&
                (raw.toLong() == 0L || raw.toLong() >= now), field, "REVIEW_TIME", "使用未来的整数毫秒时间，或0清除；不会删除原梦或自动唤醒。")
            r.put(field, raw)
        }
        if (change.has("keywords")) {
            SubjectContracts.strings(SubjectContracts.array(change, "keywords"), path = "keywords")
            r.put("keywords", change.getJSONArray("keywords"))
        }
        return r.put("title", change.getString("meaning").take(80)).put("reviewed_at", now)
            .put("understanding_id", change.getString("id"))
    }
    fun index(row: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        val kind = row.getString("storage_kind")
        val review = row.optJSONObject("dream_review")
        val title = review?.optString("title")?.takeIf { it.isNotBlank() }
            ?: if (kind == "dream") row.getJSONObject("content").getString("title") else row.optString("index_text", "参与式梦")
        val keywords = review?.optJSONArray("keywords") ?: org.json.JSONArray()
        val card = JSONObject().put("date", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(row.getLong("at"))))
            .put("title", title.take(120)).put("keywords", org.json.JSONArray((0 until minOf(6, keywords.length())).map { keywords.getString(it) }))
            .put("keyword_count", keywords.length())
            .put("archived", archived(row, now)).put("status", row.optString("status", "saved"))
            .put("review_due", review?.optLong("review_at")?.let { it > 0 && now >= it } == true)
            .put("notice", "这是梦的索引，不是梦境全文或新经历；需要时按ID查阅。归档不清除余韵和已采用认识。")
        return JSONObject().put("id", "material:dream_index:$kind:${row.getString("id")}:${row.getInt("version")}")
            .put("type", "dream_index").put("owner", row.getString("owner")).put("author", row.getString("owner"))
            .put("at", row.getLong("at")).put("text", card.toString()).put("status", "retrieved")
            .put("not_new_occurrence", true).put("subject_private_material", true)
            .put("locator", JSONObject().put("kind", kind).put("id", row.getString("id")).put("version", row.getInt("version")))
            .put("retrieval", "companion_internal_state get section=$kind id=${row.getString("id")}" +
                if (kind == "dream_process") "; get section=dream_step id=${row.getString("id")} offset=0" else "")
    }
}
