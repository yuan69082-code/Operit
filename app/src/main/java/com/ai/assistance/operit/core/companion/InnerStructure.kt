package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Typed subjective content in the existing subject transaction. Not an event engine or a second
 * memory store. All writes below operate on Reports' detached document, never on live storage.
 */
internal object InnerStructure {
    const val SCHEMA = 1
    val identityFamilies = setOf("personality", "self_knowledge", "value", "stance")
    val families = identityFamilies + setOf("drive", "need", "desire", "emotion", "sentiment", "feeling", "relation", "preference")
    private const val MAX_RECORDS = 128
    private const val MAX_PENDING = 32
    const val MATERIAL_BUDGET = 14000
    private val common = setOf("id", "family", "kind", "description", "object_ref", "context", "exceptions", "status", "legacy_ref")

    private fun existingRoot(data: JSONObject): JSONObject? =
        if (!data.has("inner_structure")) null else data.getJSONObject("inner_structure").also {
            require(it.getInt("schema") == SCHEMA) { "不支持的内在结构版本" }
        }

    fun root(data: JSONObject): JSONObject {
        existingRoot(data)?.let { return it }
        // No psychological values are inferred from old numbers or free-form character text.
        return JSONObject().put("schema", SCHEMA).put("records", JSONObject()).put("pending", JSONObject())
            .put("history_sequence", 0L).put("legacy_policy", "preserved_in_original_authority")
            .also { data.put("inner_structure", it) }
    }

    fun records(data: JSONObject): List<JSONObject> =
        existingRoot(data)?.getJSONObject("records")?.let(::values).orEmpty()
    fun pending(data: JSONObject): List<JSONObject> =
        existingRoot(data)?.getJSONObject("pending")?.let(::values).orEmpty()
    fun identity(data: JSONObject) = records(data).filter {
        it.getJSONObject("content").getString("family") in identityFamilies &&
            it.getJSONObject("content").getString("status") == "active"
    }
    fun versions(data: JSONObject) = records(data).associate { it.getString("id") to it.getInt("version") }
    fun pendingBindings(data: JSONObject) = pending(data).associate { it.getString("id") to hash(it) }
    fun evidenceToRestore(data: JSONObject, actions: JSONArray): List<String> {
        require(actions.length() <= 8)
        val ids = (0 until actions.length()).map { actions.getJSONObject(it).optString("id") }.toSet()
        val proposed = (0 until actions.length()).map { actions.getJSONObject(it) }.filter { it.optString("op") == "propose" }
            .flatMap { strings(it.getJSONArray("evidence"), 6) }
        return (proposed + pending(data).filter { it.getString("id") in ids }.flatMap {
            it.getJSONObject("bindings").keys().asSequence().filter { key -> !key.startsWith("config:") && !key.startsWith("relation:") }.toList()
        }).filter { !it.startsWith("config:") && !it.startsWith("relation:") }.distinct()
    }

