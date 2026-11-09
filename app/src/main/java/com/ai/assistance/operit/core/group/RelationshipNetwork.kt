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
    private fun legacy(): List<JSONObject> = store.read("relationships").let { root -> root.keys().asSequence().map { root.getJSONObject(it) }.toList() }
    fun all(): List<JSONObject> {
        val states = com.ai.assistance.operit.core.companion.InternalStateStore(context)
        val caller = com.ai.assistance.operit.data.preferences.AssistantScope.callerId()
        val owners = if (caller != null) listOf(caller) else
            com.ai.assistance.operit.core.companion.CompanionStore(context).preferences.all.keys
                .filter { it.startsWith("internal_state:") }.map { it.removePrefix("internal_state:") }
        val current = owners.filter { states.enabled(it) }.flatMap { owner -> adopted(owner) }
        val overridden = current.map { it.getString("from") + ":" + it.getString("to") }.toSet()
        return current + legacy().filter { it.getString("from") + ":" + it.getString("to") !in overridden }.map { row ->
            if (states.enabled(row.getString("from"))) JSONObject(row.toString())
                .put("adoption", "legacy_unadopted").put("notice", "旧关系原文；来源保留，不代表主体已采用")
            else row
        }
    }
    private fun adopted(owner: String): List<JSONObject> =
        com.ai.assistance.operit.core.companion.InnerStructure.records(
            com.ai.assistance.operit.core.companion.InternalStateStore(context).read(owner)).filter {
                it.getJSONObject("content").getString("family") == "relation"
            }.map { record ->
                val content = record.getJSONObject("content")
                JSONObject().put("from", owner).put("to", content.getString("object_ref"))
                    .put("relation", content.getString("label")).put("opinion", content.getString("understanding"))
                    .put("progress", content.getString("feeling")).put("experiences", content.getString("description"))
                    .put("revision", record.getInt("version")).put("source", record.getString("origin"))
                    .put("updated_by", owner).put("updated_at", record.getLong("adopted_at"))
                    .put("adoption", "subject_adopted").put("status", content.getString("status"))
            }
    suspend fun list(caller: String): List<JSONObject> {
        com.ai.assistance.operit.core.companion.SubjectStateBoundary.authorize(caller,
            com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        val primary = CharacterCardManager.getInstance(context).isPrimaryAssistant(caller)
        val states = com.ai.assistance.operit.core.companion.InternalStateStore(context)
        val current = if (states.enabled(caller)) adopted(caller) else emptyList()
        val overridden = current.map { it.getString("from") + ":" + it.getString("to") }.toSet()
        return current + legacy().filter {
            it.getString("from") + ":" + it.getString("to") !in overridden &&
                RelationshipPolicy.mayRead(caller, it.getString("from"), it.getString("to"), primary)
        }.map { row -> if (states.enabled(row.getString("from"))) JSONObject(row.toString())
            .put("adoption", "legacy_unadopted").put("notice", "旧关系原文；来源保留，不代表主体已采用") else row }
    }
    suspend fun save(caller: String?, from: String, to: String, relation: String, opinion: String, progress: String, experiences: String, source: String): JSONObject {
        val cards = CharacterCardManager.getInstance(context)
        protectSubject(caller, from)
        if (caller != null) check(RelationshipPolicy.mayEdit(caller, from, cards.isPrimaryAssistant(caller))) { "只能修改自己的看法，主助手可全局管理" }
        val ids = cards.getAllCharacterCards().map { it.id }
        require(from != to && from in ids && (to in ids || to == "user" &&
            com.ai.assistance.operit.core.companion.InternalStateStore(context).enabled(from))) { "请选择现有助手，内部状态也可记录对用户的认识" }
        require(relation.isNotBlank() && relation.length <= 100 && opinion.length <= 2000 && progress.length <= 2000 && experiences.length <= 6000) { "关系必填，关系100字、看法/进展各2000字、经历6000字以内" }
        val value = JSONObject().put("from", from).put("to", to).put("relation", relation).put("opinion", opinion)
            .put("progress", progress).put("experiences", experiences).put("source_chat", source).put("updated_by", caller ?: "user")
            .put("updated_at", System.currentTimeMillis()).put("perspective", "这是from对to的记录，不代表to同意，不属于查看者的亲历")
            .put("source", if (caller == null) "user_edit" else if (caller == from) "assistant_relationship" else "primary_edit")
        if (com.ai.assistance.operit.core.companion.InternalStateStore(context).enabled(from)) {
            val result = com.ai.assistance.operit.core.companion.InternalStateStore(context)
                .relationshipProposal(from, caller, value, source)
            if (!result.getBoolean("replayed")) store.log("relationship_audit", "关系建议", "$from → $to", caller ?: "user")
            return result
        }
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
        if (com.ai.assistance.operit.core.companion.InternalStateStore(context).enabled(from)) {
            val old = adopted(from).firstOrNull { it.getString("to") == to }
                ?: legacy().firstOrNull { it.getString("from") == from && it.getString("to") == to }
                ?: error("关系记录不存在")
            com.ai.assistance.operit.core.companion.InternalStateStore(context).relationshipProposal(from, caller,
                JSONObject(old.toString()).put("operation", "request_retirement"), "")
            store.log("relationship_audit", "关系结束建议", "$from → $to", caller ?: "user")
            return
        }
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
        if (com.ai.assistance.operit.core.companion.InternalStateStore(context).enabled(actor)) {
            // The only adopted relational view is selected with the other inner records.
            return RuntimeContextSections(instructions = "\n旧关系编辑保存为有来源的建议；已采用的关系认识只从 inner_structure 读取。assistant_relationship list 可查原文与采用状态，不能把另一方表述认成自己的体验。\n")
        }
        val own = legacy().filter { it.optString("from") == actor }.sortedByDescending { it.optLong("updated_at") }.take(4)
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

