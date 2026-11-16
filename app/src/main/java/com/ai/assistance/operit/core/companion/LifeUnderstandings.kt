package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Experience and direction are interpretations in the existing subject journal, not a planner
 * or a memory database. Nothing here schedules work or equates a receipt with satisfaction. */
internal object LifeUnderstandings {
    val kinds = setOf("experience", "direction")
    fun apply(data: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long): Boolean {
        val touched = mutableSetOf<String>()
        for (i in 0 until actions.length()) {
            val a = actions.getJSONObject(i)
            require(a.keys().asSequence().all { it in setOf("kind", "id", "expected_version", "text", "scope", "uncertainty",
                "evidence", "status", "retention", "review_at", "purpose", "goal", "resolve", "ambition", "related") })
            val kind = a.getString("kind")
            require(kind in kinds)
            val id = a.getString("id")
            require(id.isNotBlank() && id.length <= 100 && touched.add("$kind:$id"))
            val old = CategoryEvents.row(data, kind, id)
            val version = old?.getInt("version") ?: 0
            require(a.getInt("expected_version") == version) { "life[$i].expected_version: current version changed" }
            if (old != null) require(frame.materialSources.values.any { raw ->
                JSONObject(raw).optJSONObject("locator")?.let { it.optString("kind") == kind &&
                    it.optString("id") == id && it.optInt("version") == version } == true
            }) { "life[$i]: read the current version before revising" }
            val refs = CategoryEvents.strings(a.getJSONArray("evidence"), 32)
            require(refs.isNotEmpty()) { "life[$i].evidence: actual supplied material required" }
            val bindings = JSONObject()
            refs.forEach { bindings.put(it, InnerStructure.source(data, frame, it)) }
            for (key in listOf("text", "scope", "uncertainty")) require(a.get(key) is String && a.getString(key).isNotBlank()) {
                "life[$i].$key: explicit content required"
            }
            require(a.getString("status") in setOf("considering", "active", "paused", "ended", "abandoned", "uncertain"))
            require(a.optString("retention", "long_term") in setOf("short_term", "long_term"))
            if (a.has("review_at")) require(a.getLong("review_at") >= now)
            for (key in listOf("purpose", "goal", "resolve", "ambition")) if (a.has(key)) require(a.get(key) is String)
            val c = JSONObject(a.toString()).apply { remove("expected_version") }
                .put("bindings", bindings).put("not_a_plan", true).put("not_a_new_occurrence", true)
                .put("subject_private_material", true)
                .put("reality", if (SubjectRestDreams.privateProvenance(bindings)) "INTERPRETATION_WITH_PRIVATE_OR_DREAM_SOURCE" else "SOURCE_BOUND_INTERPRETATION")
            CategoryEvents.append(data, frame, JSONObject().put("storage_kind", kind).put("id", id)
                .put("parent", "private").put("owner", frame.roleId).put("source_chat", frame.chatId)
                .put("version", version + 1).put("frame_id", frame.id).put("at", now)
                .put("source", "subject_report").put("content", c))
        }
        return actions.length() > 0
    }

    fun materials(data: JSONObject): List<JSONObject> = kinds.flatMap { kind ->
        val history = requireNotNull((data as InternalStateDocument).history)
        val count = history.categoryCount(kind, "private")
        val offset = if (count == 0L) 0 else (data.optLong("material_query_cursor") % ((count + 7) / 8) * 8).toInt()
        val rows = CategoryEvents.page(data, kind, offset, "private").getJSONArray("items")
        (0 until rows.length()).map { material(rows.getJSONObject(it)) }
    }
    fun material(row: JSONObject): JSONObject {
        val c = row.getJSONObject("content")
        val text = c.toString()
        return JSONObject().put("id", "material:${row.getString("storage_kind")}:${row.getString("id")}:${row.getInt("version")}")
            .put("type", "subject_" + row.getString("storage_kind")).put("owner", row.getString("owner"))
            .put("author", row.getString("owner")).put("at", row.getLong("at"))
            .put("text", text.take(1400)).put("excerpt", text.length > 1400).put("status", "retrieved")
            .put("not_new_occurrence", true).put("subject_private_material", true)
            .put("locator", JSONObject().put("kind", row.getString("storage_kind")).put("id", row.getString("id"))
                .put("version", row.getInt("version")))
    }
    val instructions = """
        [经验与自己的方向]
        已提供的experience/direction是你过去实际形成的理解，不是命令或新经历；结合本次情况决定是否使用。可用companion_internal_state get section=experience|direction按id/offset查完整原文和category_versions历史。过复查时间不删除事实，也不自动安排活动。
        轮末life:[{kind:"experience|direction",id,expected_version,text,scope,uncertainty,evidence:[],status:"considering|active|paused|ended|abandoned|uncertain",retention:"short_term|long_term",review_at?:毫秒,purpose?:为什么,goal?:希望结果,resolve?:投入与限度,ambition?:长远方向,related?:关联ID数组}]。
        引用本轮实际读到的来源。新理解保留完整描述、适用范围和不确定性；修改先读现版本。目的、目标、决心和野心可以分别改变，不压成驱力总分。当前情绪、人格、关系、价值、偏好和实际反馈可以支持或牵制方向；结果不预定。复查或提炼出的想法不等于已经安排，真正预约仍用原工具。睡觉不必整理，可在自己安排的安静活动里整理增量材料、重复问题或矛盾，分清事实错误与后来改变的认识。梦和模拟只说明梦中体验，不能转成现实事实。稳定人格等仍走inner跨轮采用，life不能改写它们。
    """.trimIndent()
}
