package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Host-owned request identity; definitionVersions covers the full registry, not the attention view. */
data class InternalStateFrame(
    val id: String,
    val roleId: String,
    val chatId: String,
    val revision: Long,
    val at: Long,
    val eventIds: Set<String>,
    val definitionVersions: Map<String, Int>,
    val eventSequence: Long
)

/** Applies one subjective report atomically. This does not replay the source event or choose actions. */
object InternalStateReports {
    private val optionalFields = listOf("object_ref", "unresolved", "desire", "choice", "time_conditions")
    private val experienceFields = setOf("value", "reason", "evidence") + optionalFields
    private val editableDefinitionFields = setOf("name", "description", "kind", "time_rules")

    fun apply(data: JSONObject, frame: InternalStateFrame, report: JSONObject, now: Long): JSONObject {
        require(frame.id.isNotBlank() && frame.roleId.isNotBlank() && frame.chatId.isNotBlank())
        require(frame.revision >= 0 && frame.at >= 0 && now >= frame.at) { "状态报告时间早于本轮快照" }
        requireStateReport(frame.eventSequence >= 0 && frame.eventSequence <= data.optLong("sequence"), InternalStateReportFailure.SOURCE)
        require(report.keys().asSequence().all { it in setOf("frame_id", "updates", "create", "revise") }) { "状态报告包含未知字段" }
        requireStateReport(string(report, "frame_id", 160) == frame.id, InternalStateReportFailure.FRAME)
        val reportDigest = digest(report)
        val frameDigest = digest(JSONObject().put("id", frame.id).put("role", frame.roleId).put("chat", frame.chatId)
            .put("revision", frame.revision).put("at", frame.at).put("events", JSONArray(frame.eventIds.sorted()))
            .put("definitions", JSONObject(frame.definitionVersions)).put("event_sequence", frame.eventSequence))

        // A transport retry is identified before checking the newer semantic revision. A changed
        // report with the same frame is a conflicting write, not another interpretation to append.
        val applied = InternalStateEngine.rows(data, "semantic_reports").firstOrNull { it.getString("frame_id") == frame.id }
        if (applied != null) {
            requireStateReport(applied.getString("role_id") == frame.roleId && applied.getString("chat_id") == frame.chatId &&
                applied.getLong("frame_revision") == frame.revision && applied.getLong("frame_at") == frame.at, InternalStateReportFailure.FRAME)
            requireStateReport(applied.getString("digest") == reportDigest, InternalStateReportFailure.CONFLICT)
            requireStateReport(applied.getString("frame_digest") == frameDigest, InternalStateReportFailure.SOURCE)
            return JSONObject(applied.getJSONObject("receipt").toString())
        }

        val revision = InternalStateEngine.semanticRevision(data)
        requireStateReport(revision == frame.revision && revision < Long.MAX_VALUE, InternalStateReportFailure.REVISION)
        requireStateReport(frame.eventIds.isNotEmpty(), InternalStateReportFailure.SOURCE)
        val events = InternalStateEngine.rows(data, "events").associateBy { it.getString("id") }
        frame.eventIds.forEach { id ->
            val event = events[id] ?: throw InternalStateReportRejected(InternalStateReportFailure.SOURCE)
            requireStateReport(event.getLong("at") <= frame.at, InternalStateReportFailure.SOURCE)
            requireStateReport(event.getLong("sequence") <= frame.eventSequence, InternalStateReportFailure.SOURCE)
        }

        // All validation and writes happen on a detached copy. Rejection cannot leave a definition
        // without its initial experience or alter an unreported dimension's value/time anchor.
        val next = JSONObject(data.toString())
        val updates = objects(report, "updates")
        val creates = objects(report, "create")
        val revisions = if (report.has("revise")) objects(report, "revise") else emptyList()
        val definitions = InternalStateEngine.definitions(next).associateBy { it.getString("id") }
        val revisedDefinitions = linkedMapOf<String, JSONObject>()
        revisions.forEach { revision ->
            require(revision.keys().asSequence().all { it in setOf("id", "expected_version", "changes", "reason", "evidence") }) {
                "规则修订包含未知字段"
            }
            val id = string(revision, "id", 64)
            require(id !in revisedDefinitions) { "同一报告不能重复修订同一维度" }
            val old = requireNotNull(definitions[id]) { "待修订维度不存在" }
            require(old.getBoolean("active")) { "待修订维度已停用" }
            val expected = revision.get("expected_version")
            requireStateReport(expected is Number && expected.toDouble() == old.getInt("version").toDouble() &&
                frame.definitionVersions[id] == old.getInt("version"), InternalStateReportFailure.DEFINITION)
            require(old.getInt("version") < Int.MAX_VALUE) { "维度版本已达上限" }
            string(revision, "reason", 1500)
            evidence(revision, frame)
            val patch = revision.getJSONObject("changes")
            require(patch.length() > 0 && patch.keys().asSequence().all { it in editableDefinitionFields }) {
                "报告修订只能更改 name、description、kind、time_rules；ID 和作用域保持不变"
            }
            val definition = JSONObject(old.toString()).also {
                it.remove("active"); it.remove("version")
            }
            patch.keys().forEach { key -> definition.put(key, patch.get(key)) }
            if (patch.has("time_rules")) {
                // Publishing explicit time rules switches the reducer to semantic events and
                // conditional timing; retained legacy fields become inactive historical data.
                InternalStateTimeRules.requireDefined(definition)
            }
            InternalStateEngine.validate(definition)
            revisedDefinitions[id] = definition.put("active", true).put("version", old.getInt("version") + 1)
        }
        val effectiveDefinitions = definitions + revisedDefinitions
        val identifiers = mutableSetOf<String>()
        updates.forEach { update ->
            require(update.keys().asSequence().all { it == "id" || it in experienceFields })
            val id = string(update, "id", 64)
            require(identifiers.add(id)) { "同一报告不能重复写入同一维度" }
            val old = requireNotNull(definitions[id]) { "待更新维度不存在" }
            val definition = effectiveDefinitions.getValue(id)
            require(old.getBoolean("active")) { "待更新维度已停用" }
            requireStateReport(frame.definitionVersions[id] == old.getInt("version"), InternalStateReportFailure.DEFINITION)
            validateExperience(update, definition, frame, allowConditionOnly = true)
            val state = InternalStateEngine.instance(next, definition, frame.chatId, now)
            require(state.getLong("at") <= now) { "状态时间晚于本次报告" }
            validateObject(update, definition, state)
        }
        require(definitions.values.count { it.getBoolean("active") } + creates.size <= 64) { "活跃维度已达上限" }
        creates.forEach { create ->
            require(create.keys().asSequence().all { it == "definition" || it in experienceFields })
            val definition = create.getJSONObject("definition")
            InternalStateEngine.validate(definition)
            require(definition.has("kind") && definition.getString("kind") in InternalStateEngine.kinds) { "新状态必须明确类别及完整时间参数" }
            InternalStateTimeRules.requireDefined(definition)
            val id = definition.getString("id")
            require(identifiers.add(id) && id !in definitions) { "新维度 ID 已存在，不能覆盖旧状态" }
            validateExperience(create, definition, frame)
            validateObject(create, definition)
        }

        // Time may advance independently, but an absolute subjective report must not erase an
        // unseen objective mutation of the same instance. Other chats' session instances and
        // unreported dimensions remain independent; this never rebases or starts another request.
        val targets = updates.map { update ->
            val definition = definitions.getValue(update.getString("id"))
            definition.getString("id") to if (definition.getString("scope") == "global") "global" else frame.chatId
        }.toSet() + InternalStateEngine.rows(next, "instances").filter {
            it.getString("dimension") in revisedDefinitions
        }.map { it.getString("dimension") to it.getString("scope") }
        val unseenChange = events.values.any { event ->
            event.getLong("sequence") > frame.eventSequence && event.getString("source") != "time" &&
                InternalStateEngine.rows(event, "changes").any { change ->
                    (change.getString("dimension") to change.getString("scope")) in targets
                }
        }
        requireStateReport(!unseenChange, InternalStateReportFailure.CONFLICT)

        revisions.forEach { revision ->
            val id = revision.getString("id")
            val old = definitions.getValue(id)
            val definition = revisedDefinitions.getValue(id)
            val instances = InternalStateEngine.rows(next, "instances").filter { it.getString("dimension") == id }
            // A definition applies to all of its session instances. Settle each under the old
            // policy before publishing the new one, without advancing unrelated dimensions.
            instances.forEach { state ->
                val chat = if (state.getString("scope") == "global") frame.chatId else state.getString("scope")
                require(state.getLong("at") <= now) { "状态时间晚于本次报告" }
                InternalStateEngine.advanceDimension(next, old, chat, now)
                InternalStateTimeRules.clearChangedConditions(state, old, definition)
            }
            val list = next.getJSONArray("definitions")
            val index = (0 until list.length()).single { list.getJSONObject(it).getString("id") == id }
            list.put(index, definition)
            array(next, "versions").put(JSONObject().put("at", now).put("operation", "revise")
                .put("dimension", id).put("version", definition.getInt("version"))
                .put("reason", revision.getString("reason")).put("evidence", evidence(revision, frame))
                .put("before", JSONObject(old.toString())).put("after", JSONObject(definition.toString()))
                .put("changes", JSONArray()).put("frame_id", frame.id))
        }
        val changes = JSONArray()
        updates.forEach { update ->
            val definition = effectiveDefinitions.getValue(update.getString("id"))
            InternalStateEngine.advanceDimension(next, definition, frame.chatId, now)
            writeExperience(next, definition, update, frame, now, changes)
        }
        creates.forEach { create ->
            val definition = JSONObject(create.getJSONObject("definition").toString()).put("active", true).put("version", 1)
            array(next, "definitions").put(definition)
            val firstChange = writeExperience(next, definition, create, frame, now, changes)
            array(next, "versions").put(JSONObject().put("at", now).put("operation", "create")
                .put("dimension", definition.getString("id")).put("version", 1)
                .put("reason", create.getString("reason")).put("evidence", evidence(create, frame))
                .put("before", JSONObject.NULL).put("after", JSONObject(definition.toString()))
                .put("changes", JSONArray().put(JSONObject(firstChange.toString()))).put("frame_id", frame.id))
        }
        if (changes.length() > 0) {
            // This event says that the assistant reported its own experience. It never impersonates
            // another user message/tool result and does not run that event's triggers a second time.
            InternalStateEngine.record(next, "state_report:${frame.id}", "state_report", frame.chatId,
                "本轮主观状态报告", changes, now)
        }
        // A no-change acknowledgement consumes its frame without invalidating another
        // in-flight report: no semantic state has changed merely because the model stayed quiet.
        val nextRevision = if (updates.isEmpty() && creates.isEmpty() && revisions.isEmpty()) revision else revision + 1
        next.put("semantic_revision", nextRevision)
        val receipt = JSONObject().put("frame_id", frame.id).put("revision", nextRevision)
            .put("updated", JSONArray(updates.map { it.getString("id") }))
            .put("created", JSONArray(creates.map { it.getJSONObject("definition").getString("id") }))
            .put("revised", JSONArray(revisions.map { it.getString("id") }))
        array(next, "semantic_reports").put(JSONObject().put("frame_id", frame.id)
            .put("role_id", frame.roleId).put("chat_id", frame.chatId).put("frame_revision", frame.revision)
            .put("frame_at", frame.at).put("event_sequence", frame.eventSequence).put("at", now)
            .put("digest", reportDigest).put("frame_digest", frameDigest)
            .put("receipt", JSONObject(receipt.toString())))

        data.keys().asSequence().toList().forEach { data.remove(it) }
        next.keys().forEach { key -> data.put(key, next.get(key)) }
        return receipt
    }

