package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** A union records one adopted interpretation, not a second reducer of member values.
 * Member changes are validated by the original inner/episode reducers in the same detached
 * report transaction. Reading or ending a union never reapplies those changes.
 */
internal object InnerInteractions {
    /** Inactive definitions remain identifiable for ending an existing union, never for new propagation. */
    fun stateMember(data: JSONObject, id: String, chat: String, at: Long): JSONObject {
        val definition = requireNotNull(InternalStateEngine.definitions(data).firstOrNull { it.getString("id") == id })
        if (!definition.getBoolean("active")) return JSONObject(definition.toString())
            .put("instance_scope", definition.getString("scope")).put("available", false)
        return requireNotNull(InternalStateEngine.snapshot(InternalStateDocument.copy(data), chat, at)
            .firstOrNull { it.getString("id") == id })
    }

    private fun member(data: JSONObject, kind: String, id: String, chat: String, at: Long): JSONObject = when (kind) {
        "inner" -> requireNotNull(InnerStructure.record(data, id))
        "episode" -> requireNotNull(CategoryEvents.row(data, kind, id))
        "state" -> stateMember(data, id, chat, at)
        else -> throw IllegalArgumentException("联合成员类型不受支持")
    }

    fun apply(before: JSONObject, after: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long): Boolean {
        require(actions.length() <= 4)
        val ids = mutableSetOf<String>()
        for (i in 0 until actions.length()) {
            val a = actions.getJSONObject(i)
            require(a.keys().asSequence().all { it in setOf("id", "expected_version", "members", "objects", "evidence", "interpretation", "decision", "status", "effects") })
            val id = text(a, "id", 64)
            require(ids.add(id))
            val old = CategoryEvents.row(before, "union", id)
            require(old?.optString("local_chat").orEmpty() in setOf("", frame.chatId)) { "窗口局部联合不能在另一窗口改写" }
            val expected = a.get("expected_version")
            require(expected is Number && expected.toDouble() == (old?.getInt("version") ?: 0).toDouble())
            require(text(a, "status", 16) in setOf("active", "ended"))
            text(a, "interpretation", 800); text(a, "decision", 500)
            CategoryEvents.strings(a.getJSONArray("objects"), 8)
            val evidence = CategoryEvents.strings(a.getJSONArray("evidence"), 8)
            require(evidence.isNotEmpty() && evidence.all { it in frame.eventIds || it in frame.materialSources }) { "只能引用本轮实际提供的来源" }
            val sources = JSONObject()
            evidence.forEach { sources.put(it, InnerStructure.source(before, frame, it)) }
            val members = a.getJSONArray("members")
            require(members.length() in 2..8)
            val keys = mutableSetOf<String>()
            val transitions = JSONArray()
            var local = false
            for (j in 0 until members.length()) {
                val ref = members.getJSONObject(j)
                require(ref.keys().asSequence().toSet() == setOf("kind", "id", "version"))
                val kind = text(ref, "kind", 16); val target = text(ref, "id", 64)
                require(keys.add("$kind:$target"))
                val prior = member(before, kind, target, frame.chatId, frame.at)
                if (kind == "state" && !prior.optBoolean("available", true)) {
                    val oldRefs = old?.getJSONObject("content")?.getJSONArray("members")
                    require(a.getString("status") == "ended" && oldRefs != null &&
                        (0 until oldRefs.length()).any {
                            val oldRef = oldRefs.getJSONObject(it)
                            oldRef.getString("kind") == kind && oldRef.getString("id") == target
                        }) { "停用成员只可随已有联合明确结束，不能形成新作用" }
                }
                if (kind == "state" && prior.optString("instance_scope") != "global") local = true
                require(frame.interactionMembers["$kind:$target"] == prior.getInt("version")) { "成员未在本轮实际材料中提供，或版本已变化" }
                val version = ref.get("version")
                require(version is Number && version.toDouble() == prior.getInt("version").toDouble())
                val current = member(after, kind, target, frame.chatId, now)
                transitions.put(JSONObject().put("kind", kind).put("id", target)
                    .put("before_version", prior.getInt("version")).put("after_version", current.getInt("version"))
                    .put("before_hash", InnerStructure.hash(prior)).put("after_hash", InnerStructure.hash(current)))
            }
            val effects = a.getJSONArray("effects")
            require(effects.length() <= 16)
            val edges = mutableSetOf<String>()
            for (j in 0 until effects.length()) {
                val effect = effects.getJSONObject(j)
                require(effect.keys().asSequence().toSet() == setOf("from", "to", "meaning"))
                val from = text(effect, "from", 90); val to = text(effect, "to", 90)
                require(from != to && from in keys && to in keys && edges.add("$from>$to"))
                text(effect, "meaning", 400)
            }
            // No recursive propagation, coefficient, time projection or action dispatch here.
            // Equal before/after versions explicitly represent interpretation without a value change.
            val content = JSONObject(a.toString()).apply { remove("expected_version") }
            content.put("source_bindings", sources).put("transitions", transitions)
            require(content.toString().length <= 12000)
            CategoryEvents.append(after, frame, JSONObject().put("storage_kind", "union").put("id", id)
                .put("parent", frame.roleId).put("version", Math.addExact(old?.getInt("version") ?: 0, 1))
                .put("content", content).put("owner", frame.roleId).put("frame_id", frame.id)
                .put("source_chat", frame.chatId).put("local_chat", if (local) frame.chatId else old?.optString("local_chat").orEmpty())
                .put("at", now).put("source", "subject_report_adoption"))
        }
        return actions.length() > 0
    }

