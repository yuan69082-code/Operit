package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.json.JSONObject

/** Bind the normalized result actually sent on a tool continuation, including on-demand MCP
 * memory and library reads. The result already occupies the tool part of the full window;
 * do not duplicate its body in the subject material block or claim an unexecuted read.
 */
internal object SubjectToolMaterials {
    fun attach(turns: List<PromptTurn>, at: Long): List<PromptTurn> {
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
        val sources = (frame.materialSources + (id to material.toString())).entries.toList().takeLast(16)
            .associate { it.key to it.value }
        val bound = CurrentRequestContext.bindInternalStateFrame(turns, frame.copy(materialSources = sources))
        return bound.mapIndexed { i, row ->
            if (i != index) row else row.copy(content = row.content +
                "\n[宿主来源ID:$id；仅指上述实际工具返回，成功或失败以上述结果为准，不表示新亲历。]",
                metadata = row.metadata + ("subject_material_id" to id))
        }
    }
}
