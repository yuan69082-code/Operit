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
            try {
            val submitted = actions.getJSONObject(i)
            val old = CategoryEvents.row(data, submitted.getString("kind"), submitted.getString("id"))
            val a = SubjectConcerns.prepare(old, submitted, frame)
            SubjectContracts.fields(a, setOf("kind", "id", "expected_version", "text", "scope", "uncertainty",
                "evidence", "status", "retention", "review_at", "purpose", "goal", "resolve", "ambition", "related", "attention", "attention_change"))
            val kind = SubjectContracts.choice(a, "kind", kinds)
            val id = SubjectContracts.text(a, "id", 100)
            SubjectContracts.expect(touched.add("$kind:$id"), "id", "DUPLICATE_WRITE", "同一报告不能重复写入同一条记录。")
            val version = old?.getInt("version") ?: 0
            SubjectContracts.expect(SubjectContracts.integer(a, "expected_version") == version, "expected_version", "VERSION_CHANGED", "当前版本已改变；先读取现版本，再决定是否修订。")
            if (old != null) SubjectContracts.expect(frame.materialSources.values.any { raw ->
                JSONObject(raw).optJSONObject("locator")?.let { it.optString("kind") == kind &&
                    it.optString("id") == id && it.optInt("version") == version } == true
            }, "expected_version", "MEMBER_NOT_DELIVERED", "修订前须读取该记录的当前版本。")
            val refs = SubjectContracts.strings(SubjectContracts.array(a, "evidence"), path = "evidence")
            SubjectContracts.expect(refs.isNotEmpty(), "evidence", "EVIDENCE_REQUIRED", "需要至少一个实际提供的来源ID。")
            val bindings = JSONObject()
            refs.forEach { bindings.put(it, InnerStructure.source(data, frame, it)) }
            if (submitted.has("related")) SubjectActions.validateDeclaration(JSONObject()
                .put("intention_refs", a.getJSONArray("related")).put("evidence", JSONArray()), frame, prefix = "related_declaration")
            for (key in listOf("text", "scope", "uncertainty")) SubjectContracts.text(a, key)
            SubjectContracts.choice(a, "status", setOf("considering", "active", "paused", "ended", "abandoned", "uncertain"))
            if (a.has("retention")) SubjectContracts.choice(a, "retention", setOf("short_term", "long_term"))
            if (a.has("review_at")) {
                val raw = a.opt("review_at")
                SubjectContracts.expect(raw is Number && raw.toDouble().isFinite() &&
                    raw.toDouble() == raw.toLong().toDouble() && raw.toLong() >= now,
                    "review_at", "REVIEW_TIME", "需要不早于提交时间的整数毫秒数字；可省略，不会自动预约。")
            }
            for (key in listOf("purpose", "goal", "resolve", "ambition"))
                if (a.has(key)) SubjectContracts.text(a, key, empty = true)
            val c = JSONObject(a.toString()).apply { remove("expected_version") }
                .put("bindings", bindings).put("not_a_plan", true).put("not_a_new_occurrence", true)
                .put("subject_private_material", true)
                .put("reality", if (SubjectRestDreams.privateProvenance(bindings)) "INTERPRETATION_WITH_PRIVATE_OR_DREAM_SOURCE" else "SOURCE_BOUND_INTERPRETATION")
            if (c.has("attention")) c.put("original_source", if (old != null && old.getJSONObject("content").optString("text") == c.getString("text"))
                old.getJSONObject("content").optString("original_source", old.getString("source")) else "subject_report")
            val row = JSONObject().put("storage_kind", kind).put("id", id)
                .put("parent", "private").put("owner", frame.roleId).put("source_chat", frame.chatId)
                .put("version", version + 1).put("frame_id", frame.id).put("at", now)
                .put("source", "subject_report").put("content", c)
            CategoryEvents.append(data, frame, row)
            SubjectConcerns.index(data, frame, row)
            } catch (failure: Exception) {
                throw InternalStateReportDiagnostics.issue(failure, "life[$i]")
            }
        }
        return actions.length() > 0
    }

    fun materials(data: JSONObject, input: String = "", chat: String = ""): List<JSONObject> = kinds.flatMap { kind ->
        SubjectRecall.selected(data, kind, input, chat, data.optLong("material_query_cursor") * 8)
            .filterNot { it.getJSONObject("content").has("attention") }.map { material(it) }
    }
    fun material(row: JSONObject, detail: Boolean = false): JSONObject {
        val c = row.getJSONObject("content")
        if (c.has("attention")) return SubjectConcerns.material(row, detail)
        // Original text and source snapshots stay in the journal. Spend the request's
        // allowance on the interpretation, not on nested copies of old evidence.
        val text = listOf("text", "scope", "uncertainty", "status", "retention", "review_at",
            "purpose", "goal", "resolve", "ambition", "related", "reality")
            .filter(c::has).joinToString("\n") { "$it: ${c.get(it)}" }
        return JSONObject().put("id", "material:${row.getString("storage_kind")}:${row.getString("id")}:${row.getInt("version")}")
            .put("type", "subject_" + row.getString("storage_kind")).put("owner", row.getString("owner"))
            .put("author", row.getString("owner")).put("at", row.getLong("at"))
            .put("text", text.take(1400)).put("excerpt", text.length > 1400).put("status", "retrieved")
            .put("not_new_occurrence", true).put("subject_private_material", true)
            .put("selection_reason", row.optString("selection_reason", "ROTATION"))
            .put("full_text_tool", "companion_internal_state get section=${row.getString("storage_kind")} id=${row.getString("id")}")
            .put("locator", JSONObject().put("kind", row.getString("storage_kind")).put("id", row.getString("id"))
                .put("version", row.getInt("version")))
    }
    val instructions = """
        [经验与自己的方向]
        已提供的experience/direction是你过去实际形成的理解，不是命令或新经历；结合本次情况决定是否使用。可用companion_internal_state get section=experience|direction按id/offset查完整原文和category_versions历史。过复查时间不删除事实，也不自动安排活动。
        轮末life:[{kind:"experience|direction",id,expected_version,text,scope,uncertainty,evidence:[],status:"considering|active|paused|ended|abandoned|uncertain",retention:"short_term|long_term",review_at?:毫秒,purpose?:为什么,goal?:希望结果,resolve?:投入与限度,ambition?:长远方向,related?:[{kind,id,version}]}]。related必须是实际读到的版本。direction承接长期目的与投入，decision承接某次具体选择，两者用intention_refs关联，不复制为另一套任务。已提供的experience/direction也可作为联合成员、决定或实际操作的intention_refs；同一报告中的合法变化整体采用，不能拿未读的经验冒充依据。
        引用本轮实际读到的来源。新理解保留完整描述、适用范围和不确定性；修改先读现版本。目的、目标、决心和野心可以分别改变，不压成驱力总分。当前情绪、人格、关系、价值、偏好和实际反馈可以支持或牵制方向；结果不预定。复查或提炼出的想法不等于已经安排，真正预约仍用原工具。睡觉不必整理，可在自己安排的安静活动里整理增量材料、重复问题或矛盾，分清事实错误与后来改变的认识。梦和模拟只说明梦中体验，不能转成现实事实。稳定人格等仍走inner跨轮采用，life不能改写它们。
    """.trimIndent()
}
