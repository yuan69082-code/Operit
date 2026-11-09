package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** A user-opened submission window, not an adoption timer. Stored with the assistant's
 * authoritative metadata so model/window changes and normal backup restore cannot reset it. */
internal object EstablishedContent {
    const val ORIGIN = "established_import"
    const val DURATION = 48L * 60 * 60 * 1000
    fun open(data: JSONObject, owner: String, now: Long): JSONObject {
        val root = InnerStructure.root(data)
        root.optJSONObject("established_window")?.let { return JSONObject(it.toString()) }
        return JSONObject().put("owner", owner).put("opened_at", now).put("closes_at", Math.addExact(now, DURATION))
            .put("opened_by", "user").put("submitted", 0L).put("last_observed_at", now)
            .also { root.put("established_window", it) }
    }
    fun status(data: JSONObject, now: Long): JSONObject {
        val w = InnerStructure.root(data).optJSONObject("established_window")
            ?: return JSONObject().put("status", "not_opened")
        val at = maxOf(now, w.getLong("last_observed_at"))
        return JSONObject(w.toString()).put("status", if (at < w.getLong("closes_at")) "open" else "closed")
            .put("remaining_ms", maxOf(0L, w.getLong("closes_at") - at))
    }
    fun declaration(data: JSONObject, owner: String, candidate: String, family: String, now: Long, formedAt: Long?): JSONObject {
        val w = requireNotNull(InnerStructure.root(data).optJSONObject("established_window")) { "请先由园园开启该助手的48小时已有内容承接窗口" }
        require(w.getString("owner") == owner && maxOf(now, w.getLong("last_observed_at")) < w.getLong("closes_at")) { "已有内容提交窗口已截止；此前候选仍可在后续正常轮次采用" }
        if (formedAt != null) require(formedAt <= now && formedAt >= 0) { "历史形成时间不能是未来" }
        w.put("last_observed_at", maxOf(now, w.getLong("last_observed_at")))
        w.put("submitted", Math.addExact(w.getLong("submitted"), 1L))
        return JSONObject().put("source", ORIGIN).put("label", "已有确定内容导入/写入")
            .put("family", family).put("owner", owner).put("candidate_id", candidate).put("submitted_at", now)
            .put("formed_at", formedAt ?: JSONObject.NULL).put("original_evidence_verified", false)
    }
}