    fun material(data: JSONObject, persist: Boolean = false, chat: String = "", at: Long = System.currentTimeMillis()): JSONObject {
        val selection = data.optJSONObject("category_selection") ?: JSONObject().also { data.put("category_selection", it) }
        val page = CategoryEvents.page(data, "union", selection.optInt("union"))
        if (persist) selection.put("union", if (page.isNull("next_offset")) 0 else page.getInt("next_offset"))
        val rows = page.getJSONArray("items")
        // A persistent union may refer to a session-scoped member. Keep the union/history,
        // but never project that session's private interpretation into another window.
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.getJSONObject(i)
            if (row.optString("local_chat").isNotBlank() && row.optString("local_chat") != chat) rows.remove(i)
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val refs = row.getJSONObject("content").getJSONArray("members")
            val versions = JSONObject()
            for (j in 0 until refs.length()) {
                val ref = refs.getJSONObject(j)
                val kind = ref.getString("kind")
                val id = ref.getString("id")
                val version = when (kind) {
                    "inner" -> InnerStructure.record(data, id)?.optInt("version")
                    "episode" -> CategoryEvents.row(data, kind, id)?.optInt("version")
                    "state" -> InternalStateEngine.definitions(data).firstOrNull { it.getString("id") == id && it.optBoolean("active") }?.optInt("version")
                    else -> null
                }
                versions.put("$kind:$id", version ?: JSONObject.NULL)
            }
            row.put("current_member_versions", versions)
            // The model receives the adopted meaning, not a replay of the transaction's hashes.
            // Full transitions remain available through the existing union/version query.
            row.getJSONObject("content").remove("transitions")
            val bindings = row.getJSONObject("content").optJSONObject("source_bindings")
            bindings?.keys()?.forEach { id -> bindings.getJSONObject(id).remove("snapshot") }
            row.put("projection", "current_meaning; full transaction via get union/version")
        }
        return page.put("notice", "联合保留当时理解；当前成员版本单独列出，旧联合不得覆盖成员的新状态。读取不再次生效。")
    }

    private fun text(o: JSONObject, key: String, max: Int): String {
        require(o.get(key) is String)
        return o.getString(key).also { require(it.isNotBlank() && it.length <= max) }
    }

    val instructions = """
        [内在联合]
        预算未选中的成员可用 companion_internal_state action=get section=inner|state|episode id=成员ID 按需读取；完整原生返回在同轮登记版本。截断结果不登记，请使用按ID读取。联合仅在自己的私聊处理，窗口局部成员不能在另一窗口改写。
        同一报告可带 unions:[{id,expected_version,members:[{kind:"inner|episode|state",id,version}],objects:[],evidence:["本轮来源ID"],interpretation:"共同理解或冲突",decision:"倾向或暂未形成决定",status:"active|ended",effects:[{from:"inner:ID",to:"episode:ID",meaning:"作用解释"}]}]，每轮最多4项。成员须在本轮实际选入材料。state是原有数值维度，用原updates/revise更新；inner/episodes仍走原采用规则。companion_internal_state get section:union 可按id或offset查询，category_versions用union:ID查询历史。
        retrieved_materials 是本轮实际取回并提供的材料，可引用其中status=retrieved的ID。保留作者、原来源、时间和locator；摘要不是原话，旧聊天不是新发生或自动相信的事实。failed不是读过。important_changes中的ID和current_input_event也可引用。外部MCP仍按需用已授权工具查，不会每轮自动访问。
        联合不代替成员。需要改变成员时，在同一报告的原 inner/episodes 中明确提出，仍须原采用规则；全部成功才保存。双方可影响，但不必都改值，也可无变化或相反方向。不得重复计入同一事实，不做循环放大，不要求行动。
        求生欲只能通过原明确候选采用改值；联合和时间不会代它改变强度。人格发展和偏好采用仍须原证据与前态核对。只记录必要解释、决定与作用，不输出隐藏思维链。
    """.trimIndent()
}
