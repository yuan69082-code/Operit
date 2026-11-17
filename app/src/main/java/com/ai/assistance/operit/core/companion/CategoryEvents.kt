package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/** Psychological episodes are interpretations of facts, never extra factual input events.
 * The host adopts validated candidates in the existing report transaction at round end.
 * Current heads and all versions live in the existing archive; only a bounded redo journal
 * lives in the subject snapshot. No time function or action dispatcher writes these values.
 */
internal object CategoryEvents {
    private val families = setOf("drive", "need", "desire", "emotion", "sentiment", "feeling")
    private fun access(data: JSONObject) = requireNotNull((data as? InternalStateDocument)?.history) { "事件读取必须经过主体存储" }
    fun row(data: JSONObject, kind: String, id: String): JSONObject? {
        val journal = data.optJSONObject("category_commit")?.getJSONArray("rows")
        if (journal != null) for (i in journal.length() - 1 downTo 0) {
            val r = journal.getJSONObject(i)
            if (r.getString("storage_kind") == kind && r.getString("id") == id) return r
        }
        return access(data).categoryRow(kind, id)
    }
    fun page(data: JSONObject, kind: String, offset: Int, parent: String? = null): JSONObject {
        val rows = access(data).categoryPage(kind, offset, 9, parent)
        // A view-only emotional time projection: old intensity and unresolved status are
        // immutable. Reading never reapplies a change or mistakes calming for forgiveness.
        val view = rows.take(8).map { record ->
            if (kind != "episode") record else timeView(data, record, System.currentTimeMillis())
        }
        return JSONObject().put("items", JSONArray(view))
            .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }
    internal fun timeView(data: JSONObject, source: JSONObject, now: Long): JSONObject {
        val content = source.optJSONObject("content") ?: return source
        val type = InnerFoundations.find(content.optString("category_ref"))
        val seconds = when (type?.key) {
            "anger" -> 12 * 3600.0
            "surprise" -> 30.0
            else -> 7200.0
        }
        val elapsed = ((now - source.optLong("at")).coerceAtLeast(0)).toDouble() / 1000.0
        val original = content.optDouble("intensity")
        if (!original.isFinite()) return source
        val adopted = InnerTemporalEffects.projection(data, "episode:" + source.getString("id"),
            source.getInt("version"), now) { anchor, age ->
            if (type?.family == "emotion") anchor * exp(-age / seconds) else anchor
        }
        if (adopted != null) return JSONObject(source.toString()).put("time_projection", adopted)
        if (type?.family != "emotion") return source
        val value = original * exp(-elapsed / seconds)
        return JSONObject(source.toString()).put("time_projection", JSONObject()
            .put("intensity", value).put("at", now)
            .put("basis", "engine_emotion_natural_v1:baseline_without_relationship_closeness")
            .put("duration_parameter_seconds", seconds)
            .put("not_a_new_episode", true).put("not_resolved_by_time", true))
    }

