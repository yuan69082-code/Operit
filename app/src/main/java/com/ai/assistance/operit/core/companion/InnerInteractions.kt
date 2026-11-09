package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** A union records one adopted interpretation, not a second reducer of member values.
 * Member changes are validated by the original inner/episode reducers in the same detached
 * report transaction. Reading or ending a union never reapplies those changes.
 */
internal object InnerInteractions {
    private fun member(data: JSONObject, kind: String, id: String): JSONObject = when (kind) {
        "inner" -> requireNotNull(InnerStructure.records(data).firstOrNull { it.getString("id") == id })
        "episode" -> requireNotNull(CategoryEvents.row(data, kind, id))
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
            val expected = a.get("expected_version")
            require(expected is Number && expected.toDouble() == (old?.getInt("version") ?: 0).toDouble())
            require(text(a, "status", 16) in setOf("active", "ended"))
            text(a, "interpretation", 800); text(a, "decision", 500)
            CategoryEvents.strings(a.getJSONArray("objects"), 8)
            val evidence = CategoryEvents.strings(a.getJSONArray("evidence"), 8)
            require(evidence.isNotEmpty() && evidence.all { it in frame.eventIds }) { "只能引用本轮实际提供的来源" }
            val sources = JSONObject()
            evidence.forEach { sources.put(it, InnerStructure.source(before, frame, it)) }
            val members = a.getJSONArray("members")
            require(members.length() in 2..8)
            val keys = mutableSetOf<String>()
            val transitions = JSONArray()
            for (j in 0 until members.length()) {
                val ref = members.getJSONObject(j)
                require(ref.keys().asSequence().toSet() == setOf("kind", "id", "version"))
                val kind = text(ref, "kind", 16); val target = text(ref, "id", 64)
                require(keys.add("$kind:$target"))
                val prior = member(before, kind, target)
                require(frame.interactionMembers["$kind:$target"] == prior.getInt("version")) { "成员未在本轮实际材料中提供，或版本已变化" }
                val version = ref.get("version")
                require(version is Number && version.toDouble() == prior.getInt("version").toDouble())
                val current = member(after, kind, target)
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
                .put("source_chat", frame.chatId).put("at", now).put("source", "subject_report_adoption"))
        }
        return actions.length() > 0
    }

    fun material(data: JSONObject): JSONObject {
        val page = CategoryEvents.page(data, "union", 0)
        val rows = page.getJSONArray("items")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val refs = row.getJSONObject("content").getJSONArray("members")
            val versions = JSONObject()
            for (j in 0 until refs.length()) {
                val ref = refs.getJSONObject(j)
                versions.put("${ref.getString("kind")}:${ref.getString("id")}", member(data, ref.getString("kind"), ref.getString("id")).getInt("version"))
            }
            row.put("current_member_versions", versions)
        }
        return page.put("notice", "联合保留当时理解；当前成员版本单独列出，旧联合不得覆盖成员的新状态。读取不再次生效。")
    }

    private fun text(o: JSONObject, key: String, max: Int): String {
        require(o.get(key) is String)
        return o.getString(key).also { require(it.isNotBlank() && it.length <= max) }
    }

    val instructions = """
        [内在联合]
        同一报告可带 unions:[{id,expected_version,members:[{kind:"inner|episode",id,version}],objects:[],evidence:["本轮来源ID"],interpretation:"共同理解或冲突",decision:"倾向或暂未形成决定",status:"active|ended",effects:[{from:"inner:ID",to:"episode:ID",meaning:"作用解释"}]}]，每轮最多4项。成员须在本轮实际选入材料。companion_internal_state get section:union 可按id或offset查询，category_versions用union:ID查询历史。
        联合不代替成员。需要改变成员时，在同一报告的原 inner/episodes 中明确提出，仍须原采用规则；全部成功才保存。双方可影响，但不必都改值，也可无变化或相反方向。不得重复计入同一事实，不做循环放大，不要求行动。
        求生欲只能通过原明确候选采用改值；联合和时间不会代它改变强度。人格发展和偏好采用仍须原证据与前态核对。只记录必要解释、决定与作用，不输出隐藏思维链。
    """.trimIndent()
}
