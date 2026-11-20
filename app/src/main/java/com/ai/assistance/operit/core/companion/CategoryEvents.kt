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
    internal val families = setOf("drive", "need", "desire", "emotion", "sentiment", "feeling")
    val newCategoryGuide = """
        新事件可在 appraise 条目附 category_definition，与事件一起校验和保存。已有合适分类时只写真实 category_ref。新定义须有未使用的 id，和 category_ref 相同。family 只可选 drive（驱力）、need（需要）、desire（欲望）、emotion（情绪）、sentiment（情感）、feeling（感受）。kind 是该大类内的个人分类名，description 写含义，context 可说明适用情境，status 为 active。
        drive 另填 direction；need 填 level:broad|specific，specific 要有已启用的 drive/need 作为 parent_ref；desire 填该 parent_ref 和 object_ref；emotion/sentiment/feeling 填 unresolved 布尔值和 current_state。必须给 time_rule:{curve:"inherit|hold"}，或 {curve:"linear|exponential",target:非负数,seconds:正数}。inherit 按该类自然机制，hold 保持，持续情感用 inherit 或 hold。新增后按现有时间、事件、版本和联合机制处理，需相互影响时可之后按真实证据写 unions。此入口只新增分类，稳定人格、特殊需要、旧分类修订走原流程。
    """.trimIndent()
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
    internal fun validateTimeRule(content: JSONObject) {
        if (!content.has("time_rule")) return // Records adopted before this rule retain their behavior.
        val rule = content.optJSONObject("time_rule")
            ?: throw InternalStateReportIssue("input", "record.time_rule", "EXPECTED_OBJECT")
        val curve = SubjectContracts.choice(rule, "curve", setOf("inherit", "hold", "linear", "exponential"))
        fields(rule, if (curve in setOf("inherit", "hold")) setOf("curve") else setOf("curve", "target", "seconds"))
        if (curve in setOf("linear", "exponential")) {
            SubjectContracts.expect(content.getString("family") != "sentiment" &&
                content.optString("semantic_rule") !in setOf("survival", "mortality"),
                "record.time_rule.curve", "STABLE_CATEGORY", "持续情感、求生欲和终结认识的数值不按自动曲线变化。")
            val target = rule.opt("target") as? Number
            val seconds = rule.opt("seconds") as? Number
            SubjectContracts.expect(target != null && target.toDouble().isFinite() && target.toDouble() >= 0,
                "record.time_rule.target", "NONNEGATIVE_INTENSITY", "目标须为非负有限数。")
            SubjectContracts.expect(seconds != null && seconds.toDouble().isFinite() && seconds.toDouble() > 0,
                "record.time_rule.seconds", "POSITIVE_SECONDS", "变化时长须为正有限秒数。")
        }
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
        val rule = source.optJSONObject("category_time_rule")
        val curve = rule?.getString("curve")
        val inheritsEmotion = source.optString("category_family", type?.family.orEmpty()) == "emotion" &&
            (curve == "inherit" || rule == null && type != null)
        fun natural(anchor: Double, age: Double): Double = when {
            curve == "linear" -> anchor + (requireNotNull(rule).getDouble("target") - anchor) *
                (age / rule.getDouble("seconds")).coerceIn(0.0, 1.0)
            curve == "exponential" -> requireNotNull(rule).getDouble("target").let { target ->
                target + (anchor - target) * exp(-age / rule.getDouble("seconds"))
            }
            inheritsEmotion -> anchor * exp(-age / seconds)
            else -> anchor
        }
        val adopted = InnerTemporalEffects.projection(data, "episode:" + source.getString("id"),
            source.getInt("version"), now, ::natural)
        if (adopted != null) return JSONObject(source.toString()).put("time_projection", adopted)
        if (!inheritsEmotion && rule == null) return source
        val value = natural(original, elapsed)
        return JSONObject(source.toString()).put("time_projection", JSONObject()
            .put("intensity", value).put("at", now)
            .put("basis", if (inheritsEmotion) "engine_emotion_natural_v1:baseline_without_relationship_closeness" else "subject_category_time_rule")
            .put("duration_parameter_seconds", if (inheritsEmotion) seconds else rule?.opt("seconds") ?: JSONObject.NULL)
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
            ?: throw InternalStateReportIssue("input", "category_ref", "UNKNOWN_CATEGORY",
                "分类ID不存在。查 foundation 和 inner_family 选合适的真实分类；新事件确需新分类可带 category_definition 和 time_rule。待修正原事件可用 episode_repair 取回。")
        SubjectContracts.expect(content.getString("family") in families && content.getString("status") == "active",
            "category_ref", "INACTIVE_PSYCHOLOGICAL_CATEGORY", "须引用已启用的驱力、需要、欲望、情绪、情感或感受类别；先查询foundation或inner目录。")
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
    fun apply(data: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long,
              repairSources: Map<String, JSONObject> = emptyMap()): Boolean {
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
            var appliedRule: JSONObject? = null
            var appliedFamily: String? = null
            val parent: String
            if (op == "appraise") {
                fields(a, setOf("op", "id", "expected_version", "category_ref", "objects", "goals", "intensity", "importance",
                    "long_term_impact", "attention", "status", "understanding", "basis", "evidence", "reason", "related",
                    "phase", "supporting", "opposing", "category_definition"))
                parent = text(a, "category_ref", 64)
                val definition = if (a.has("category_definition")) a.optJSONObject("category_definition")
                    ?: throw InternalStateReportIssue("input", "category_definition", "EXPECTED_OBJECT") else null
                if (definition != null) {
                    SubjectContracts.expect(old == null, "category_definition", "NEW_EPISODE_REQUIRED",
                        "只有新事件可随报告新增分类；已有事件保留原分类。")
                    SubjectContracts.expect(definition.optString("id") == parent, "category_definition.id",
                        "CATEGORY_ID_MISMATCH", "新分类 id 须与事件的 category_ref 一致。")
                    content.remove("category_definition")
                }
                val category = definition ?: category(data, parent)
                appliedRule = category.optJSONObject("time_rule")
                appliedFamily = SubjectContracts.choice(category, "family", families, "category_definition.family")
                if (a.has("phase")) {
                    SubjectContracts.expect(category.getString("family") == "desire", "phase", "DESIRE_PHASE_ONLY",
                        "phase仅用于desire愿望事件；其他类别用understanding说明变化，不填写phase。")
                    SubjectContracts.choice(a, "phase", setOf("arising", "strengthening", "weakening",
                            "sustaining", "suppressed", "suppression_failed", "disappeared",
                            "reappeared", "conflicted", "transformed", "acted", "abandoned"))
                }
                for (name in listOf("supporting", "opposing"))
                    if (a.has(name)) SubjectContracts.strings(SubjectContracts.array(a, name), path = name)
                if (old != null) SubjectContracts.expect(old.getString("parent") == parent,
                    "category_ref", "EPISODE_CATEGORY_CHANGED",
                    "同一事件ID须保持原类别。先get section=episode id=该事件ID核对；另一类别的感受用新ID和related:[{id:原事件ID,relation:same_matter}]关联。原事件可另行更新强度、理解或状态，不自动消除。")
                // Object/goal associations are optional metadata, not a requirement to invent
                // a target for every feeling. Omission retains existing associations; [] clears.
                for (name in listOf("objects", "goals")) {
                    val values = if (a.has(name)) SubjectContracts.array(a, name)
                        else if (old != null) JSONArray(old.getJSONObject("content").getJSONArray(name).toString())
                        else JSONArray()
                    SubjectContracts.strings(values, path = name)
                    content.put(name, values)
                }
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
                refs.forEachIndexed { index, ref ->
                    SubjectContracts.expect(!ref.startsWith("config:") && !ref.startsWith("relation:"),
                        "evidence[$index]", "EPISODE_EXPERIENCE_SOURCE", "配置不是现实经历；引用实际消息、操作结果或已读取材料的来源ID。")
                    val original = repairSources[id]?.optString(ref)?.takeIf { it.isNotEmpty() }
                    val evidenceFrame = if (original == null) frame
                        else frame.copy(materialSources = mapOf(ref to original))
                    val source = InnerStructure.source(data, evidenceFrame, ref)
                    SubjectContracts.expect(source.getString("source") !in setOf("state_report", "time"),
                        "evidence[$index]", "EPISODE_EXPERIENCE_SOURCE", "状态报告或时间投影不是新的现实经历；引用原事实，用basis说明回忆或重新理解。")
                    bindings.put(ref, source)
                }
                if (definition != null) InnerStructure.registerEventCategory(data, frame, definition, id,
                    bindings, now, a.getString("reason"))
                content.put("source_bindings", bindings)
                val related = if (a.has("related")) SubjectContracts.array(a, "related") else JSONArray()
                for (j in 0 until related.length()) {
                    val link = related.getJSONObject(j)
                    fields(link, setOf("id", "relation"))
                    val other = text(link, "id", 64)
                    SubjectContracts.expect(other != id && row(data, "episode", other) != null, "related[$j].id", "RELATED_EPISODE_REQUIRED", "须引用已存在且不同于自身的心理事件ID。")
                    SubjectContracts.choice(link, "relation", setOf("same_matter", "split_from", "merged_from", "corrects"), "related[$j].relation")
                }
                // Original bindings remain immutable even when new input changes interpretation.
                content.put("original_sources", old?.getJSONObject("content")?.getJSONObject("original_sources") ?: bindings)
            } else {
                fields(a, setOf("op", "id", "expected_version", "capacity", "events", "reason"))
                parent = id
                category(data, parent)
                val capacity = SubjectContracts.integer(a, "capacity")
                SubjectContracts.expect(capacity in 3..6, "capacity", "FOCUS_CAPACITY", "重点位置capacity须为3..6；实际events可以为空或不足3项。")
                val ids = SubjectContracts.strings(SubjectContracts.array(a, "events"), capacity, "events")
                ids.forEachIndexed { index, event -> SubjectContracts.expect(row(data, "episode", event)?.getString("parent") == parent,
                    "events[$index]", "FOCUS_CATEGORY_MISMATCH", "重点须引用该类别已有的心理事件；先查询事件的category_ref。") }
                text(a, "reason", Int.MAX_VALUE) // Presence only; the host never scores importance or sorts.
            }
            // The report transport already bounds authored input. Provenance is host-owned
            // and must not make a valid short appraisal fail after expansion.
            append(data, frame, JSONObject().put("storage_kind", kind).put("id", id).put("parent", parent)
                .put("version", Math.addExact(old?.getInt("version") ?: 0, 1)).put("content", content)
                .put("owner", frame.roleId).put("frame_id", frame.id).put("at", now).put("source_chat", frame.chatId)
                .put("previous_version", old?.getInt("version") ?: 0).put("source", "subject_report_adoption")
                .apply {
                    appliedFamily?.let { put("category_family", it) }
                    appliedRule?.let { put("category_time_rule", it) }
                })
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
        分类ID不存在时，查 foundation 和 inner_family 选含义合适的真实ID。可用 get section=episode_repair 取回待修正原事件。下面是随新事件新增分类的规则。
        $newCategoryGuide
        新分类 time_rule 用 {curve:"inherit"}（情绪自然回落，其余按实际评价变化）、{curve:"hold"}（保持），或 {curve:"linear|exponential",target:非负强度,seconds:正秒数}。规则投影该类有数值的事件；持续情感、求生欲和终结认识保持原规则。实际加强或拉扯沿用 unions，可后来定，建立分类不会自动产生影响。
        同一报告可选 episodes:[...]；没有业务条数上限，按本次模型窗口分批提交，宿主轮末校验采用，无需额外模型调用。
        companion_internal_state action:get section:source id:事实ID 可按ID取得原事件，含已归档来源。section:episode|focus 用 id 精确读取；experience_link 用 id:内在记录ID 查累计经历关联；category_versions 用 id:"episode:事件ID" 或 "focus:类别ID" 查变化过程，offset 分页。
        {op:"appraise",id:"稳定事件ID",expected_version:0或当前版本,category_ref:"类别ID",objects:["对象"],goals:["目标"],intensity:非负强度,importance:"重要性",long_term_impact:"长期意义",attention:"foreground|background|aside",status:"unresolved|processing|resolved",understanding:"当前理解",basis:"input|result|recollection|reinterpretation",evidence:["已读事实ID"],reason:"变化说明",related:[{id:"已有事件ID",relation:"same_matter|split_from|merged_from|corrects"}]}。
        同一事件ID的category_ref保持原类别；这不限制感受改变。同一事情从悲伤转为依恋时，可更新原悲伤事件，并用新ID保存依恋事件，related:[{id:"原悲伤事件ID",relation:"same_matter"}]保留关联；不能把原ID直接换类。objects/goals可省略：更新时保留原关联，新建时无关联；显式[]表示无关联，不必为每种感受编目标。提供时必须是字符串数组，不能是null或单个字符串。
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