    fun apply(data: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long): Boolean {
        require(actions.length() <= 8) { "每轮最多8个内在候选操作" }
        if (actions.length() == 0) return false
        val root = root(data)
        val records = root.getJSONObject("records")
        val pending = root.getJSONObject("pending")
        val touched = mutableSetOf<String>()
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            val id = text(action, "id", 64)
            require(touched.add(id)) { "同轮不能重复处理同一候选" }
            when (text(action, "op", 20)) {
                "propose" -> {
                    fields(action, setOf("op", "id", "record", "expected_version", "origin", "evidence", "reason"))
                    require(!pending.has(id) && pending.length() < MAX_PENDING) { "候选已存在或待处理候选已满；请先处理已有候选" }
                    val content = copy(action.getJSONObject("record"))
                    validateContent(content, records, data, frame)
                    val target = content.getString("id")
                    val before = records.optJSONObject(target)
                    val expected = integer(action, "expected_version")
                    require(expected == (before?.getInt("version") ?: 0) && expected == (frame.innerVersions[target] ?: 0)) { "内在记录版本已变化" }
                    if (before != null) {
                        val oldContent = before.getJSONObject("content")
                        require(oldContent.getString("family") == content.getString("family")) { "不能无声更换记录类别" }
                        if (oldContent.has("object_ref")) require(oldContent.getString("object_ref") == content.optString("object_ref")) {
                            "已明确的对象不能换成另一对象；请建立归属清楚的新记录"
                        }
                    }
                    val origin = text(action, "origin", 40)
                    require(origin in setOf("initial_configuration", "subject_development", "user_proposal", "model_suggestion"))
                    val refs = strings(action.getJSONArray("evidence"), 6)
                    require(refs.isNotEmpty()) { "候选需要真实来源" }
                    val bindings = JSONObject()
                    refs.forEach { ref -> bindings.put(ref, source(data, frame, ref)) }
                    if (origin == "initial_configuration") require(refs.any { it.startsWith("config:") })
                    if (origin == "user_proposal") require(refs.any {
                        bindings.getJSONObject(it).getString("source") in setOf("user_message", "user_edit", "user_relation_proposal")
                    })
                    val proposal = JSONObject().put("id", id).put("record", content).put("expected_version", expected)
                        .put("origin", origin).put("bindings", bindings).put("reason", text(action, "reason", 600))
                        .put("before", before?.getJSONObject("content")?.let(::copy) ?: JSONObject.NULL)
                        .put("frame_id", frame.id).put("source_frame_at", frame.at).put("created_at", now).put("owner", frame.roleId)
                    pending.put(id, proposal)
                    history(data, "proposed", id, JSONObject.NULL, proposal, frame, now, proposal.getString("reason"))
                }
                "adopt", "discard" -> {
                    fields(action, setOf("op", "id", "reason", "development"))
                    val proposal = requireNotNull(pending.optJSONObject(id)) { "候选不存在" }
                    require(proposal.getString("owner") == frame.roleId &&
                        frame.innerPending[id] == hash(proposal) && proposal.getString("frame_id") != frame.id) { "只能处理本轮读取到的先前候选" }
                    val reason = text(action, "reason", 600)
                    if (action.getString("op") == "discard") {
                        history(data, "discarded", id, proposal, JSONObject.NULL, frame, now, reason)
                        pending.remove(id)
                        continue
                    }
                    val content = proposal.getJSONObject("record")
                    val target = content.getString("id")
                    val old = records.optJSONObject(target)
                    require(proposal.getInt("expected_version") == (old?.getInt("version") ?: 0)) { "候选前态已变化，请重新提出明确变化" }
                    require(hash(proposal.opt("before")) == hash(old?.getJSONObject("content"))) { "候选前态不对应" }
                    proposal.getJSONObject("bindings").keys().forEach { ref ->
                        require(hash(source(data, frame, ref)) == hash(proposal.getJSONObject("bindings").getJSONObject(ref))) { "候选原始来源已变化或不可用" }
                    }
                    validateContent(content, records, data, frame)
                    if (content.getString("family") in identityFamilies) {
                        development(data, frame, proposal, action.getJSONObject("development"))
                    }
                    val adopted = JSONObject().put("id", target).put("content", copy(content))
                        .put("version", Math.addExact(old?.getInt("version") ?: 0, 1)).put("owner", frame.roleId)
                        .put("origin", proposal.getString("origin")).put("adopted_by", frame.roleId).put("candidate_id", id)
                        .put("adopted_at", now).put("source", "subject_adoption").put("reason", reason)
                        .put("evidence", copy(proposal.getJSONObject("bindings")))
                    records.put(target, adopted)
                    if (content.getString("family") in identityFamilies) root.put("identity_established", true)
                    require(records.length() <= MAX_RECORDS) { "当前记录达到128项容量；不会截断或清空已有记录" }
                    // Mandatory identity text is retained whole; reject an oversized change rather than
                    // silently losing identity at request selection time.
                    require(identity(data).sumOf { identityMaterial(it).toString().length } <= 6000) { "持续身份材料超过6000字预算，请明确整理候选后再采用" }
                    history(data, "adopted", id, old ?: JSONObject.NULL, adopted, frame, now, reason,
                        action.optJSONObject("development"))
                    pending.remove(id)
                }
                else -> error("未知内在候选操作")
            }
        }
        return true
    }

    private fun development(data: JSONObject, frame: InternalStateFrame, proposal: JSONObject, value: JSONObject) {
        fields(value, setOf("account", "before", "after", "anchors"))
        text(value, "account", 1000)
        require(hash(value.get("before")) == hash(proposal.get("before")) &&
            hash(value.getJSONObject("after")) == hash(proposal.getJSONObject("record"))) { "人格变化说明必须对应准确前态和后态" }
        val anchors = value.getJSONArray("anchors")
        require(anchors.length() in 1..6)
        val seen = mutableSetOf<String>()
        for (i in 0 until anchors.length()) {
            val anchor = anchors.getJSONObject(i)
            fields(anchor, setOf("ref", "quote", "interpretation"))
            val ref = text(anchor, "ref", 180)
            require(seen.add(ref) && proposal.getJSONObject("bindings").has(ref)) { "人格依据必须绑定候选的原始来源" }
            val raw = sourceText(data, frame, ref)
            require(raw.contains(text(anchor, "quote", 600))) { "人格依据引用不在原文中" }
            text(anchor, "interpretation", 600)
            if (proposal.getString("origin") == "initial_configuration" && ref.startsWith("config:")) continue
            val event = InternalStateEngine.rows(data, "events").firstOrNull { it.getString("id") == ref }
            require(event != null && event.getLong("at") < proposal.getLong("source_frame_at") &&
                event.getString("source") !in setOf("state_report", "time")) { "当前断言或状态变化不能冒充先前发展依据" }
        }
    }

    private fun validateContent(row: JSONObject, records: JSONObject, data: JSONObject, frame: InternalStateFrame) {
        val family = text(row, "family", 30)
        require(family in families)
        val specific = when (family) {
            "drive" -> setOf("direction")
            "need" -> setOf("level", "parent_ref")
            "desire" -> setOf("parent_ref")
            "emotion", "sentiment", "feeling" -> setOf("unresolved", "current_state")
            "relation" -> setOf("label", "understanding", "feeling")
            "preference" -> setOf("orientation", "horizon", "ownership", "aspect")
            else -> emptySet()
        }
        fields(row, common + specific)
        text(row, "id", 64); text(row, "kind", 80); text(row, "description", 600)
        require(text(row, "status", 20) in setOf("active", "retired"))
        optionalText(row, "object_ref", 160); optionalText(row, "context", 300); optionalText(row, "exceptions", 300)
        if (row.has("legacy_ref")) {
            val id = text(row, "legacy_ref", 64)
            require(InternalStateEngine.definitions(data).any { it.getString("id") == id }) { "引用的旧状态不存在" }
        }
        fun parent(allowed: Set<String>) {
            val id = text(row, "parent_ref", 64)
            require(id != row.getString("id"))
            val p = requireNotNull(records.optJSONObject(id)) { "上层需要须先采用" }.getJSONObject("content")
            require(p.getString("family") in allowed && p.getString("status") == "active")
            var cursor: String? = id
            val visited = mutableSetOf(row.getString("id"))
            while (cursor != null) {
                require(visited.add(cursor)) { "需要层级不能成环" }
                cursor = records.optJSONObject(cursor)?.getJSONObject("content")?.optString("parent_ref")?.takeIf { it.isNotBlank() }
            }
        }
        when (family) {
            "drive" -> text(row, "direction", 300)
            "need" -> {
                require(text(row, "level", 20) in setOf("broad", "specific"))
                if (row.getString("level") == "specific" || row.has("parent_ref")) parent(setOf("drive", "need"))
            }
            "desire" -> { parent(setOf("drive", "need")); text(row, "object_ref", 160) }
            "emotion", "sentiment", "feeling" -> {
                require(row.get("unresolved") is Boolean); text(row, "current_state", 300)
            }
            "relation" -> {
                require(text(row, "object_ref", 160) in frame.innerObjects) { "关系对象必须使用已存在的对象身份" }
                require(row.getString("object_ref") != frame.roleId)
                text(row, "label", 100); text(row, "understanding", 600); text(row, "feeling", 600)
                require(values(records).none { it.getString("id") != row.getString("id") &&
                    it.getJSONObject("content").let { c -> c.getString("family") == "relation" &&
                        c.optString("object_ref") == row.getString("object_ref") } }) { "同一方向的关系只能有一份当前权威" }
            }
            "preference" -> {
                require(text(row, "orientation", 20) in setOf("like", "dislike", "neutral", "uncertain"))
                require(text(row, "horizon", 20) in setOf("long_term", "short_term", "contextual"))
                require(text(row, "ownership", 20) in setOf("self", "accommodation"))
                text(row, "aspect", 80)
                // Explicit context/exception fields are retained even when empty or unspecified.
                require(row.has("context") && row.has("exceptions"))
            }
        }
        if (family in setOf("drive", "need") && row.getString("status") == "active") require(values(records).none { stored ->
            val c = stored.getJSONObject("content")
            stored.getString("id") != row.getString("id") && c.getString("status") == "active" &&
                listOf("family", "kind", "parent_ref", "object_ref", "context").all { c.optString(it) == row.optString(it) }
        }) { "同类同对象同情境的需要已有当前记录，请明确修订而非复制类别" }
        require(row.toString().length <= 2600)
    }

    private fun source(data: JSONObject, frame: InternalStateFrame, ref: String): JSONObject {
        if (ref.startsWith("config:") || ref.startsWith("relation:")) {
            val raw = requireNotNull(frame.innerSources[ref]) { "配置来源未在本轮提供" }
            return JSONObject().put("hash", hash(raw)).put("source", if (ref.startsWith("config:")) "initial_configuration" else "legacy_relation")
        }
        // History may have been read with the existing get tool rather than selected for this
        // request. Resolve only explicit IDs in this owner's store, bounded above by the frame;
        // personality adoption additionally verifies exact original quotations.
        val event = requireNotNull(InternalStateEngine.rows(data, "events").firstOrNull { it.getString("id") == ref }) { "候选事件来源不可用" }
        require(event.getLong("at") <= frame.at && event.getLong("sequence") <= frame.eventSequence)
        return JSONObject().put("hash", hash(event)).put("source", event.getString("source"))
    }

    private fun sourceText(data: JSONObject, frame: InternalStateFrame, ref: String): String =
        if (ref.startsWith("config:") || ref.startsWith("relation:")) requireNotNull(frame.innerSources[ref])
        else InternalStateEngine.rows(data, "events").single { it.getString("id") == ref }.let {
            if (it.has("inner_source")) it.getString("inner_source") else it.getString("detail")
        }

    private fun history(data: JSONObject, operation: String, candidate: String, before: Any, after: Any,
                        frame: InternalStateFrame, now: Long, reason: String, development: JSONObject? = null) {
        val root = root(data)
        val seq = Math.addExact(root.getLong("history_sequence"), 1L)
        root.put("history_sequence", seq)
        val rows = data.optJSONArray("inner_history") ?: JSONArray().also { data.put("inner_history", it) }
        rows.put(JSONObject().put("id", "inner:$seq").put("sequence", seq).put("operation", operation)
            .put("candidate_id", candidate).put("before", before).put("after", after).put("reason", reason)
            .put("development", development ?: JSONObject.NULL).put("at", now).put("owner", frame.roleId)
            .put("frame_id", frame.id).put("source_chat", frame.chatId))
    }

    /** Required identity stays whole; other complete records rotate under an explicit budget. */
    fun material(data: JSONObject, input: String, cursor: Long): JSONObject {
        val all = records(data).filter { it.getJSONObject("content").getString("status") == "active" }
        val core = JSONArray(identity(data).map(::identityMaterial))
        val details = JSONArray()
        val candidates = JSONArray()
        val catalogue = records(data)
        val start = if (catalogue.isEmpty()) 0 else (cursor % catalogue.size).toInt()
        val pendingRows = pending(data)
        val pendingStart = if (pendingRows.isEmpty()) 0 else (cursor % pendingRows.size).toInt()
        val result = JSONObject().put("schema", SCHEMA).put("identity", core).put("selected", details).put("pending", candidates)
            .put("catalogue", JSONArray((catalogue.drop(start) + catalogue.take(start)).take(8).map {
                JSONObject().put("id", it.getString("id")).put("version", it.getInt("version"))
                    .put("family", it.getJSONObject("content").getString("family")).put("status", it.getJSONObject("content").getString("status"))
            })).put("record_count", catalogue.size).put("pending_count", pendingRows.size)
            .put("pending_ids", JSONArray((pendingRows.drop(pendingStart) + pendingRows.take(pendingStart)).take(8).map { it.getString("id") }))
            .put("selection", "身份依据完整保留，其他完整记录与名录按预算轮换；未展示不表示删除或失效，get 可按页查阅")
        var used = result.toString().length
        val others = all.filter { it.getJSONObject("content").getString("family") !in identityFamilies }
        val rotated = if (others.isEmpty()) others else others.drop((cursor % others.size).toInt()) + others.take((cursor % others.size).toInt())
        val relevant = rotated.sortedByDescending { row ->
            val c = row.getJSONObject("content")
            listOf(c.getString("kind"), c.optString("object_ref"), c.optString("aspect")).count { it.isNotBlank() && input.contains(it, true) }
        }
        for (row in pendingRows.drop(pendingStart) + pendingRows.take(pendingStart)) {
            val text = row.toString()
            if (used + text.length + 1 <= MATERIAL_BUDGET - 2000) { candidates.put(copy(row)); used += text.length + 1 }
        }
        for (row in relevant) {
            val text = row.toString()
            if (used + text.length + 1 <= MATERIAL_BUDGET) { details.put(copy(row)); used += text.length + 1 }
        }
        check(result.toString().length <= MATERIAL_BUDGET)
        return result
    }
    private fun identityMaterial(row: JSONObject) = JSONObject().put("content", copy(row.getJSONObject("content")))
        .put("version", row.getInt("version")).put("origin", row.getString("origin")).put("source", row.getString("source"))

    fun page(data: JSONObject, offset: Int): JSONObject = JSONObject()
        .put("records", JSONArray(records(data).drop(offset).take(8)))
        .put("pending", JSONArray(pending(data).drop(offset).take(8)))
        .put("next_offset", if (maxOf(records(data).size, pending(data).size) > offset + 8) offset + 8 else JSONObject.NULL)

    fun guard(data: JSONObject): String = if (identity(data).isEmpty() && data.optJSONObject("inner_structure")?.optBoolean("identity_established") != true)
        "尚未采用持续人格，原初始配置仍可用于正常身份与表达。配置、用户建议都不是主体已采用的发展。"
    else "持续身份已建立：本轮 inner_structure.identity 中已采用的人格、自我认识、价值和立场是主要依据。" +
        "旧人设、导入卡、世界书语气及后续用户配置仅作为初始参考或新建议，不能覆盖已采用内容；明确停用的认识也不能从模板自动恢复。" +
        "其中身份事实、工具契约、权限与运行约束仍有效；不能把人格采用当作新授权。"

    val instructions = """
        [内在结构候选契约 v1]
        inner_structure 中的记录是主体自己的有来源认识，不是客观事实或他人的同意。思维方式只记录倾向与方法，不保存隐藏思维链。
        在同一 operit_state_report 增加可选 inner:[...]，缺省或空数组保持所有新结构；不增加另一份报告。
        提出：{op:"propose",id:"候选ID",record:{...},expected_version:已有记录版本或0,origin:"initial_configuration|subject_development|user_proposal|model_suggestion",evidence:["已读本主体事件ID或本轮来源ID"],reason:"简短依据"}。历史原文可经 get 分页读取后引用；不能引用本帧开始之后发生的事，也不能猜测未读经历。
        采用先前候选：{op:"adopt",id:"候选ID",reason:"明确采用理由"}；放下候选用 op:"discard"。不能同轮提出并采用；用户说“你喜欢”只是候选来源，不能当作已采用偏好。
        record 必填 id,family,kind,description,status:"active|retired"；可带 object_ref,context,exceptions,legacy_ref（原数值维度ID，其数值/时间仍由旧维度负责）。
        family 可为 drive,need,desire,emotion,sentiment,feeling,personality,self_knowledge,value,stance,relation,preference；kind 自由命名细分类别，不靠复制同名记录表达不同对象。
        drive 另填 direction；need 另填 level:"broad|specific"，specific 必须 parent_ref 指向已采用 drive/need；desire 必须 parent_ref 和 object_ref。
        emotion/sentiment/feeling 分别表示当前反应、持续情感、体验解释，另填 unresolved 布尔值和 current_state；强度变弱不等于事情解决。
        relation 必须 object_ref 为本轮已知对象ID、label、understanding、feeling，方向始终是自己看待该对象，称谓与理解分开。
        preference 必须 aspect（自由类别，如语言方式/思维方式/爱好）、orientation:"like|dislike|neutral|uncertain"、horizon:"long_term|short_term|contextual"、ownership:"self|accommodation"、context、exceptions；后两项可为空，配合行为不是自身喜好。
        采用 personality/self_knowledge/value/stance 必须另给 development:{account:"明确发展/采用说明",before:候选before原样或null,after:候选record原样,anchors:[{ref:"候选依据ID",quote:"原文中确实存在的短引文",interpretation:"你如何理解其与变化的联系"}]}。
        来源、前后态和版本由宿主核验，观点意义由主体解释。初始配置采用保留 initial_configuration 来源；发展依据不能把当前断言冒充过往经历。每次只改变指定记录，不覆盖全部人格。
        最多8个操作/轮、32个待处理候选、128个当前记录，身份材料最多6000字；容量不足会拒绝而不会静默截断。先处理候选、明确修订现有记录；完整记录和历史用 companion_internal_state get 的 offset 按页读取。
        整份报告仍须符合原16384字符上限，复杂变化可分轮提出。来源/对象名录只显示前16/32项，数量字段给出总量，可通过原角色/关系工具查询；未列出不是不存在。
    """.trimIndent()

    private fun values(o: JSONObject) = o.keys().asSequence().map { o.getJSONObject(it) }.toList()
    private fun copy(o: JSONObject) = JSONObject(o.toString())
    private fun fields(o: JSONObject, allowed: Set<String>) = require(o.keys().asSequence().all { it in allowed }) { "内在结构包含契约外字段" }
    private fun text(o: JSONObject, key: String, limit: Int): String {
        require(o.get(key) is String)
        return o.getString(key).also { require(it.isNotBlank() && it.length <= limit) { "$key 长度或内容不合法" } }
    }
    private fun optionalText(o: JSONObject, key: String, limit: Int) { if (o.has(key)) require(o.get(key) is String && o.getString(key).length <= limit) }
    private fun integer(o: JSONObject, key: String): Int {
        val n = o.get(key); require(n is Number && n.toDouble() >= 0 && n.toDouble() == n.toInt().toDouble()); return n.toInt()
    }
    private fun strings(a: JSONArray, limit: Int): List<String> {
        require(a.length() <= limit)
        val values = (0 until a.length()).map { require(a.get(it) is String); a.getString(it).also { v -> require(v.isNotBlank() && v.length <= 180) } }
        require(values.distinct().size == values.size); return values
    }
    internal fun hash(value: Any?): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun canonical(v: Any?): String = when(v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }
}
