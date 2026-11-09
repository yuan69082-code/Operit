package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Subject interpretation is adopted only in the original atomic report transaction.
 * Technical completion alone never applies a need, feeling, personality or relation update. */
internal object ActionUnderstanding {
    fun apply(data: JSONObject, frame: InternalStateFrame, report: JSONObject, now: Long): Boolean {
        var changed = false
        for ((field, kind) in listOf("decisions" to "decision", "action_feedback" to "feedback")) {
            val actions = report.optJSONArray(field) ?: continue
            require(actions.length() <= 4)
            val touched = mutableSetOf<String>()
            for (i in 0 until actions.length()) {
                val a = actions.getJSONObject(i)
                val permitted = setOf("id", "expected_version", "meaning", "evidence", "intention_refs") +
                    if (kind == "decision") setOf("status") else setOf("action_id", "result_source")
                require(a.keys().asSequence().all { it in permitted })
                val id = a.getString("id")
                require(id.length in 1..64 && touched.add(id))
                require(a.get("meaning") is String && a.getString("meaning").length in 1..1000)
                val old = CategoryEvents.row(data, kind, id)
                require(old == null || old.getString("parent") == "private")
                require(a.get("expected_version") is Number &&
                    a.getDouble("expected_version") == (old?.getInt("version") ?: 0).toDouble())
                SubjectActions.validateDeclaration(JSONObject().put("intention_refs", a.optJSONArray("intention_refs") ?: JSONArray())
                    .put("evidence", a.getJSONArray("evidence")), frame)
                val evidence = CategoryEvents.strings(a.getJSONArray("evidence"), 8)
                require(evidence.isNotEmpty())
                if (kind == "decision") {
                    require(a.getString("status") in setOf("considering", "chosen", "deferred", "ended"))
                } else {
                    val source = JSONObject(requireNotNull(frame.materialSources[a.getString("result_source")]))
                    require(source.getString("type") == "actual_operation")
                    require(source.getJSONObject("locator").getString("action_id") == a.getString("action_id"))
                    require(a.getString("result_source") in evidence)
                    // The actual provided receipt, including unknown status, is immutable evidence.
                    // Reading it again permits a new interpretation, not a second execution.
                }
                val bindings = JSONObject()
                evidence.forEach { bindings.put(it, InnerStructure.source(data, frame, it)) }
                val content = JSONObject(a.toString()).apply { remove("expected_version") }
                    .put("source_bindings", bindings)
                CategoryEvents.append(data, frame, JSONObject().put("storage_kind", kind).put("id", id)
                    .put("parent", "private").put("owner", frame.roleId).put("source_chat", frame.chatId)
                    .put("version", (old?.getInt("version") ?: 0) + 1).put("frame_id", frame.id).put("at", now)
                    .put("source", "subject_report_adoption").put("content", content))
                changed = true
            }
        }
        return changed
    }

    fun materials(data: JSONObject): List<JSONObject> = listOf("decision", "feedback").flatMap { kind ->
        val rows = CategoryEvents.page(data, kind, 0, "private").getJSONArray("items")
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val content = JSONObject(row.getJSONObject("content").toString()).apply { remove("source_bindings") }
            JSONObject().put("id", "material:$kind:${row.getString("id")}:${row.getInt("version")}")
                .put("type", "adopted_$kind").put("owner", row.getString("owner")).put("author", row.getString("owner"))
                .put("at", row.getLong("at")).put("status", "retrieved").put("not_new_occurrence", true)
                .put("text", content.toString())
                .put("locator", JSONObject().put("tool", "companion_internal_state get section=$kind")
                    .put("id", row.getString("id")).put("version", row.getInt("version")))
        }
    }

    val instructions = """
        [决定、实际操作和理解]
        你可结合内在状态表达、继续自己的兴趣、探索、使用能力、调整或暂缓。不要求固定次序，不要求每次行动或每次不行动。普通回复和原有工具无需增加心理表单。
        工具请求可选参数 __operit_action（JSON字符串）记录已形成的 decision、intention_refs:[{kind,id,version}]、evidence:[本轮来源ID]，以及明确再试时 retry_of:原操作ID。宿主在执行前保存原始请求，保留实际权限。引用须来自本轮已读材料，普通工具可省略此参数。它不是完整状态报告，也不证明体验已改变。
        可选 capability:{id,version,stage:"build|configure|run|adjust",target} 标识明确的软件能力使用；只作你给出的关联，不证明安装或达到预期。沿已有写文件/脚本/工具包能力执行，宿主保留实际请求和回执，不提供新权限或任意构建保证。
        实际操作用 companion_internal_state get section=action id=操作ID 或 offset 分页查询；section=action_versions id=操作ID 查该操作的增量过程。requested/started且无持久回执是处理中或未知，不自动重放；completed只是技术返回成功，partial/failed/denied/unknown不是心理结论。saved也不证明用户已读。
        私聊轮末报告可选 decisions:[{id,expected_version,status:"considering|chosen|deferred|ended",meaning,evidence:[],intention_refs:[]}] 保存你自己的方向，不复制待办/预约，不调度执行。
        对实际提供的操作材料可报告 action_feedback:[{id,expected_version,action_id,result_source:"material:action:...",meaning,evidence:[result_source],intention_refs:[]}]。理解与人格/偏好/情感改变分别沿原inner/updates/episodes/unions规则；同一报告原子采用。技术成功不自动满足，失败不自动放弃。可后来重新认识同一结果，未取用不等于遗忘。
        决定与理解用 get section=decision|feedback 查询，category_versions用decision:ID或feedback:ID追溯。仍轮末采用，工具事实即时保存；本轮已有工具续轮可读取实际回执，没有续轮则下次触发再读，不额外调用模型。
    """.trimIndent()
}