    private fun validateExperience(experience: JSONObject, definition: JSONObject, frame: InternalStateFrame,
                                   allowConditionOnly: Boolean = false) {
        require(experience.has("value") || allowConditionOnly && experience.has("time_conditions")) {
            "状态更新必须提供 value 或 time_conditions；新状态必须提供首次 value"
        }
        if (experience.has("value")) {
            val raw = experience.get("value")
            require(raw is Number) { "状态值必须是数值" }
            val value = raw.toDouble()
            require(value.isFinite() && value in definition.getDouble("min")..definition.getDouble("max")) { "状态值超出定义范围" }
        }
        if (experience.has("time_conditions")) {
            try {
                InternalStateTimeRules.validateConditions(definition, experience.getJSONObject("time_conditions"))
            } catch (failure: IllegalArgumentException) {
                throw InternalStateReportRejected(InternalStateReportFailure.CONDITION)
            }
        }
        string(experience, "reason", 1500)
        if (experience.has("object_ref")) string(experience, "object_ref", 160)
        if (experience.has("unresolved")) require(experience.get("unresolved") is Boolean) { "unresolved 必须是布尔值" }
        listOf("desire", "choice").filter { experience.has(it) }.forEach { key ->
            require(experience.get(key) is String && experience.getString(key).length <= 1500) { "$key 必须为不超过 1500 字的文本" }
        }
        evidence(experience, frame)
    }

