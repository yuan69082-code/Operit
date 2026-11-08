package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.core.chat.RuntimeContextSections
import android.content.Context
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import org.json.JSONArray
import org.json.JSONObject

object RelationshipPolicy {
    fun mayEdit(caller: String, from: String, primary: Boolean) = primary || caller == from
    fun mayRead(caller: String, from: String, to: String, primary: Boolean) = primary || caller == from || caller == to
}

/** Directed records: A's view of B never silently overwrites B's view of A. */
class RelationshipNetwork(private val context: Context) {
    private val store = GroupStore(context)
    fun all(): List<JSONObject> = store.read("relationships").let { root -> root.keys().asSequence().map { root.getJSONObject(it) }.toList() }
    suspend fun list(caller: String): List<JSONObject> {
        val primary = CharacterCardManager.getInstance(context).isPrimaryAssistant(caller)
        return all().filter { RelationshipPolicy.mayRead(caller, it.getString("from"), it.getString("to"), primary) }
    }
    suspend fun save(caller: String?, from: String, to: String, relation: String, opinion: String, progress: String, experiences: String, source: String): JSONObject {
        val cards = CharacterCardManager.getInstance(context)
        protectSubject(caller, from)
        if (caller != null) check(RelationshipPolicy.mayEdit(caller, from, cards.isPrimaryAssistant(caller))) { "只能修改自己的看法，主助手可全局管理" }
        val ids = cards.getAllCharacterCards().map { it.id }
        require(from != to && from in ids && to in ids) { "请选择两位不同的现有助手" }
        require(relation.isNotBlank() && relation.length <= 100 && opinion.length <= 2000 && progress.length <= 2000 && experiences.length <= 6000) { "关系必填，关系100字、看法/进展各2000字、经历6000字以内" }
        val value = JSONObject().put("from", from).put("to", to).put("relation", relation).put("opinion", opinion)
            .put("progress", progress).put("experiences", experiences).put("source_chat", source).put("updated_by", caller ?: "user")
            .put("updated_at", System.currentTimeMillis()).put("perspective", "这是from对to的记录，不代表to同意，不属于查看者的亲历")
            .put("source", if (caller == null) "user_edit" else if (caller == from) "assistant_relationship" else "primary_edit")
        store.update("relationships") {
            value.put("revision", Math.addExact(it.optJSONObject("$from:$to")?.optLong("revision") ?: 0L, 1L))
            it.put("$from:$to", value)
        }
        store.log("relationship_audit", "修改", "$from → $to", caller ?: "user")
        return value
    }
    suspend fun delete(caller: String?, from: String, to: String) {
        protectSubject(caller, from)
        if (caller != null) check(RelationshipPolicy.mayEdit(caller, from, CharacterCardManager.getInstance(context).isPrimaryAssistant(caller))) { "没有修改权限" }
        store.update("relationships") { it.remove("$from:$to") }
        store.log("relationship_audit", "删除", "$from → $to", caller ?: "user")
    }
    private fun protectSubject(caller: String?, from: String) {
        check(caller == null || caller == from ||
            !com.ai.assistance.operit.core.companion.InternalStateStore(context).enabled(from)) {
            "内部状态已启用，其他助手不能代写该主体的关系看法；用户仍可明确编辑"
        }
    }
    fun prompt(actor: String): String = promptSections(actor).asText()

    fun promptSections(actor: String): RuntimeContextSections {
        val own = all().filter { it.optString("from") == actor }.sortedByDescending { it.optLong("updated_at") }.take(4)
        return RuntimeContextSections(
            instructions = "\n[关系网] 可用 assistant_relationship 查看或维护你的关系、看法、进展和共同经历。各人的观点分别保存，阅读不等于亲历。详细记录按需读取。\n",
            state = if (own.isEmpty()) "" else "以下是归属于你的关系记录；updated_by/source 区分用户或他人编辑，不等于你的亲历；缺失来源不能推断为你本人：\n" + JSONArray(own.map {
                JSONObject().put("from", actor).put("to", it.getString("to")).put("relation", it.getString("relation")).put("progress", it.optString("progress").take(180))
                    .put("updated_by", it.opt("updated_by") ?: JSONObject.NULL).put("source", it.opt("source") ?: JSONObject.NULL)
                    .put("revision", it.opt("revision") ?: JSONObject.NULL)
            }).toString() + "\n"
        )
    }
}

