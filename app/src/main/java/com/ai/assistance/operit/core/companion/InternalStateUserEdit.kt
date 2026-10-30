package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Explicit local-user edits. This entry point is not exposed as a model tool or model report. */
object InternalStateUserEdits {
    fun apply(
        data: JSONObject,
        chat: String,
        id: String,
        expectedVersion: Int,
        expectedRevision: Long,
        changes: JSONObject,
        reason: String,
        reveal: Boolean,
        now: Long,
        advanceTime: Boolean = true,
    ): JSONObject {
        require(chat.isNotBlank() && id.isNotBlank() && now >= 0)
        require(reason.isNotBlank() && reason.length <= 1500) { "修改原因不能为空，最多 1500 字" }
        require(changes.keys().asSequence().all { it in setOf("value", "kind", "time_rules") }) {
            "用户修改只接受当前值、分类和时间规则"
        }
        val revision = InternalStateEngine.semanticRevision(data)
        require(expectedRevision == revision && revision >= 0 && revision < Long.MAX_VALUE) {
            "内部状态已变化，请关闭编辑后重新打开，再确认要保存的修改"
        }

        // Validate and settle on a copy: a rejected edit must not leave half a rule revision,
        // advance a clock, or discard a concurrent model update.
        val next = JSONObject(data.toString())
        val old = requireNotNull(InternalStateEngine.definitions(next).firstOrNull { it.getString("id") == id }) {
            "状态不存在"
        }
        require(old.getBoolean("active")) { "状态已停用" }
        require(old.getInt("version") == expectedVersion) { "状态规则已变化，请关闭编辑后重新打开" }
        require(InternalStateEngine.visible(old, reveal)) { "此状态当前未授权显示，不能修改" }
        if (changes.has("value")) {
            val value = changes.get("value")
            require(value is Number && value.toDouble().isFinite() &&
                value.toDouble() in old.getDouble("min")..old.getDouble("max")) { "当前值必须是状态范围内的有限数字" }
        }
        if (changes.has("kind")) require(changes.get("kind") is String && changes.getString("kind") in InternalStateEngine.kinds) {
            "分类只能是驱力、情感、情绪或心理"
        }
        if (changes.has("time_rules")) require(changes.get("time_rules") is JSONObject) { "时间规则必须完整填写" }

        val definitionChanged = changes.has("kind") || changes.has("time_rules")
        val definition = JSONObject(old.toString())
        if (definitionChanged) {
            require(expectedVersion < Int.MAX_VALUE) { "状态规则版本已达上限" }
            definition.remove("version")
            definition.remove("active")
            listOf("kind", "time_rules").filter { changes.has(it) }.forEach { key ->
                val value = changes.get(key)
                definition.put(key, if (value is JSONObject) JSONObject(value.toString()) else value)
            }
            if (changes.has("time_rules")) InternalStateTimeRules.requireDefined(definition)
            InternalStateEngine.validate(definition)
            definition.put("active", true).put("version", expectedVersion + 1)
        }
        val affected = InternalStateEngine.rows(next, "instances").filter {
            it.getString("dimension") == id && (definitionChanged || it.getString("scope") in setOf("global", chat))
        }
        require(affected.all { it.getLong("at") <= now }) { "状态时间晚于本次修改" }
        if (advanceTime) InternalStateEngine.advanceDimension(next, old, chat, now, allScopes = definitionChanged)

        val eventId = "user_edit:${UUID.randomUUID()}"
        if (definitionChanged) {
            // Changed custom meanings lose their old confirmation; a speed/category-only edit
            // keeps the same condition and its original disclosure provenance.
            InternalStateEngine.rows(next, "instances").filter { it.getString("dimension") == id }.forEach {
                InternalStateTimeRules.clearChangedConditions(it, old, definition)
            }
            val definitions = next.getJSONArray("definitions")
            val index = (0 until definitions.length()).single { definitions.getJSONObject(it).getString("id") == id }
            definitions.put(index, definition)
            array(next, "versions").put(JSONObject().put("at", now).put("operation", "revise")
                .put("source", "user_edit").put("event_id", eventId)
                .put("dimension", id).put("version", definition.getInt("version"))
                .put("reason", reason).put("evidence", JSONArray().put(eventId))
                .put("before", JSONObject(old.toString())).put("after", JSONObject(definition.toString()))
                .put("changes", JSONArray()))
        }

        val state = InternalStateEngine.instance(next, definition, chat, now)
        val before = state.getDouble("value")
        val fromAt = state.getLong("at")
        val value = if (changes.has("value")) changes.getDouble("value") else before
        InternalStateEngine.change(definition, state, value, now)
        val fieldVersions = state.optJSONObject("semantic_versions") ?: JSONObject().also { state.put("semantic_versions", it) }
        // The reason is user-authored. Keeping the previous model frame here would falsely
        // attribute the edit to the assistant and misidentify its current experience.
        state.remove("report_frame_id")
        fieldVersions.remove("report_frame_id")
        state.put("reason", reason).put("evidence", JSONArray().put(eventId)).put("experience_at", now)
            .put("experience_source", "user_edit").put("experience_event_id", eventId)
        listOf("reason", "evidence", "experience_at", "experience_source", "experience_event_id").forEach {
            fieldVersions.put(it, definition.getInt("version"))
        }
        // A reason/category-only edit still deserves its own point; equal values are not
        // grounds for dropping the user's actual edit from the timeline.
        val change = JSONObject().put("dimension", id).put("scope", state.getString("scope"))
            .put("version", definition.getInt("version")).put("before", before).put("after", value)
            .put("from_at", fromAt).put("source", "user_edit").put("reason", reason)
        val editedFields = JSONArray(listOf("reason") + changes.keys().asSequence().toList())
        val experience = JSONObject(change.toString()).put("at", now).put("value", value)
            .put("event_id", eventId).put("edited_fields", editedFields)
            .put("evidence", JSONArray().put(eventId)).put("field_versions", JSONObject(fieldVersions.toString()))
        listOf("object_ref", "unresolved", "desire", "choice", "time_conditions").filter { state.has(it) }.forEach {
            val value = state.get(it)
            experience.put(it, if (value is JSONObject) JSONObject(value.toString()) else value)
        }
        state.optJSONObject("time_condition_versions")?.let {
            experience.put("time_condition_versions", JSONObject(it.toString()))
        }
        array(next, "experiences").put(experience)
        InternalStateEngine.record(next, eventId, "user_edit", chat, "用户修改内部状态：$reason", JSONArray().put(change), now)
        InternalStateEngine.rows(next, "events").last().put("edited_fields", JSONArray(editedFields.toString()))
        next.put("semantic_revision", revision + 1)

        // Observation may instantiate untouched states for display; keep that read projection
        // separate from the transaction's durable, target-only changes.
        val observation = InternalStateEngine.observation(JSONObject(next.toString()), chat, now, reveal)
        data.keys().asSequence().toList().forEach { data.remove(it) }
        next.keys().forEach { key -> data.put(key, next.get(key)) }
        return observation
    }

    private fun array(data: JSONObject, key: String): JSONArray =
        data.optJSONArray(key) ?: JSONArray().also { data.put(key, it) }
}
