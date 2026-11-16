package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/** Source-bound, explicitly adopted time rules. No recursive propagation, inferred
 * psychological conclusion, wake, tool call or mutation on read. Each target has one
 * current rule in the existing archive; every replacement retains its version history. */
internal object InnerTemporalEffects {
    private const val STORAGE = "inner_time"

    private fun pausedElapsed(data: JSONObject, now: Long): Long {
        val clock = data.optJSONObject("foundation_state") ?: return 0L
        return clock.optLong("paused_elapsed") + if (clock.has("paused_at"))
            (now - clock.getLong("paused_at")).coerceAtLeast(0) else 0L
    }

    private fun profile(data: JSONObject, target: String): JSONObject? {
        val journal = data.optJSONObject("category_commit")?.optJSONArray("rows")
        if (journal != null) for (i in journal.length() - 1 downTo 0) {
            val row = journal.getJSONObject(i)
            if (row.optString("storage_kind") == STORAGE && row.optString("id") == target) return row
        }
        return (data as? InternalStateDocument)?.history?.categoryRow(STORAGE, target)
    }

    fun projection(data: JSONObject, target: String, version: Int, now: Long,
                   natural: (Double, Double) -> Double): JSONObject? {
        val saved = profile(data, target) ?: return null
        val rule = saved.getJSONObject("content")
        // A later member appraisal supersedes its old union anchor, including after restart.
        if (rule.getInt("member_version") != version) return null
        val elapsed = (now - rule.getLong("anchor_at") -
            (pausedElapsed(data, now) - rule.optLong("paused_elapsed")).coerceAtLeast(0)).coerceAtLeast(0) / 1000.0
        val anchor = rule.getDouble("anchor_value")
        val value = when (rule.getString("curve")) {
            "natural" -> natural(anchor, elapsed)
            "hold" -> anchor
            "linear" -> anchor + (rule.getDouble("target") - anchor) *
                (elapsed / rule.getDouble("seconds")).coerceAtMost(1.0)
            "exponential" -> rule.getDouble("target") + (anchor - rule.getDouble("target")) *
                exp(-elapsed / rule.getDouble("seconds"))
            else -> error("Unknown persisted time rule")
        }
        return JSONObject().put("intensity", value).put("at", now).put("rule_version", saved.getInt("version"))
            .put("union_id", saved.getString("parent")).put("member_version", version)
            .put("basis", "subject_adopted_time_rule").put("curve", rule.getString("curve"))
            .put("not_a_new_episode", true).put("not_resolved_by_time", true)
    }

    private fun target(data: JSONObject, ref: String, now: Long): Pair<Int, Double> {
        val id = ref.substringAfter(':')
        if (ref.startsWith("foundation:")) {
            require(InnerFoundations.find(id)?.family == "drive") {
                "时间作用只适用于基础驱力，不能修改求生欲或稳定内容"
            }
            val member = InnerFoundations.member(data, id, now)
            return member.getInt("version") to member.getDouble("current_value")
        }
        require(ref.startsWith("episode:")) { "时间作用不能绕过稳定内容采用流程" }
        val row = requireNotNull(CategoryEvents.row(data, "episode", id))
        val content = row.getJSONObject("content")
        val categoryId = content.getString("category_ref")
        val category = InnerFoundations.definition(categoryId)
            ?: requireNotNull(InnerStructure.record(data, categoryId)).getJSONObject("content")
        require(category.optString("semantic_rule") !in setOf("survival", "mortality") &&
            categoryId !in setOf("foundation:need:survival", "foundation:need:mortality")) {
            "求生欲与终结认识不由联合时间规则改变"
        }
        require(category.getString("family") in setOf("drive", "need", "desire", "emotion", "feeling")) {
            "持续情感和稳定内容不由联合统一衰减"
        }
        val view = CategoryEvents.timeView(data, row, now)
        val value = view.optJSONObject("time_projection")?.opt("intensity") ?: content.opt("intensity")
        require(value is Number && value.toDouble().isFinite()) { "未知强度不能凭空生成数值时间曲线" }
        return row.getInt("version") to value.toDouble()
    }

    fun adopt(data: JSONObject, frame: InternalStateFrame, unionId: String, oldUnion: JSONObject?,
              action: JSONObject, sources: JSONObject, now: Long) {
        val previous = oldUnion?.getJSONObject("content")?.getJSONArray("effects") ?: JSONArray()
        val incoming = action.getJSONArray("effects")
        val rules = linkedMapOf<String, JSONObject>()
        for (i in 0 until incoming.length()) {
            val effect = incoming.getJSONObject(i)
            if (!effect.has("time_rule")) continue
            require(action.getString("status") == "active") { "结束的联合不能再安排时间作用" }
            require(rules.put(effect.getString("to"), effect) == null) { "同一成员不能收到两条相互覆盖的时间规则" }
        }
        fun append(ref: String, body: JSONObject) {
            body.put("paused_elapsed", pausedElapsed(data, now))
            val current = profile(data, ref)
            CategoryEvents.append(data, frame, JSONObject().put("storage_kind", STORAGE).put("id", ref)
                .put("parent", unionId).put("version", Math.addExact(current?.getInt("version") ?: 0, 1))
                .put("content", body).put("owner", frame.roleId).put("frame_id", frame.id)
                .put("source_chat", frame.chatId).put("at", now).put("source", "subject_union_time_adoption"))
        }
        // Releasing a rule continues from the actual value at this point, never its old anchor.
        for (i in 0 until previous.length()) {
            val effect = previous.getJSONObject(i)
            val ref = effect.getString("to")
            if (!effect.has("time_rule") || ref in rules) continue
            val saved = profile(data, ref) ?: continue
            if (saved.getString("parent") != unionId) continue
            val currentVersion = if (ref.startsWith("foundation:"))
                InnerFoundations.currentVersions(data)[ref.substringAfter(':')]
            else CategoryEvents.row(data, "episode", ref.substringAfter(':'))?.getInt("version")
            if (saved.getJSONObject("content").getInt("member_version") != currentVersion) continue
            val (version, value) = target(data, ref, now)
            append(ref, JSONObject().put("curve", "natural").put("member_version", version)
                .put("anchor_value", value).put("anchor_at", now).put("status", "released")
                .put("source_bindings", sources))
        }
        for ((ref, effect) in rules) {
            val (version, value) = target(data, ref, now)
            val old = profile(data, ref)
            require(old == null || old.getString("parent") == unionId ||
                old.getJSONObject("content").getInt("member_version") != version ||
                old.getJSONObject("content").getString("status") == "released") {
                "成员已有另一联合的有效时间规则；须明确结束或修订，不能叠加计入"
            }
            val rule = effect.getJSONObject("time_rule")
            require(rule.keys().asSequence().all { it in setOf("curve", "target", "seconds") })
            require(rule.opt("curve") is String && rule.getString("curve") in setOf("hold", "linear", "exponential"))
            if (rule.getString("curve") == "hold") require(!rule.has("target") && !rule.has("seconds"))
            else {
                val goal = rule.get("target"); val seconds = rule.get("seconds")
                require(goal is Number && goal.toDouble().isFinite() && goal.toDouble() >= 0 &&
                    (!ref.startsWith("foundation:") || goal.toDouble() <= 1.0))
                require(seconds is Number && seconds.toDouble().isFinite() && seconds.toDouble() > 0)
            }
            append(ref, JSONObject(rule.toString()).put("member_version", version)
                .put("anchor_value", value).put("anchor_at", now).put("status", "active")
                .put("from", effect.getString("from")).put("meaning", effect.getString("meaning"))
                .put("source_bindings", sources))
        }
    }
}
