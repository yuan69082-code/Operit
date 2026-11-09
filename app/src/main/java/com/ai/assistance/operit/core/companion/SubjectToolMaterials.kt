package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.json.JSONObject
import org.json.JSONArray
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.InternalStateReadResultData

/** Bind the normalized result actually sent on a tool continuation, including on-demand MCP
 * memory and library reads. The result already occupies the tool part of the full window;
 * do not duplicate its body in the subject material block or claim an unexecuted read.
 */
internal object SubjectToolMaterials {
    fun attach(turns: List<PromptTurn>, at: Long, results: List<ToolResult> = emptyList(),
               context: android.content.Context? = null): List<PromptTurn> {
        val frame = CurrentRequestContext.internalStateFrame(turns) ?: return turns
        val index = turns.indexOfLast { it.kind == PromptTurnKind.TOOL_RESULT }
        if (index < 0) return turns
        val turn = turns[index]
        if (turn.metadata["subject_material_id"] != null) return turns
        val hash = InnerStructure.hash(turn.content)
        // Identical return text from distinct calls is not the same occurrence. Retried
        // projection retains the metadata receipt; identity is scoped to this turn/result.
        val id = "material:tool:" + InnerStructure.hash("${frame.id}:$index:${turn.toolName}:$hash")
        val material = JSONObject().put("id", id).put("type", "tool_result")
            .put("author", turn.toolName ?: JSONObject.NULL).put("owner", frame.roleId)
            .put("at", at).put("text", turn.content.take(1200)).put("excerpt", turn.content.length > 1200)
            .put("status", "retrieved").put("not_new_occurrence", true)
            .put("locator", JSONObject().put("chat_id", frame.chatId).put("frame_id", frame.id)
                .put("tool", turn.toolName ?: JSONObject.NULL).put("normalized_result_hash", hash))
        // Bounded per request, without an unbounded process-global registry. Older results
        // remain in normal tool history; an unbound result cannot claim a receipt here.
        val sources = (frame.materialSources + (id to material.toString())).toMutableMap()
        val actionIds = mutableListOf<String>()
        if (context != null) results.forEach { result ->
            val action = result.subjectActionId ?: return@forEach
            val row = SubjectActions.read(context, frame.roleId, frame.chatId, action, 0).optJSONObject("item")
                ?: return@forEach
            val receipt = row.optJSONObject("receipt") ?: return@forEach
            if (receipt.getString("text") != result.result.toString() ||
                !turn.content.contains(result.result.toString())) return@forEach
            val source = SubjectActions.material(row)
            sources[source.getString("id")] = source.toString()
            actionIds.add(source.getString("id"))
        }
        val members = frame.interactionMembers.toMutableMap()
        results.filter { it.success && it.toolName == "companion_internal_state" }.forEach { result ->
            val receipt = result.result as? InternalStateReadResultData ?: return@forEach
            // Only complete, actually delivered native reads establish member/version proof.
            // A truncated, transformed, foreign or historical result remains ordinary material.
            if (!receipt.privateContext || receipt.owner != frame.roleId || receipt.chatId != frame.chatId ||
                !turn.content.contains(receipt.value)) return@forEach
            val body = JSONObject(receipt.value)
            if (receipt.section == "action") {
                val rows = body.optJSONObject("item")?.let { JSONArray().put(it) } ?: body.optJSONArray("items")
                if (rows != null) for (i in 0 until rows.length()) {
                    val source = SubjectActions.material(rows.getJSONObject(i))
                    sources[source.getString("id")] = source.toString()
                    actionIds.add(source.getString("id"))
                }
            }
            fun rows(kind: String, values: JSONArray?) {
                if (values == null) return
                for (i in 0 until values.length()) {
                    val row = values.getJSONObject(i)
                    members["$kind:${row.getString("id")}"] = row.getInt("version")
                }
            }
            when (receipt.section) {
                null -> {
                    rows("inner", body.optJSONObject("inner")?.optJSONArray("records"))
                    rows("episode", body.optJSONObject("psychological_events")?.optJSONArray("items"))
                    rows("state", body.optJSONArray("states"))
                }
                "inner", "state", "episode" -> {
                    val one = body.optJSONObject("item")
                    rows(receipt.section, if (one != null) JSONArray().put(one)
                        else body.optJSONArray(if (receipt.section == "inner") "records" else "items"))
                }
            }
        }
        val bound = CurrentRequestContext.bindInternalStateFrame(turns, frame.copy(
            materialSources = sources.entries.toList().takeLast(24).associate { it.key to it.value }, interactionMembers = members))
        return bound.mapIndexed { i, row ->
            if (i != index) row else row.copy(content = row.content +
                "\n[宿主来源ID:$id；实际操作来源:${actionIds.joinToString(",")}；仅指上述实际工具返回，成功或失败以上述结果为准，不表示新亲历。]",
                metadata = row.metadata + ("subject_material_id" to id))
        }
    }
}