    fun evidence(actions: JSONArray): List<String> {
        return (0 until actions.length()).map { actions.getJSONObject(it) }
            .filter { it.optString("op") == "appraise" }.flatMap { strings(it.getJSONArray("evidence"), Int.MAX_VALUE) }.distinct()
    }
    private fun category(data: JSONObject, id: String): JSONObject {
        // Structural vocabulary is installed by the host. It is not an adopted subjective
        // feeling and must not require the model to first invent a candidate about itself.
        val content = InnerStructure.record(data, id)?.getJSONObject("content")
            ?: InnerFoundations.definition(id)
            ?: throw InternalStateReportIssue("input", "category_ref", "UNKNOWN_CATEGORY", "心理类别不存在；先查询foundation或inner目录。")
        require(content.getString("family") in families && content.getString("status") == "active") {
            "此记录不是可承接心理事件的类别"
        }
        return content
    }
    internal fun append(data: JSONObject, frame: InternalStateFrame, row: JSONObject) {
        var journal = data.optJSONObject("category_commit")
        if (journal == null || journal.getString("id") != frame.id) {
            journal = JSONObject().put("schema", 1).put("id", frame.id).put("rows", JSONArray())
            data.put("category_commit", journal)
        }
        val rows = journal.getJSONArray("rows")
        rows.put(row)
    }
    fun apply(data: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long): Boolean {
        val touched = mutableSetOf<String>()
        for (i in 0 until actions.length()) {
            try {
            val a = actions.getJSONObject(i)
            val op = text(a, "op", 24)
            val id = text(a, "id", 64)
            SubjectContracts.expect(touched.add("$op:$id"), "id", "DUPLICATE_WRITE", "同一报告不能重复修改同一事件或重点。")
            val kind = when (op) { "appraise" -> "episode"; "focus" -> "focus"; else -> throw InternalStateReportIssue("input", "op", "ENUM_VALUE", "允许值：appraise|focus。") }
            val old = row(data, kind, id)
            val expected = SubjectContracts.integer(a, "expected_version")
            SubjectContracts.expect(expected == (old?.getInt("version") ?: 0), "expected_version", "VERSION_CHANGED", "先读取事件或重点的当前版本；新记录用0。")
            val content = JSONObject(a.toString()).apply { remove("op"); remove("expected_version") }
            val parent: String
            if (op == "appraise") {
                fields(a, setOf("op", "id", "expected_version", "category_ref", "objects", "goals", "intensity", "importance",
                    "long_term_impact", "attention", "status", "understanding", "basis", "evidence", "reason", "related",
                    "phase", "supporting", "opposing"))
                parent = text(a, "category_ref", 64)
                val category = category(data, parent)
                if (a.has("phase")) {
                    require(category.getString("family") == "desire") { "发展阶段仅适用于具体愿望" }
                    require(a.get("phase") is String &&
                        a.getString("phase") in setOf("arising", "strengthening", "weakening",
                            "sustaining", "suppressed", "suppression_failed", "disappeared",
                            "reappeared", "conflicted", "transformed", "acted", "abandoned")) {
                        "具体愿望阶段必须使用已约定的十二种状态之一"
                    }
                }
                for (name in listOf("supporting", "opposing"))
                    if (a.has(name)) SubjectContracts.strings(SubjectContracts.array(a, name), path = name)
                if (old != null) require(old.getString("parent") == parent) { "既有事件不能无声改类" }
                SubjectContracts.strings(SubjectContracts.array(a, "objects"), path = "objects"); SubjectContracts.strings(SubjectContracts.array(a, "goals"), path = "goals")
                val intensity = a.get("intensity")
                if (intensity === JSONObject.NULL) {
                    if (category.getString("family") != "feeling")
                        throw InternalStateReportIssue("episodes", "episodes[$i].intensity", "UNKNOWN_INTENSITY_REQUIRES_FEELING")
                } else if (intensity !is Number || !intensity.toDouble().isFinite() || intensity.toDouble() < 0) {
                    throw InternalStateReportIssue("episodes", "episodes[$i].intensity", "NONNEGATIVE_INTENSITY")
                }
                text(a, "importance", Int.MAX_VALUE); text(a, "long_term_impact", Int.MAX_VALUE)
                text(a, "understanding", Int.MAX_VALUE); text(a, "reason", Int.MAX_VALUE)
                SubjectContracts.choice(a, "attention", setOf("foreground", "background", "aside"))
                SubjectContracts.choice(a, "status", setOf("unresolved", "processing", "resolved"))
                SubjectContracts.choice(a, "basis", setOf("input", "result", "recollection", "reinterpretation"))
                val refs = SubjectContracts.strings(SubjectContracts.array(a, "evidence"), path = "evidence")
                SubjectContracts.expect(refs.isNotEmpty(), "evidence", "EVIDENCE_REQUIRED", "心理事件须提供至少一个实际读取的来源ID。")
                val bindings = JSONObject()
                refs.forEach { ref ->
                    require(!ref.startsWith("config:") && !ref.startsWith("relation:")) { "配置不是现实经历" }
                    val source = InnerStructure.source(data, frame, ref)
                    require(source.getString("source") !in setOf("state_report", "time")) { "状态变化不能伪装成现实经历" }
                    bindings.put(ref, source)
                }
                content.put("source_bindings", bindings)
                val related = if (a.has("related")) SubjectContracts.array(a, "related") else JSONArray()
                for (j in 0 until related.length()) {
                    val link = related.getJSONObject(j)
                    fields(link, setOf("id", "relation"))
                    val other = text(link, "id", 64)
                    SubjectContracts.expect(other != id && row(data, "episode", other) != null, "related[$j].id", "RELATED_EPISODE_REQUIRED", "须引用已存在且不同于自身的心理事件ID。")
                    require(text(link, "relation", 24) in setOf("same_matter", "split_from", "merged_from", "corrects"))
                }
                // Original bindings remain immutable even when new input changes interpretation.
                content.put("original_sources", old?.getJSONObject("content")?.getJSONObject("original_sources") ?: bindings)
            } else {
                fields(a, setOf("op", "id", "expected_version", "capacity", "events", "reason"))
                parent = id
                category(data, parent)
                val capacity = a.get("capacity")
                require(capacity is Number && capacity.toDouble() == capacity.toInt().toDouble() && capacity.toInt() in 3..6)
                val ids = strings(a.getJSONArray("events"), capacity.toInt())
                ids.forEach { require(row(data, "episode", it)?.getString("parent") == parent) { "重点须引用该类别已有事件" } }
                text(a, "reason", Int.MAX_VALUE) // Presence only; the host never scores importance or sorts.
            }
            // The report transport already bounds authored input. Provenance is host-owned
            // and must not make a valid short appraisal fail after expansion.
            append(data, frame, JSONObject().put("storage_kind", kind).put("id", id).put("parent", parent)
                .put("version", Math.addExact(old?.getInt("version") ?: 0, 1)).put("content", content)
                .put("owner", frame.roleId).put("frame_id", frame.id).put("at", now).put("source_chat", frame.chatId)
                .put("previous_version", old?.getInt("version") ?: 0).put("source", "subject_report_adoption"))
            } catch (failure: Exception) {
                if (failure is InternalStateReportRejected) throw failure
                throw InternalStateReportDiagnostics.issue(failure, "episodes[$i]")
            }
        }
        return actions.length() > 0
    }

