package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.StringResultData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import com.ai.assistance.operit.core.tools.ToolResultData

/** Durable objective facts, separate from adopted subjective understanding.
 * No recovery path replays an unconfirmed external operation. */
internal class SubjectActions(private val context: Context, val frame: InternalStateFrame) {
    private val store = CompanionStore(context)

    data class Claim(val id: String, val execute: Boolean, val replay: ToolResult?)

    suspend fun claim(id: String, tool: AITool, declaration: String?): Claim = withContext(Dispatchers.IO) {
        val state = InternalStateStore(context)
        check(state.enabled(frame.roleId)) { "指定主体内部状态已关闭" }
        val parent = if (state.requireRecipient(frame.roleId, frame.chatId)) "group:${frame.chatId}" else "private"
        val parameters = JSONArray(tool.parameters.map { JSONObject().put("name", it.name).put("value", it.value) })
        val request = JSONObject().put("tool", tool.name).put("parameters", parameters)
        if (tool.name == "companion_life" && tool.parameters.firstOrNull { it.name == "action" }?.value == "ack") {
            val ids = CategoryEvents.strings(JSONObject(requireNotNull(tool.parameters.firstOrNull { it.name == "details" }?.value))
                .getJSONArray("ids"), 64)
            require(ids.all { id -> frame.materialSources.values.any { raw ->
                val source = JSONObject(raw)
                source.optString("owner") == frame.roleId && source.optJSONObject("locator")?.let {
                    it.optString("kind") == "life_event" && it.optString("id") == id
                } == true
            } }) { "先实际读取暂存事件材料，再标记处理；仅有触发索引不等于已读" }
        }
        val declared = declaration?.let { parseDeclaration(it, frame) }
        val digest = InnerStructure.hash(JSONObject().put("request", request).put("declaration", declared ?: JSONObject.NULL))
        var fresh = false
        val row = store.actionWrite(frame.roleId, id) { old ->
            if (old != null) {
                require(old.getString("request_hash") == digest && old.getString("source_chat") == frame.chatId) {
                    "同一操作标识的请求内容或窗口发生冲突"
                }
                old
            } else {
                fresh = true
                JSONObject().put("schema", 1).put("id", id).put("version", 1).put("owner", frame.roleId).put("parent", parent)
                    .put("source_chat", frame.chatId).put("frame_id", frame.id).put("state_version", frame.stateVersion)
                    .put("semantic_revision", frame.revision).put("trigger_sources", JSONArray(frame.eventIds.toList()))
                    .put("request", request).put("request_hash", digest)
                    .put("declaration", declared ?: JSONObject.NULL).put("declaration_authority", "model_request_not_adopted_experience")
                    .put("status", "requested").put("at", System.currentTimeMillis())
            }
        }
        if (!fresh && (!row.has("receipt") || row.optString("status") == "unknown") && tool.name == "companion_software" &&
            tool.parameters.firstOrNull { it.name == "action" }?.value == "build") {
            try {
                fun arg(name: String) = tool.parameters.firstOrNull { it.name == name }?.value
                val verified = SoftwareCapabilityArtifacts(context, frame.roleId, frame.chatId).recoverBuild(
                    requireNotNull(arg("id")), requireNotNull(arg("source")), arg("previous_version"), id)
                if (verified != null) {
                    val result = ToolResult(tool.name, true, StringResultData(verified.toString()))
                    // Resolve uncertainty from this exact immutable output. The old unknown
                    // receipt remains in incremental history, never overwritten in place.
                    store.actionWrite(frame.roleId, id) { old ->
                        val current = requireNotNull(old)
                        if (!current.has("receipt") || current.optString("status") == "unknown") {
                            val receipt = receipt(result)
                            current.put("status", "completed").put("receipt", receipt)
                                .put("receipt_hash", InnerStructure.hash(receipt))
                                .put("version", current.getInt("version") + 1)
                                .put("finished_at", System.currentTimeMillis())
                                .put("verified_artifact", verified.getString("version"))
                        }
                        current
                    }.let { return@withContext Claim(id, false, replay(it)) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.ai.assistance.operit.util.AppLogger.e("SubjectActions", "本地产物不能证实原操作，保留未知且不重放", failure)
            }
        }
        Claim(id, fresh, if (fresh) null else replay(row))
    }

    suspend fun started(id: String) = withContext(Dispatchers.IO) {
        store.actionWrite(frame.roleId, id) { old ->
            val row = requireNotNull(old)
            check(row.getString("status") == "requested")
            row.put("status", "started").put("version", row.getInt("version") + 1)
                .put("started_at", System.currentTimeMillis())
        }
    }

    suspend fun finish(id: String, result: ToolResult, status: String): ToolResult = withContext(Dispatchers.IO) {
        require(status in setOf("completed", "failed", "partial", "cancelled", "unknown", "denied"))
        val executionStatus = when (val data = result.result) {
            is com.ai.assistance.operit.core.tools.TerminalCommandResultData -> if (data.timedOut) "unknown" else if (data.exitCode != 0) "failed" else status
            is com.ai.assistance.operit.core.tools.HiddenTerminalCommandResultData -> if (data.timedOut) "unknown" else if (data.exitCode != 0) "failed" else status
            else -> status
        }
        val receipt = receipt(result)
        store.actionWrite(frame.roleId, id) { old ->
            val row = requireNotNull(old)
            if (row.has("receipt")) {
                check(row.getJSONObject("receipt").toString() == receipt.toString() && row.getString("status") == executionStatus) {
                    "已有回执不能被另一结果覆盖"
                }
                row
            } else row.put("status", executionStatus).put("receipt", receipt).put("receipt_hash", InnerStructure.hash(receipt))
                .put("version", row.getInt("version") + 1).put("finished_at", System.currentTimeMillis())
        }
        result.copy(subjectActionId = id, executionStatus = executionStatus)
    }

    private fun receipt(result: ToolResult) = JSONObject().put("success", result.success).put("text", result.result.toString())
        .put("error", result.error ?: JSONObject.NULL).put("tool", result.toolName)
        .put("data", result.result.toJson()).put("scope", "single_tool_request_not_downstream_goal")

    private fun replay(row: JSONObject): ToolResult {
        val receipt = row.optJSONObject("receipt")
        // A live duplicate and a process-interrupted request are both ineligible for replay.
        // Neither elapsed time nor lack of a final report proves that no side effect happened.
        if (receipt == null) return ToolResult(row.getJSONObject("request").getString("tool"), false,
            StringResultData("操作已登记，尚无持久回执。可能处理中或已中断；结果未知，未再次执行。"),
            "UNKNOWN_RESULT", row.getString("id"), "unknown")
        val data = Json { ignoreUnknownKeys = true; classDiscriminator = "__type" }
            .decodeFromString<ToolResultData>(receipt.getString("data"))
        return ToolResult(receipt.getString("tool"), receipt.getBoolean("success"), data,
            if (receipt.isNull("error")) null else receipt.getString("error"), row.getString("id"), row.getString("status"))
    }

    companion object {
        const val DECLARATION = "__operit_action"
        fun parseDeclaration(raw: String, frame: InternalStateFrame): JSONObject {
            SubjectContracts.expect(raw.length <= SubjectContracts.JSON_CHARS, DECLARATION, "INPUT_SIZE",
                "行动关联JSON最多16384个UTF-16字符；详情可引用已保存决定。")
            val declaration = try { JSONObject(raw) } catch (_: org.json.JSONException) {
                throw InternalStateReportIssue("action", DECLARATION, "JSON_SHAPE", "需要JSON对象字符串；先检查引号、括号和逗号。")
            }
            validateDeclaration(declaration, frame)
            return declaration
        }

        fun validateDeclaration(d: JSONObject, frame: InternalStateFrame, prefix: String = DECLARATION) {
            fun path(key: String) = if (prefix.isEmpty()) key else "$prefix.$key"
            SubjectContracts.fields(d, setOf("decision", "intention_refs", "evidence", "retry_of", "capability"), prefix.ifEmpty { "$" })
            SubjectContracts.jsonSize(d, prefix.ifEmpty { "$" })
            if (d.has("decision")) SubjectContracts.text(d, "decision", empty = true, path = path("decision"))
            val refs = if (d.has("intention_refs")) SubjectContracts.array(d, "intention_refs", path("intention_refs")) else JSONArray()
            for (i in 0 until refs.length()) {
                val p = path("intention_refs[$i]")
                val raw = refs.opt(i)
                SubjectContracts.expect(raw is JSONObject, p, "EXPECTED_OBJECT", "需要{kind,id,version}对象。")
                val ref = raw as JSONObject
                SubjectContracts.fields(ref, setOf("kind", "id", "version"), p)
                val kind = SubjectContracts.text(ref, "kind", path = "$p.kind")
                val id = SubjectContracts.text(ref, "id", path = "$p.id")
                val version = SubjectContracts.integer(ref, "version", "$p.version")
                val providedDecision = kind in (setOf("decision", "feedback") + LifeUnderstandings.kinds) && frame.materialSources.values.any { text ->
                    val source = JSONObject(text)
                    val locator = source.optJSONObject("locator")
                    source.optString("type") in setOf("adopted_$kind", "subject_$kind") && locator != null && locator.optString("id") == id &&
                        locator.optInt("version") == version
                }
                SubjectContracts.expect(providedDecision || frame.interactionMembers["$kind:$id"] == version,
                    p, "MEMBER_NOT_DELIVERED", "先通过get读取该成员的当前版本；没有关联时可以省略intention_refs或使用[]。")
            }
            val evidence = if (d.has("evidence")) SubjectContracts.array(d, "evidence", path("evidence")) else JSONArray()
            SubjectContracts.strings(evidence, path = path("evidence")).forEachIndexed { i, ref ->
                SubjectContracts.expect(ref in frame.eventIds || ref in frame.materialSources,
                    path("evidence[$i]"), "SOURCE_NOT_DELIVERED", "引用本轮已提供的来源ID，或先按需读取来源；不能猜造。")
            }
            if (d.has("retry_of")) {
                val id = SubjectContracts.text(d, "retry_of", path = path("retry_of"))
                SubjectContracts.expect(frame.materialSources.values.any {
                    JSONObject(it).optJSONObject("locator")?.optString("action_id") == id
                }, path("retry_of"), "RECEIPT_NOT_DELIVERED", "明确再试须先读取原操作的实际回执或未知状态。")
            }
            if (d.has("capability")) {
                val raw = d.opt("capability")
                SubjectContracts.expect(raw is JSONObject, path("capability"), "EXPECTED_OBJECT", "提供{id,version,stage,target}对象；普通工具查询可省略整个capability。")
                val c = raw as JSONObject
                SubjectContracts.fields(c, setOf("id", "version", "stage", "target"), path("capability"))
                for (key in listOf("id", "target"))
                    SubjectContracts.text(c, key, 500, path = path("capability.$key"))
                // Capability versions are descriptive labels, not state-version CAS tokens.
                // Preserve numeric labels as supplied; rejecting 1 while accepting "1" blocked
                // otherwise valid calls without protecting any downstream invariant.
                val version = c.opt("version")
                if (version is String) SubjectContracts.text(c, "version", 500, path = path("capability.version"))
                else SubjectContracts.expect(version is Number && version.toDouble().isFinite() && version.toDouble() >= 0,
                    path("capability.version"), "CAPABILITY_VERSION", "能力版本可以是非空字符串或非负有限数字；不能是对象、数组、布尔值或null。")
                SubjectContracts.choice(c, "stage", setOf("build", "configure", "run", "adjust"), path("capability.stage"))
            }
        }

        fun read(context: Context, role: String, chat: String, id: String?, offset: Int): JSONObject {
            val group = InternalStateStore(context).recipientScope(role, chat)
            val parent = if (group) "group:$chat" else "private"
            val store = CompanionStore(context)
            if (id != null) {
                val row = store.actionRead(role, id)
                require(row == null || row.getString("parent") == parent) { "操作不在当前会话的可读范围" }
                return JSONObject().put("item", row ?: JSONObject.NULL)
            }
            val rows = store.actionPage(role, parent, offset)
            return JSONObject().put("items", JSONArray(rows.take(8)))
                .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
        }

        fun material(row: JSONObject): JSONObject {
            val id = row.getString("id")
            val receipt = row.optJSONObject("receipt")
            return JSONObject().put("id", "material:action:$id:${row.getInt("version")}")
                .put("type", "actual_operation").put("owner", row.getString("owner")).put("author", "host_executor")
                .put("at", row.optLong("finished_at", row.getLong("at"))).put("status", "retrieved")
                .put("not_new_occurrence", true).put("understanding", "not_inferred")
                .put("text", JSONObject().put("tool", row.getJSONObject("request").getString("tool"))
                    .put("execution", if (receipt == null) "unknown_or_in_progress" else row.getString("status"))
                    .put("result_excerpt", receipt?.getString("text")?.take(600) ?: JSONObject.NULL)
                    .put("error", receipt?.opt("error") ?: JSONObject.NULL)
                    .put("declared", row.opt("declaration") ?: JSONObject.NULL).toString())
                .put("locator", JSONObject().put("tool", "companion_internal_state get section=action")
                    .put("action_id", id).put("version", row.getInt("version"))
                    .put("parent", row.getString("parent"))
                    .put("receipt_hash", row.opt("receipt_hash") ?: JSONObject.NULL))
        }

        fun materials(context: Context, role: String, chat: String): List<JSONObject> {
            val rows = read(context, role, chat, null, 0).getJSONArray("items")
            return (0 until rows.length()).map { material(rows.getJSONObject(it)) }
        }
    }
}
