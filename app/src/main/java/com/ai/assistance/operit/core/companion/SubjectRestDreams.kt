package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Private generated experience, adopted by the existing report transaction. There is no
 * timer, device-state inference, public message, external memory write or model call here. */
internal object SubjectRestDreams {
    val kinds = setOf("rest", "dream", "dream_understanding")
    /** Provenance IDs/types, never psychological keywords. Derived descriptions inherit
     * private provenance; publishing them requires a separate normal expression decision. */
    fun privateProvenance(value: Any?): Boolean = when (value) {
        is JSONObject -> value.optBoolean("subject_private_material") ||
            value.optString("type") in setOf("dream_not_reality", "subject_rest", "subject_dream_understanding") ||
            value.optString("storage_kind") in kinds ||
            value.keys().asSequence().any { privateProvenance(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { privateProvenance(value.opt(it)) }
        is String -> value.startsWith("material:dream:") || value.startsWith("material:dream_understanding:") || value.startsWith("material:rest:")
        else -> false
    }
    fun publicMaterial(value: JSONObject): JSONObject {
        fun scrub(v: Any): Any = when (v) {
            is JSONArray -> JSONArray((0 until v.length()).map { scrub(v.get(it)) })
            is JSONObject -> {
                val direct = v.optBoolean("subject_private_material") || v.optString("storage_kind") in kinds ||
                    listOf("evidence", "bindings", "source_bindings", "material_bindings", "before", "after")
                        .any { privateProvenance(v.opt(it)) }
                if (direct) JSONObject().put("private_material_omitted", true).also { out ->
                    for (key in listOf("id", "version", "at")) if (v.has(key)) out.put(key, v.get(key))
                }
                else JSONObject().also { out -> v.keys().forEach { out.put(it, scrub(v.get(it))) } }
            }
            else -> v
        }
        return scrub(value) as JSONObject
    }
    fun requested(report: JSONObject) = report.has("rest") || report.has("dream") || report.has("dream_understanding")

    private fun text(row: JSONObject, key: String, max: Int): String {
        require(row.get(key) is String)
        return row.getString(key).also { require(it.isNotBlank() && it.length <= max) }
    }
    private fun bindings(data: JSONObject, frame: InternalStateFrame, refs: JSONArray): JSONObject {
        val result = JSONObject()
        CategoryEvents.strings(refs, 8).forEach { result.put(it, InnerStructure.source(data, frame, it)) }
        return result
    }
    private fun version(old: JSONObject?, action: JSONObject, frame: InternalStateFrame) {
        val expected = old?.getInt("version") ?: 0
        require(action.get("expected_version") is Number && action.getDouble("expected_version") == expected.toDouble())
        if (old != null) require(frame.materialSources.values.any {
            val source = JSONObject(it)
            val locator = source.optJSONObject("locator")
            source.optString("owner") == frame.roleId && locator?.optString("kind") == old.getString("storage_kind") &&
                locator.optString("id") == old.getString("id") && locator.optInt("version") == expected
        }) { "须先读取当前私域记录版本" }
    }
    private fun append(data: JSONObject, frame: InternalStateFrame, kind: String, id: String,
                       version: Int, content: JSONObject, now: Long) {
        CategoryEvents.append(data, frame, JSONObject().put("storage_kind", kind).put("id", id)
            .put("parent", "private").put("owner", frame.roleId).put("source_chat", frame.chatId)
            .put("version", version).put("frame_id", frame.id).put("at", now)
            .put("source", "subject_private_report").put("content", content))
    }

    fun apply(data: JSONObject, frame: InternalStateFrame, report: JSONObject, now: Long): Boolean {
        if (!requested(report)) return false
        var rest = CategoryEvents.row(data, "rest", "current")
        if (report.has("rest")) {
            val change = report.getJSONObject("rest")
            require(change.keys().asSequence().all { it in setOf("expected_version", "state", "meaning", "evidence") })
            version(rest, change, frame)
            val state = text(change, "state", 10)
            require(state in setOf("resting", "awake"))
            val content = JSONObject().put("state", state).put("meaning", text(change, "meaning", 1000))
                .put("source_bindings", bindings(data, frame, change.getJSONArray("evidence")))
                .put("device_state_inference", false)
            append(data, frame, "rest", "current", (rest?.getInt("version") ?: 0) + 1, content, now)
            rest = CategoryEvents.row(data, "rest", "current")
        }
        if (report.has("dream")) {
            require(rest?.getJSONObject("content")?.getString("state") == "resting") { "梦须来自主体已采用的休息选择" }
            val dream = report.getJSONObject("dream")
            require(dream.keys().asSequence().all { it in setOf("title", "content", "sources") })
            val id = "dream-" + InnerStructure.hash(frame.id)
            require(CategoryEvents.row(data, "dream", id) == null)
            val content = JSONObject().put("title", text(dream, "title", 160)).put("content", text(dream, "content", 12000))
                .put("reality", "DREAM_NOT_REALITY").put("generated_in_frame", frame.id)
                .put("rest_version", requireNotNull(rest).getInt("version"))
                .put("source_bindings", bindings(data, frame, dream.getJSONArray("sources")))
            // Empty sources are valid: a generated dream has identity, not invented reality evidence.
            append(data, frame, "dream", id, 1, content, now)
        }
        if (report.has("dream_understanding")) {
            require(rest?.getJSONObject("content")?.getString("state") == "awake") { "醒后理解须有主体已采用的醒来选择" }
            val changes = report.getJSONArray("dream_understanding")
            require(changes.length() in 1..4)
            val ids = mutableSetOf<String>()
            for (i in 0 until changes.length()) {
                val change = changes.getJSONObject(i)
                require(change.keys().asSequence().all { it in setOf("id", "expected_version", "dream_source", "meaning", "feeling", "objects", "evidence") })
                val id = text(change, "id", 64)
                require(ids.add(id))
                val old = CategoryEvents.row(data, "dream_understanding", id)
                version(old, change, frame)
                val sourceId = text(change, "dream_source", 200)
                val source = JSONObject(requireNotNull(frame.materialSources[sourceId]))
                require(source.getString("type") == "dream_not_reality" && source.getString("owner") == frame.roleId)
                val dreamId = source.getJSONObject("locator").getString("id")
                val original = requireNotNull(CategoryEvents.row(data, "dream", dreamId))
                require(original.getInt("version") == source.getJSONObject("locator").getInt("version"))
                val evidence = change.getJSONArray("evidence")
                require(sourceId in CategoryEvents.strings(evidence, 8))
                CategoryEvents.strings(change.getJSONArray("objects"), 12).forEach { require(it in frame.innerObjects) }
                val content = JSONObject(change.toString()).apply { remove("expected_version") }
                    .put("meaning", text(change, "meaning", 1500)).put("feeling", text(change, "feeling", 1000))
                    .put("dream_id", dreamId).put("dream_version", original.getInt("version"))
                    .put("reality", "WAKING_INTERPRETATION_OF_DREAM_NOT_EXTERNAL_FACT")
                    .put("source_bindings", bindings(data, frame, evidence))
                append(data, frame, "dream_understanding", id, (old?.getInt("version") ?: 0) + 1, content, now)
            }
        }
        return true
    }

    fun material(row: JSONObject): JSONObject {
        val kind = row.getString("storage_kind")
        require(kind in kinds)
        val content = JSONObject(row.getJSONObject("content").toString()).apply { remove("source_bindings") }
        if (kind == "dream") {
            val original = content.getString("content")
            content.put("content", original.take(600)).put("excerpt", original.length > 600)
        }
        return JSONObject().put("id", "material:$kind:${row.getString("id")}:${row.getInt("version")}")
            .put("type", if (kind == "dream") "dream_not_reality" else "subject_$kind")
            .put("owner", row.getString("owner")).put("author", row.getString("owner")).put("at", row.getLong("at"))
            .put("status", "retrieved").put("not_new_occurrence", true).put("private", true)
            .put("text", content.toString()).put("locator", JSONObject().put("kind", kind)
                .put("id", row.getString("id")).put("version", row.getInt("version")))
            .put("retrieval", "companion_internal_state get section=$kind id=${row.getString("id")}")
    }
    fun materials(data: JSONObject): List<JSONObject> = kinds.flatMap { kind ->
        val rows = CategoryEvents.page(data, kind, 0, "private").getJSONArray("items")
        (0 until rows.length()).map { material(rows.getJSONObject(it)) }
    }

    val instructions = """
        [私域休息、梦与醒后承接]
        休息与设备锁屏、断网、进程停止不同。程序恢复不代表你主动醒来。没有有效触发就安静，不需为了持续存在安排空转；自己的具体预约仍可用原schedule。休息不清除愿望、关系或未确认结果，也不统一拒绝交流。
        本助手私聊或已授权后台轮末报告可选 rest:{expected_version,state:"resting|awake",meaning,evidence:[]}；无旧记录expected_version=0，否则先读当前版本。它是你本轮形成的选择，不冒称后台一直在思考。
        已选择resting或本轮同时采用resting时，可选 dream:{title,content,sources:[]}，只保存本轮实际生成的梦。sources可空，梦可自由、荒诞、混合或想象，不要求现实情节证据；不要为了休息凑梦。每轮最多一段，重试不重新生成。梦中行为不是任何人的现实行为，不作为现实指控或已证事实。
        选择awake后可选 dream_understanding:[{id,expected_version,dream_source,meaning,feeling,objects:[],evidence:[dream_source]}]；引用本轮实际读取的material:dream:...来源。感受可以真实存在，剧情仍是梦；新理解可联系对象，不能编造其现实行为。以后可重新理解同一梦，读到不等于已理解、不自动叠加余波。既有updates/inner/episodes/unions可引用这些实际材料形成变化；人格、关系和稳定认识仍须原采用规则，不因梦直接越过约束。
        用companion_internal_state get section=rest|dream|dream_understanding id=ID或offset查询，section=category_versions id=种类:ID回看过程。梦与理解属于私域，不默认表达、写外部记忆或进入群聊。材料未选入本轮不代表遗忘。新记录在轮末报告采用后才供后续请求读取，无专门周期造梦或额外模型调用。
    """.trimIndent()
}