    /** Called only after the existing record-adoption checks, including personality development. */
    fun linkAdopted(data: JSONObject, frame: InternalStateFrame, content: JSONObject, now: Long) {
        val ids = strings(content.optJSONArray("experience_refs") ?: JSONArray(), Int.MAX_VALUE)
        ids.forEach { id ->
            val episode = requireNotNull(row(data, "episode", id)) { "关联经历不存在" }
            val record = content.getString("id")
            val key = InnerStructure.hash("$record:$id")
            if (row(data, "experience_link", key) == null) append(data, frame, JSONObject()
                .put("storage_kind", "experience_link").put("id", key).put("parent", record).put("version", 1)
                .put("owner", frame.roleId).put("frame_id", frame.id).put("at", now).put("source_chat", frame.chatId)
                .put("episode_id", id).put("episode_version", episode.getInt("version"))
                .put("source", "subject_record_adoption"))
        }
    }

    fun validateLinks(data: JSONObject, content: JSONObject) {
        strings(content.optJSONArray("experience_refs") ?: JSONArray(), Int.MAX_VALUE).forEach {
            require(row(data, "episode", it) != null) { "关联经历不存在" }
        }
    }

    fun material(data: JSONObject, persist: Boolean, now: Long = System.currentTimeMillis(), input: String = "", chat: String = ""): JSONObject {
        val selection = data.optJSONObject("category_selection") ?: JSONObject().also { data.put("category_selection", it) }
        val result = JSONObject().put("schema", 1).put("episodes", JSONArray()).put("focus", JSONArray())
            .put("notice", "重点顺序由主体决定；本轮选材与重点排名不同。未选入不表示遗忘、解决或移出重点。get 可分页查事件、重点、经历关联与版本；原事实仍在 events。旧日志尚未明确关联，不自动升格为心事。")
        for ((kind, field) in listOf("episode" to "episodes", "focus" to "focus")) {
            val offset = selection.optInt(kind)
            val rows = access(data).categoryPage(kind, offset, 9)
            val selected = if (kind == "episode" && input.isNotBlank())
                SubjectRecall.selected(data, kind, input, chat, offset.toLong(), rows.take(8)) else rows.take(8)
            // Each complete record has a bounded shape. A separate rotating disk cursor avoids
            // an unbounded hot index and never changes the subject's chosen focus ordering.
            for (row in selected) {
                result.getJSONArray(field).put(if (kind == "episode") requestProjection(timeView(data, row, now)) else row)
            }
            val consumed = minOf(rows.size, 8)
            if (persist) selection.put(kind, if (rows.size > consumed && consumed > 0) offset + consumed else 0)
        }
        return result
    }

