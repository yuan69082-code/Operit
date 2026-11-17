package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Private generated experience, adopted by the existing report transaction. There is no
 * timer, device-state inference, public message, external memory write or model call here. */
internal object SubjectRestDreams {
    val kinds = setOf("rest", "dream", "dream_understanding")
    val privateKinds = kinds + setOf("dream_process", "dream_step")
    /** Provenance IDs/types, never psychological keywords. Derived descriptions inherit
     * private provenance; publishing them requires a separate normal expression decision. */
    fun privateProvenance(value: Any?): Boolean = when (value) {
        is JSONObject -> value.optBoolean("subject_private_material") ||
            value.optString("type") in setOf("dream_not_reality", "subject_rest", "subject_dream_understanding") ||
            value.optString("storage_kind") in privateKinds ||
            value.keys().asSequence().any { privateProvenance(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { privateProvenance(value.opt(it)) }
        is String -> privateKinds.any { value.startsWith("material:$it:") } || value.startsWith("material:dream_index:")
        else -> false
    }
    fun publicMaterial(value: JSONObject): JSONObject {
        fun scrub(v: Any): Any = when (v) {
            is JSONArray -> JSONArray((0 until v.length()).map { scrub(v.get(it)) })
            is JSONObject -> {
                val direct = v.optBoolean("subject_private_material") || v.optString("storage_kind") in privateKinds ||
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

    private fun text(row: JSONObject, key: String, max: Int): String = SubjectContracts.text(row, key, max)
    private fun bindings(data: JSONObject, frame: InternalStateFrame, refs: JSONArray): JSONObject {
        val result = JSONObject()
        SubjectContracts.strings(refs, path = "evidence").forEach { result.put(it, InnerStructure.source(data, frame, it)) }
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
            require(change.keys().asSequence().all { it in setOf("expected_version", "state", "meaning", "evidence",
                "mode", "until", "wake_sources", "interruption", "defer_internal", "dream_plan", "consolidation") })
            version(rest, change, frame)
            val state = text(change, "state", 10)
            require(state in setOf("resting", "awake"))
            val content = SubjectRestPolicy.adopt(change, rest?.getJSONObject("content"), now, frame.id)
                .put("meaning", text(change, "meaning", Int.MAX_VALUE))
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
            val content = JSONObject().put("title", text(dream, "title", 160)).put("content", text(dream, "content", Int.MAX_VALUE))
                .put("reality", "DREAM_NOT_REALITY").put("generated_in_frame", frame.id)
                .put("rest_version", requireNotNull(rest).getInt("version"))
                .put("source_bindings", bindings(data, frame, dream.getJSONArray("sources")))
            // Empty sources are valid: a generated dream has identity, not invented reality evidence.
            append(data, frame, "dream", id, 1, content, now)
        }
        if (report.has("dream_understanding")) {
            require(rest?.getJSONObject("content")?.getString("state") == "awake") { "醒后理解须有主体已采用的醒来选择" }
            val changes = report.getJSONArray("dream_understanding")
            val ids = mutableSetOf<String>()
            val reviews = linkedMapOf<String, JSONObject>()
            for (i in 0 until changes.length()) {
                val change = changes.getJSONObject(i)
                require(change.keys().asSequence().all { it in setOf("id", "expected_version", "dream_source", "meaning", "feeling", "objects", "evidence") + DreamShelf.reviewFields })
                val id = text(change, "id", 64)
                require(ids.add(id))
                val old = CategoryEvents.row(data, "dream_understanding", id)
                version(old, change, frame)
                val sourceId = text(change, "dream_source", 200)
                val source = JSONObject(requireNotNull(frame.materialSources[sourceId]))
                require(source.getString("type") == "dream_not_reality" && source.getString("owner") == frame.roleId)
                val dreamId = source.getJSONObject("locator").getString("id")
                val kind = source.getJSONObject("locator").getString("kind")
                require(kind in setOf("dream", "dream_process"))
                val original = requireNotNull(CategoryEvents.row(data, kind, dreamId))
                require(original.getInt("version") == source.getJSONObject("locator").getInt("version"))
                if (kind == "dream_process") require(original.optString("status") !in setOf("running", "resume_authorized")) { "运行中的梦暂不归档，先结束或中断后再理解" }
                val evidence = change.getJSONArray("evidence")
                require(sourceId in SubjectContracts.strings(evidence, path = "evidence"))
                SubjectContracts.strings(SubjectContracts.array(change, "objects"), path = "objects").forEach { require(it in frame.innerObjects) }
                val content = JSONObject(change.toString()).apply { remove("expected_version") }
                    .put("meaning", text(change, "meaning", Int.MAX_VALUE)).put("feeling", text(change, "feeling", Int.MAX_VALUE))
                    .put("dream_id", dreamId).put("dream_version", original.getInt("version"))
                    .put("reality", "WAKING_INTERPRETATION_OF_DREAM_NOT_EXTERNAL_FACT")
                    .put("source_bindings", bindings(data, frame, evidence))
                append(data, frame, "dream_understanding", id, (old?.getInt("version") ?: 0) + 1, content, now)
                DreamShelf.review(original, change, now)?.let { review ->
                    reviews["$kind:$dreamId"] = JSONObject(original.toString()).put("dream_review", review)
                        .put("version", original.getInt("version") + 1).put("frame_id", frame.id)
                }
            }
            reviews.values.forEach { CategoryEvents.append(data, frame, it) }
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
    fun materials(data: JSONObject, input: String = "", chat: String = ""): List<JSONObject> =
        listOfNotNull(CategoryEvents.row(data, "rest", "current")?.let(::material)) +
            SubjectRecall.selected(data, "dream", input, chat, data.optLong("material_query_cursor") * 2).take(2).map { DreamShelf.index(it) } +
            SubjectRecall.selected(data, "dream_understanding", input, chat, data.optLong("material_query_cursor") * 2).take(2).map(::material)

    val instructions = """
        [私域休息、梦与醒后承接]
        休息与设备锁屏、断网、进程停止不同。程序恢复不代表你主动醒来。没有有效触发就安静，不需为了持续存在安排空转；自己的具体预约仍可用原schedule。休息不清除愿望、关系或未确认结果，也不统一拒绝交流。
        本助手私聊或已授权后台轮末报告可选 rest:{expected_version,state:"resting|awake",meaning,evidence:[]}；无旧记录expected_version=0，否则先读当前版本。它是你本轮形成的选择，不冒称后台一直在思考。
        resting可选mode="quiet"（没有时长的安静休息）或"sleep"（睡眠，须until毫秒时间或wake_sources结束条件）。until到期只执行你已安排的续接，不凭空推断已醒。wake_sources是愿意打断的来源数组：user_message/study/phone/software/task/notification；interruption="buffer|judge|wake"表示暂存/有界判断/按原安排醒来，未选中的普通事件只保存。defer_internal=true允许把自己的内部预约留待醒后重新安排，原截止不改。醒来用state=awake，延长须新报告与新结束条件。可选dream_plan:{steps:1..6,theme_mode:"single|multiple|random",theme_count?}安排一次有参与的梦。安排时先选择单主题、多主题或交给抽签；multiple可给theme_count=2..7，省略时随机2..3。系统独立抽整体短/中/长和每段停留/流动/跳跃节奏，实际组数不超过steps预算；节奏不绑定某个主题。再随机抽取美梦、恐惧、混合、荒诞、日常、冒险、春梦中的类型，独立抽资料为主或状态为主；素材打乱，无来源先后。类型可在经历中变化或维持，不强制按顺序用完。省略theme_mode表示交给抽签。旧theme/tone/form/recall仍保留原记录，新梦不把这些旧字段当指定剧情。每组环境与参与各一次调用，辅助模型只负责环境，主体使用自己的模型参与。consolidation是旧整理范围，保留到醒后再决定；睡眠中不执行整理。清醒回顾整理用companion_life free_status菜单与free_plan安排，可先整理后睡觉。都非必填，不因每次休息自动安排。
        已选择resting或本轮同时采用resting时，可选 dream:{title,content,sources:[]}，只保存本轮实际生成的梦。sources可空，梦可自由、荒诞、混合或想象，不要求现实情节证据；不要为了休息凑梦。每轮最多一段，重试不重新生成。梦中行为不是任何人的现实行为，不作为现实指控或已证事实。
        选择awake后可选 dream_understanding:[{id,expected_version,dream_source,meaning,feeling,objects:[],evidence:[dream_source]}]；引用本轮实际读取的material:dream:...来源。余韵可用正常episodes/foundation/inner/unions承接，参与后续判断和自主选择，不是生成成功就机械加减数值。需要延续到中午或几天，可明确采用联合time_rule的hold/linear/exponential；不统一按两小时归零，保持多久也可以暂不确定。感受可以真实存在，剧情仍是梦；新理解可联系对象，不能编造其现实行为。以后可重新理解同一梦，读到不等于已理解、不自动叠加余波。既有updates/inner/episodes/unions可引用这些实际材料形成变化；人格、关系和稳定认识仍须原采用规则，不因梦直接越过约束。
        dream_understanding可附retention:"active|archive"、retain_until?:未来毫秒或0、review_at?:未来毫秒或0、keywords?:字符串数组。归档或自选保留期到期后只退出普通轮换，关联检索或按ID仍可取回；复查时间不是唤醒预约。先实际查阅梦，再决定整理，不因到期删除原文、已形成感受或认识。
        用companion_internal_state get section=rest|dream|dream_understanding|dream_process按id或offset查询；普通请求只选少量日期/关键词索引，不自动带全部梦或全文。索引不是已读经历；按ID和dream_step取回实际内容后再解释。dream_step必须id=参与式dream_process的ID，再用offset分页读取步骤，section=category_versions id=种类:ID回看过程。get section=dream的participatory_processes同时提供参与式梦索引，不会因不在旧梦列表就丢失。参与式梦过程的来源为material:dream_process:...，同样只能作为梦的来源。梦与理解属于私域，不默认表达、写外部记忆或进入群聊。材料未选入本轮不代表遗忘。新记录在轮末报告采用后才供后续请求读取；普通报告不新增调用，无周期造梦。只有明确采用dream_plan后才运行已安排的额外有界调用，中断步骤不会自动重试。refused/invalid_response是模型未参与或结果格式不符，不是梦中经历；原始响应保留供核对。只对已生成的环境或主体参与分别形成理解。
    """.trimIndent()
}
