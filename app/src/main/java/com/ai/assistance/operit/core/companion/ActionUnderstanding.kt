package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Subject interpretation is adopted only in the original atomic report transaction.
 * Technical completion alone never applies a need, feeling, personality or relation update. */
internal object ActionUnderstanding {
    fun apply(data: JSONObject, frame: InternalStateFrame, report: JSONObject, now: Long): Boolean {
        var changed = false
        for ((field, kind) in listOf("decisions" to "decision", "action_feedback" to "feedback")) {
            if (!report.has(field)) continue
            val actions = report.getJSONArray(field)
            val touched = mutableSetOf<String>()
            for (i in 0 until actions.length()) {
                try {
                val a = actions.getJSONObject(i)
                val permitted = setOf("id", "expected_version", "meaning", "evidence", "intention_refs") +
                    if (kind == "decision") setOf("status") else setOf("action_id", "result_source")
                SubjectContracts.fields(a, permitted)
                val id = SubjectContracts.text(a, "id", 64)
                SubjectContracts.expect(touched.add(id), "id", "DUPLICATE_WRITE", "同一报告不能重复写入同一条记录。")
                SubjectContracts.text(a, "meaning")
                val old = CategoryEvents.row(data, kind, id)
                require(old == null || old.getString("parent") == "private")
                SubjectContracts.expect(SubjectContracts.integer(a, "expected_version") == (old?.getInt("version") ?: 0),
                    "expected_version", "VERSION_CHANGED", "先读取当前版本；新记录用0。")
                SubjectActions.validateDeclaration(JSONObject().put("intention_refs", if (a.has("intention_refs")) a.getJSONArray("intention_refs") else JSONArray())
                    .put("evidence", a.getJSONArray("evidence")), frame, prefix = "")
                val evidence = SubjectContracts.strings(SubjectContracts.array(a, "evidence"), path = "evidence")
                SubjectContracts.expect(evidence.isNotEmpty(), "evidence", "EVIDENCE_REQUIRED", "采用决定或反馈需要至少一个实际来源ID。")
                if (kind == "decision") {
                    SubjectContracts.choice(a, "status", setOf("considering", "chosen", "deferred", "ended"))
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
                } catch (failure: Exception) {
                    throw InternalStateReportDiagnostics.issue(failure, "$field[$i]")
                }
            }
        }
        return changed
    }

    fun materials(data: JSONObject, input: String = "", chat: String = ""): List<JSONObject> = listOf("decision", "feedback").flatMap { kind ->
        SubjectRecall.selected(data, kind, input, chat, data.optLong("material_query_cursor")).map { row ->
            val content = JSONObject(row.getJSONObject("content").toString()).apply {
                remove("source_bindings")
                // Request projection only: copying an old report-shaped field invited stale writes.
                if (has("intention_refs")) put("historical_intention_refs", remove("intention_refs"))
            }
            JSONObject().put("id", "material:$kind:${row.getString("id")}:${row.getInt("version")}")
                .put("type", "adopted_$kind").put("owner", row.getString("owner")).put("author", row.getString("owner"))
                .put("at", row.getLong("at")).put("status", "retrieved").put("not_new_occurrence", true)
                .put("selection_reason", row.optString("selection_reason", "ROTATION"))
                .put("referenced_members_are_historical", true)
                .put("text", content.toString())
                .put("locator", JSONObject().put("tool", "companion_internal_state get section=$kind")
                    .put("id", row.getString("id")).put("version", row.getInt("version")))
        }
    }

    val instructions = """
        [决定、实际操作和理解]
        历史决定或反馈内的intention_refs只说明当时的关联，不证明引用成员已在本轮读到；state_catalog也只是目录。需要沿用关联时先get读取该成员当前版本，再填引用。已承接驱力用foundation引用；旧状态仅用于尚未承接的原有规则，不再新建同义数值项。没有实际关联可省略intention_refs，不为填表补造。
        你可结合内在状态表达、继续自己的兴趣、探索、使用能力、调整或暂缓。不要求固定次序，不要求每次行动或每次不行动。普通回复和原有工具无需增加心理表单。
        工具请求可选参数 __operit_action（JSON字符串）记录已形成的 decision、intention_refs:[{kind,id,version}]、evidence:[本轮来源ID]，以及明确再试时 retry_of:原操作ID。宿主在执行前保存原始请求，保留实际权限。引用须来自本轮已读材料，普通工具可省略此参数。它不是完整状态报告，也不证明体验已改变。
        可选 capability:{id:"能力ID",version:"版本字符串",stage:"build|configure|run|adjust",target} 标识明确的软件能力使用；id/target为1..500字符字符串，version可为1..500字符字符串或非负有限数字（均按原值保存）；intention_refs.version须为当前整数版本；无能力关联时省略整个capability，intention_refs/evidence可省略或为[]；只作你给出的关联，不证明安装或达到预期。沿已有写文件/脚本/工具包能力执行，宿主保留实际请求和回执，不提供新权限或任意构建保证。
        实际操作用 companion_internal_state get section=action id=操作ID 或 offset 分页查询；section=action_versions id=操作ID 查该操作的增量过程。requested/started且无持久回执是处理中或未知，不自动重放；completed只是技术返回成功，partial/failed/denied/unknown不是心理结论。saved也不证明用户已读。
        私聊轮末报告可选 decisions:[{id,expected_version,status:"considering|chosen|deferred|ended",meaning,evidence:[],intention_refs:[]}] 保存你自己的方向，不复制待办/预约，不调度执行。
        选择长期保留时可用 companion_reading save_source/progress 或 companion_todo save 保存来源、理解与进度；只有希望未来再处理时才调用 companion_explore schedule 或 companion_todo submit。预约 purpose 写清事项/来源ID与本次问题，按回执确认时间；保存决定不等于预约成功。explore只有一个当前自由判断预约，新schedule替换原预约，schedule_replaced会注明；不代表多个兴趣都各自预约成功。私人事项的submit逐项保存独立dispatch，但探索轮不能借它绕过探索开关/间隔/每日额度；保留多项进度与安排一个下次判断是不同事实。下一次先核对进度与实际操作，可重新评价、转向或放下。经验可沿原 inner 候选采用为方法、认识或偏好，原始结果与解释分别保留。
        自由时间的当前相关计划也会作为有界索引参与正常请求；结合情绪、驱力、需要和新认识，自主决定是否用companion_life free_status/free_plan/free_progress修改安排。计划中的进度自述不等于已核实回执；活动结果的理解沿下述反馈与原状态报告采用，之后可参与新安排，不设情绪阈值自动排班。
        对实际提供的操作材料可报告 action_feedback:[{id,expected_version,action_id,result_source:"material:action:...",meaning,evidence:[result_source],intention_refs:[]}]。理解与人格/偏好/情感改变分别沿原inner/updates/episodes/unions规则；同一报告原子采用。技术成功不自动满足，失败不自动放弃。可后来重新认识同一结果，未取用不等于遗忘。
        决定与理解用 get section=decision|feedback 查询，category_versions用decision:ID或feedback:ID追溯。仍轮末采用，工具事实即时保存；本轮已有工具续轮可读取实际回执，没有续轮则下次触发再读，不额外调用模型。
    """.trimIndent()
}