    private fun validateObject(experience: JSONObject, definition: JSONObject, state: JSONObject? = null) {
        val known = listOfNotNull(definition, state).filter { it.has("object_ref") }.map { it.getString("object_ref") }.distinct()
        require(known.size <= 1) { "现有状态对象不一致" }
        if (experience.has("object_ref") && known.isNotEmpty()) {
            require(experience.getString("object_ref") == known.single()) { "明确对象不能无声更换，请为不同对象建立独立状态" }
        }
    }

    private fun writeExperience(data: JSONObject, definition: JSONObject, experience: JSONObject,
                                frame: InternalStateFrame, now: Long, changes: JSONArray): JSONObject {
        val state = InternalStateEngine.instance(data, definition, frame.chatId, now)
        val before = state.getDouble("value")
        val fromAt = state.getLong("at")
        val value = if (experience.has("value")) experience.getDouble("value") else before
        InternalStateEngine.change(definition, state, value, now)
        val fieldVersions = state.optJSONObject("semantic_versions") ?: JSONObject().also { state.put("semantic_versions", it) }
        fun subjective(key: String, value: Any) {
            state.put(key, value)
            fieldVersions.put(key, definition.getInt("version"))
        }
        if (definition.has("object_ref") && !state.has("object_ref")) subjective("object_ref", definition.getString("object_ref"))
        optionalFields.filter { it != "time_conditions" && experience.has(it) }.forEach { subjective(it, experience.get(it)) }
        if (experience.has("time_conditions")) {
            // Each branch retains its own origin. Re-reporting one public condition must not
            // disclose another condition inherited from a previously private definition.
            InternalStateTimeRules.writeConditions(state, definition, experience.getJSONObject("time_conditions"))
        }
        state.put("reason", experience.getString("reason")).put("evidence", evidence(experience, frame))
            .put("report_frame_id", frame.id).put("experience_at", now)
            .put("experience_source", "state_report").put("experience_event_id", "state_report:${frame.id}")
        listOf("reason", "evidence", "report_frame_id", "experience_at", "experience_source", "experience_event_id")
            .forEach { fieldVersions.put(it, definition.getInt("version")) }
        val change = JSONObject().put("dimension", definition.getString("id")).put("scope", state.getString("scope"))
            .put("version", definition.getInt("version")).put("before", before).put("after", value).put("from_at", fromAt)
        changes.put(change)
        val record = JSONObject(change.toString()).put("frame_id", frame.id).put("at", now).put("value", value)
            .put("source", "state_report").put("event_id", "state_report:${frame.id}")
            .put("reason", state.getString("reason")).put("evidence", JSONArray(state.getJSONArray("evidence").toString()))
            .put("field_versions", JSONObject(fieldVersions.toString()))
        optionalFields.filter { state.has(it) }.forEach { record.put(it, state.get(it)) }
        state.optJSONObject("time_condition_versions")?.let {
            record.put("time_condition_versions", JSONObject(it.toString()))
        }
        array(data, "experiences").put(record)
        return change
    }

    private fun evidence(experience: JSONObject, frame: InternalStateFrame): JSONArray {
        if (!experience.has("evidence")) return JSONArray(frame.eventIds.sorted())
        val evidence = experience.getJSONArray("evidence")
        requireStateReport(evidence.length() > 0, InternalStateReportFailure.SOURCE)
        val ids = (0 until evidence.length()).map { index ->
            require(evidence.get(index) is String)
            evidence.getString(index)
        }
        requireStateReport(ids.distinct().size == ids.size && ids.all { it in frame.eventIds }, InternalStateReportFailure.SOURCE)
        return JSONArray(ids)
    }

    private fun string(value: JSONObject, key: String, limit: Int): String {
        require(value.get(key) is String) { "$key 必须是文本" }
        return value.getString(key).also { require(it.isNotBlank() && it.length <= limit) { "$key 不能为空，最多 $limit 字" } }
    }

    private fun objects(value: JSONObject, key: String): List<JSONObject> {
        val array = value.getJSONArray(key)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun array(data: JSONObject, key: String): JSONArray =
        data.optJSONArray(key) ?: JSONArray().also { data.put(key, it) }

    private fun digest(report: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(report).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> error("状态报告包含非 JSON 值")
    }
}
