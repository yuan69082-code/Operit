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
    private val optionalFields = listOf("object_ref", "unresolved", "desire", "choice")
    private val experienceFields = setOf("value", "reason", "evidence") + optionalFields

    fun apply(data: JSONObject, frame: InternalStateFrame, report: JSONObject, now: Long): JSONObject {
        require(frame.id.isNotBlank() && frame.roleId.isNotBlank() && frame.chatId.isNotBlank())
        require(frame.revision >= 0 && frame.at >= 0 && now >= frame.at) { "状态报告时间早于本轮快照" }
        require(frame.eventSequence >= 0 && frame.eventSequence <= data.optLong("sequence")) { "本轮事件边界无效" }
        require(report.keys().asSequence().all { it in setOf("frame_id", "updates", "create") }) { "状态报告包含未知字段" }
        require(string(report, "frame_id", 160) == frame.id) { "状态报告不属于当前请求" }
        val reportDigest = digest(report)
        val frameDigest = digest(JSONObject().put("id", frame.id).put("role", frame.roleId).put("chat", frame.chatId)
            .put("revision", frame.revision).put("at", frame.at).put("events", JSONArray(frame.eventIds.sorted()))
            .put("definitions", JSONObject(frame.definitionVersions)).put("event_sequence", frame.eventSequence))

        // A transport retry is identified before checking the newer semantic revision. A changed
        // report with the same frame is a conflicting write, not another interpretation to append.
        val applied = InternalStateEngine.rows(data, "semantic_reports").firstOrNull { it.getString("frame_id") == frame.id }
        if (applied != null) {
            require(applied.getString("role_id") == frame.roleId && applied.getString("chat_id") == frame.chatId &&
                applied.getLong("frame_revision") == frame.revision && applied.getLong("frame_at") == frame.at) { "状态报告请求身份不一致" }
            require(applied.getString("digest") == reportDigest) { "同一请求已提交了不同的状态报告" }
            require(applied.getString("frame_digest") == frameDigest) { "本轮来源或定义名录已被替换" }
            return JSONObject(applied.getJSONObject("receipt").toString())
        }

        val revision = InternalStateEngine.semanticRevision(data)
        require(revision == frame.revision && revision < Long.MAX_VALUE) { "内部状态已变化，旧快照不能覆盖新报告" }
        require(frame.eventIds.isNotEmpty()) { "本轮缺少真实来源事件" }
        val events = InternalStateEngine.rows(data, "events").associateBy { it.getString("id") }
        frame.eventIds.forEach { id ->
            val event = requireNotNull(events[id]) { "本轮来源事件不存在" }
            require(event.getLong("at") <= frame.at) { "来源事件晚于本轮快照" }
            require(event.getLong("sequence") <= frame.eventSequence) { "来源事件超出本轮已观察边界" }
        }

        // All validation and writes happen on a detached copy. Rejection cannot leave a definition
        // without its initial experience or alter an unreported dimension's value/time anchor.
        val next = JSONObject(data.toString())
        val updates = objects(report, "updates")
        val creates = objects(report, "create")
        val definitions = InternalStateEngine.definitions(next).associateBy { it.getString("id") }
        val identifiers = mutableSetOf<String>()
        updates.forEach { update ->
            require(update.keys().asSequence().all { it == "id" || it in experienceFields })
            val id = string(update, "id", 64)
            require(identifiers.add(id)) { "同一报告不能重复写入同一维度" }
            val definition = requireNotNull(definitions[id]) { "待更新维度不存在" }
            require(definition.getBoolean("active")) { "待更新维度已停用" }
            require(frame.definitionVersions[id] == definition.getInt("version")) { "维度定义已变化或不属于本轮完整名录" }
            validateExperience(update, definition, frame)
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
        }.toSet()
        val unseenChange = events.values.any { event ->
            event.getLong("sequence") > frame.eventSequence && event.getString("source") != "time" &&
                InternalStateEngine.rows(event, "changes").any { change ->
                    (change.getString("dimension") to change.getString("scope")) in targets
                }
        }
        require(!unseenChange) { "本轮后目标状态已有新的客观变化；保留新值，下一业务轮重新观察" }

        val changes = JSONArray()
        updates.forEach { update ->
            val definition = definitions.getValue(update.getString("id"))
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
        val nextRevision = if (updates.isEmpty() && creates.isEmpty()) revision else revision + 1
        next.put("semantic_revision", nextRevision)
        val receipt = JSONObject().put("frame_id", frame.id).put("revision", nextRevision)
            .put("updated", JSONArray(updates.map { it.getString("id") }))
            .put("created", JSONArray(creates.map { it.getJSONObject("definition").getString("id") }))
        array(next, "semantic_reports").put(JSONObject().put("frame_id", frame.id)
            .put("role_id", frame.roleId).put("chat_id", frame.chatId).put("frame_revision", frame.revision)
            .put("frame_at", frame.at).put("event_sequence", frame.eventSequence).put("at", now)
            .put("digest", reportDigest).put("frame_digest", frameDigest)
            .put("receipt", JSONObject(receipt.toString())))

        data.keys().asSequence().toList().forEach { data.remove(it) }
        next.keys().forEach { key -> data.put(key, next.get(key)) }
        return receipt
    }

    private fun validateExperience(experience: JSONObject, definition: JSONObject, frame: InternalStateFrame) {
        val raw = experience.get("value")
        require(raw is Number) { "状态值必须是数值" }
        val value = raw.toDouble()
        require(value.isFinite() && value in definition.getDouble("min")..definition.getDouble("max")) { "状态值超出定义范围" }
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
        val value = experience.getDouble("value")
        InternalStateEngine.change(definition, state, value, now)
        val fieldVersions = state.optJSONObject("semantic_versions") ?: JSONObject().also { state.put("semantic_versions", it) }
        fun subjective(key: String, value: Any) {
            state.put(key, value)
            fieldVersions.put(key, definition.getInt("version"))
        }
        if (definition.has("object_ref") && !state.has("object_ref")) subjective("object_ref", definition.getString("object_ref"))
        optionalFields.filter { experience.has(it) }.forEach { subjective(it, experience.get(it)) }
        state.put("reason", experience.getString("reason")).put("evidence", evidence(experience, frame))
            .put("report_frame_id", frame.id).put("experience_at", now)
        listOf("reason", "evidence", "report_frame_id", "experience_at").forEach { fieldVersions.put(it, definition.getInt("version")) }
        val change = JSONObject().put("dimension", definition.getString("id")).put("scope", state.getString("scope"))
            .put("version", definition.getInt("version")).put("before", before).put("after", value)
        changes.put(change)
        val record = JSONObject(change.toString()).put("frame_id", frame.id).put("at", now).put("value", value)
            .put("reason", state.getString("reason")).put("evidence", JSONArray(state.getJSONArray("evidence").toString()))
            .put("field_versions", JSONObject(fieldVersions.toString()))
        optionalFields.filter { state.has(it) }.forEach { record.put(it, state.get(it)) }
        array(data, "experiences").put(record)
        return change
    }

    private fun evidence(experience: JSONObject, frame: InternalStateFrame): JSONArray {
        if (!experience.has("evidence")) return JSONArray(frame.eventIds.sorted())
        val evidence = experience.getJSONArray("evidence")
        require(evidence.length() > 0) { "体验必须保留本轮来源" }
        val ids = (0 until evidence.length()).map { index ->
            require(evidence.get(index) is String)
            evidence.getString(index)
        }
        require(ids.distinct().size == ids.size && ids.all { it in frame.eventIds }) { "体验只能引用本轮绑定的真实来源" }
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