    internal fun requestProjection(source: JSONObject): JSONObject {
        val view = JSONObject(source.toString())
        if (SubjectRestDreams.privateProvenance(source)) view.put("subject_private_material", true)
        view.optJSONObject("content")?.let { content ->
            for (field in listOf("source_bindings", "original_sources")) {
                content.optJSONObject(field)?.let { bindings ->
                    bindings.keys().forEach { id -> bindings.getJSONObject(id).remove("snapshot") }
                }
            }
        }
        return view.put("projection", "来源快照完整保存在事件版本中；get section=episode或category_versions按ID读取。")
    }

    val instructions = """
        [类别内部事件 v1]
        类别使用已采用 inner 记录的 ID，或宿主已安装的 foundation:family:key 目录 ID。不同事情用独立稳定事件 ID；不能按同名对象/关键词合并，也不必每条消息建事件。
        同一报告可选 episodes:[...]；没有业务条数上限，按本次模型窗口分批提交，宿主轮末校验采用，无需额外模型调用。
        companion_internal_state action:get section:source id:事实ID 可按ID取得原事件，含已归档来源。section:episode|focus 用 id 精确读取；experience_link 用 id:内在记录ID 查累计经历关联；category_versions 用 id:"episode:事件ID" 或 "focus:类别ID" 查变化过程，offset 分页。
        {op:"appraise",id:"稳定事件ID",expected_version:0或当前版本,category_ref:"类别ID",objects:["对象"],goals:["目标"],intensity:非负强度,importance:"重要性",long_term_impact:"长期意义",attention:"foreground|background|aside",status:"unresolved|processing|resolved",understanding:"当前理解",basis:"input|result|recollection|reinterpretation",evidence:["已读事实ID"],reason:"变化说明",related:[{id:"已有事件ID",relation:"same_matter|split_from|merged_from|corrects"}]}。
        feeling 类别的 intensity 可明确为 null，表示未知或没有确定分数，不等于0；其他类别仍需非负有限数。感受以后可由主体重新理解并明确强度，旧版本原话与未知事实保留。related 可省略。回忆/重新理解引用原事实；不创建第二次现实发生。不同类别可引用同一个事实。关联修正保留版本和原始来源；不会删除原事件。
        {op:"focus",id:"类别ID",expected_version:0或当前重点版本,capacity:3到6,events:["按你决定的次序排列的事件ID"],reason:"本次安排或替换理由"}。可以不足3件或为空，不凑数。重点外的事件完整保留。
        强度、重要性、注意力、解决状态相互独立。放到背景不是原谅或解决；重新关注不自动回升强度。执念/不甘心不意味着必须行动或不能放下。
        基础九情绪在读取时附 time_projection：愤怒的默认参数12小时，惊讶30秒，其余默认2小时。当前关系亲近分量未核实则不虚构关系修正；该投影不是新经历，也不写回强度或把未解决改为解决，主体仍可明确重新评价。
        愿望事件可另带 phase:"arising|strengthening|weakening|sustaining|suppressed|suppression_failed|disappeared|reappeared|conflicted|transformed|acted|abandoned" 以及 supporting/opposing 字符串数组。阶段由主体对实际经历判断，不要求按顺序走完；其他类别不得伪称愿望阶段。
        inner.record 可带 experience_refs:["已有心理事件ID"]，不设额外关联条数上限；只在通过原采用规则后关联，之前的关联另存并保留，可查询。它们不替代人格采用所需的原文依据。
        广义生用 need level:broad semantic_rule:"life"；求生欲用 need level:specific parent_ref 指向该广义需要，semantic_rule:"survival",strength:自主给出的非负有限数,objects:[...],goals:[...]。不强制创建，不从旧值推测，不允许 legacy_ref 产生第二强度权威。
        求生欲候选 propose/adopt 以及基础求生欲 foundation 报告的 reason 可以省略或为空，其他类别仍须明确理由。仍须真实 evidence、版本和采用；求生欲不受时间/溢出/联合自动改值，归零不触发停止或删除。其事件强度也不自动改写该需要强度。
    """.trimIndent()

    private fun fields(o: JSONObject, allowed: Set<String>) = SubjectContracts.fields(o, allowed)
    private fun text(o: JSONObject, key: String, limit: Int) = SubjectContracts.text(o, key, limit)
    internal fun strings(a: JSONArray, limit: Int): List<String> = SubjectContracts.strings(a, limit)
}
