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
    private val common = setOf("id", "family", "kind", "description", "object_ref", "context", "exceptions", "status", "legacy_ref", "experience_refs")

    private fun existingRoot(data: JSONObject): JSONObject? =
        if (!data.has("inner_structure")) null else data.getJSONObject("inner_structure").also {
            require(it.getInt("schema") == SCHEMA) { "不支持的内在结构版本" }
        }

    fun root(data: JSONObject): JSONObject {
        existingRoot(data)?.let { return it }
        // No psychological values are inferred from old numbers or free-form character text.
        return JSONObject().put("schema", SCHEMA).put("records", JSONObject()).put("pending", JSONObject())
            .put("history_sequence", 0L).put("legacy_policy", "preserved_in_original_authority")
            .also {
                if (data is InternalStateDocument) {
                    it.remove("records"); it.remove("pending"); it.put("storage", "archive_v1").put("family_index", 1)
                }
                data.put("inner_structure", it)
            }
    }

    // Explicit full enumeration is for export/contract inspection, never normal request assembly.
    private fun enumerate(rows: InnerRows): List<JSONObject> = buildList {
        var offset = 0
        while (true) {
            val page = rows.page(offset, 20)
            addAll(page)
            if (page.size < 20) break
            offset += page.size
        }
    }
    fun records(data: JSONObject): List<JSONObject> = enumerate(InnerRows(data, false))
    fun pending(data: JSONObject): List<JSONObject> = enumerate(InnerRows(data, true))
    fun record(data: JSONObject, id: String) = InnerRows(data, false).optJSONObject(id)
    fun candidate(data: JSONObject, id: String) = InnerRows(data, true).optJSONObject(id)
    fun identity(data: JSONObject) = records(data).filter {
        it.getJSONObject("content").getString("family") in identityFamilies &&
            it.getJSONObject("content").getString("status") == "active"
    }
    fun versions(data: JSONObject) = records(data).associate { it.getString("id") to it.getInt("version") }
    fun pendingBindings(data: JSONObject) = pending(data).associate { it.getString("id") to hash(it) }
    fun evidenceToRestore(data: JSONObject, actions: JSONArray): List<String> {
        val ids = (0 until actions.length()).map { actions.getJSONObject(it).optString("id") }.toSet()
        val proposed = (0 until actions.length()).map { actions.getJSONObject(it) }.filter { it.optString("op") == "propose" }
            .flatMap { strings(it.getJSONArray("evidence"), 6) }
        return (proposed + ids.mapNotNull { candidate(data, it) }.flatMap {
            it.getJSONObject("bindings").keys().asSequence().filter { key -> !key.startsWith("config:") && !key.startsWith("relation:") }.toList()
        }).filter { !it.startsWith("config:") && !it.startsWith("relation:") && !it.startsWith("declaration:") }.distinct()
    }

    fun apply(data: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long): Boolean {
        if (actions.length() == 0) return false
        val root = root(data)
        val records = InnerRows(data, false, frame)
        val pending = InnerRows(data, true, frame)
        val touched = mutableSetOf<String>()
        for (i in 0 until actions.length()) {
            try {
            val action = actions.getJSONObject(i)
            val id = text(action, "id", 64)
            require(touched.add(id)) { "同轮不能重复处理同一候选" }
            when (text(action, "op", 20)) {
                "propose" -> {
                    fields(action, setOf("op", "id", "record", "expected_version", "origin", "evidence", "reason", "formed_at", "replaces"))
                    require(!pending.used(id)) { "候选已存在；修订须使用新的候选版本标识" }
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
                    require(origin in setOf("initial_configuration", "subject_development", "user_proposal", "model_suggestion", EstablishedContent.ORIGIN))
                    val refs = strings(action.getJSONArray("evidence"), 6)
                    require(refs.isNotEmpty() || origin == EstablishedContent.ORIGIN) { "候选需要真实来源" }
                    val bindings = JSONObject()
                    refs.forEach { ref -> bindings.put(ref, source(data, frame, ref)) }
                    if (origin == EstablishedContent.ORIGIN) {
                        val formedAt = if (action.has("formed_at") && !action.isNull("formed_at")) action.getLong("formed_at") else null
                        bindings.put("declaration:$id", EstablishedContent.declaration(data, frame.roleId, id, content.getString("family"), now, formedAt))
                    } else require(!action.has("formed_at")) { "历史形成时间仅用于已有内容声明" }
                    if (origin == "initial_configuration") require(refs.any { it.startsWith("config:") })
                    if (origin == "user_proposal") require(refs.any {
                        bindings.getJSONObject(it).getString("source") in setOf("user_message", "user_edit", "user_relation_proposal")
                    })
                    val proposal = JSONObject().put("id", id).put("record", content).put("expected_version", expected)
                        .put("origin", origin).put("bindings", bindings).put("reason", reason(action, content))
                        .put("before", before?.getJSONObject("content")?.let(::copy) ?: JSONObject.NULL)
                        .put("frame_id", frame.id).put("source_frame_at", frame.at).put("created_at", now).put("owner", frame.roleId)
                    if (action.has("replaces")) {
                        val previousId = text(action, "replaces", 64)
                        val previous = requireNotNull(pending.optJSONObject(previousId)) { "待修订候选不存在或已被采用/撤回" }
                        require(previous.getString("owner") == frame.roleId && frame.innerPending[previousId] == hash(previous)) { "须读取待修订候选的准确版本" }
                        require(previous.getJSONObject("record").getString("id") == target) { "修订不能换成另一条内容" }
                        proposal.put("replaces", previousId).put("candidate_version", previous.optInt("candidate_version", 1) + 1)
                        history(data, "superseded", previousId, previous, proposal, frame, now, proposal.getString("reason"))
                        pending.remove(previousId)
                    } else proposal.put("candidate_version", 1)
                    pending.put(id, proposal)
                    history(data, "proposed", id, JSONObject.NULL, proposal, frame, now, proposal.getString("reason"))
                }
                "adopt", "discard" -> {
                    fields(action, setOf("op", "id", "reason", "development"))
                    val proposal = requireNotNull(pending.optJSONObject(id)) { "候选不存在" }
                    require(proposal.getString("owner") == frame.roleId &&
                        frame.innerPending[id] == hash(proposal) && proposal.getString("frame_id") != frame.id) { "只能处理本轮读取到的先前候选" }
                    val reason = if (action.getString("op") == "adopt") reason(action, proposal.getJSONObject("record")) else text(action, "reason", 600)
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
                        val binding = proposal.getJSONObject("bindings").getJSONObject(ref)
                        if (binding.optString("source") == EstablishedContent.ORIGIN) {
                            require(binding.getString("owner") == frame.roleId && binding.getString("candidate_id") == id)
                        } else if (binding.optString("source") == "retrieved_material") {
                            // This is the actual excerpt received with the original proposal,
                            // preserved as provenance; a later memory edit is not the old source.
                            require(hash(binding.getJSONObject("snapshot")) == binding.getString("hash"))
                        } else require(hash(source(data, frame, ref)) == hash(binding)) { "候选原始来源已变化或不可用" }
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
                    CategoryEvents.linkAdopted(data, frame, content, now)
                    if (content.getString("family") in identityFamilies) root.put("identity_established", true)
                    history(data, "adopted", id, old ?: JSONObject.NULL, adopted, frame, now, reason,
                        action.optJSONObject("development"))
                    pending.remove(id)
                }
                else -> error("未知内在候选操作")
            }
            } catch (failure: Exception) {
                if (failure is InternalStateReportRejected) throw failure
                throw InternalStateReportDiagnostics.issue(failure, "inner[$i]")
            }
        }
        return true
    }

    private fun development(data: JSONObject, frame: InternalStateFrame, proposal: JSONObject, value: JSONObject) {
        fields(value, setOf("account", "before", "after", "anchors", "candidate_hash", "before_version"))
        text(value, "account", 1000)
        if (value.has("candidate_hash")) {
            require(!value.has("before") && !value.has("after") &&
                value.getString("candidate_hash") == hash(proposal) &&
                value.getInt("before_version") == proposal.getInt("expected_version")) { "采用引用须对应已完整读取的准确候选与前态版本" }
        } else require(hash(value.get("before")) == hash(proposal.get("before")) &&
            hash(value.getJSONObject("after")) == hash(proposal.getJSONObject("record"))) { "人格变化说明必须对应准确前态和后态" }
        val anchors = value.getJSONArray("anchors")
        // Declared established content has a real submission/adoption provenance, not a
        // fabricated old quotation. Explicit adoption still occurs only after a later read.
        if (proposal.getString("origin") == EstablishedContent.ORIGIN) {
            require(anchors.length() == 0) { "声明承接不冒充找到了旧原文；原文路径请使用实际来源" }
            return
        }
        require(anchors.length() in 1..6)
        val seen = mutableSetOf<String>()
        for (i in 0 until anchors.length()) {
            val anchor = anchors.getJSONObject(i)
            fields(anchor, setOf("ref", "quote", "interpretation"))
            val ref = text(anchor, "ref", 180)
            require(seen.add(ref) && proposal.getJSONObject("bindings").has(ref)) { "人格依据必须绑定候选的原始来源" }
            val raw = proposal.getJSONObject("bindings").getJSONObject(ref).optJSONObject("snapshot")?.getString("text")
                ?: sourceText(data, frame, ref)
            require(raw.contains(text(anchor, "quote", 600))) { "人格依据引用不在原文中" }
            text(anchor, "interpretation", 600)
            if (proposal.getString("origin") == "initial_configuration" && ref.startsWith("config:")) continue
            val binding = proposal.getJSONObject("bindings").getJSONObject(ref)
            if (binding.optString("source") == "retrieved_material") {
                val snapshot = binding.getJSONObject("snapshot")
                require(snapshot.optLong("at", Long.MAX_VALUE) < proposal.getLong("source_frame_at"))
                // A prior material may inform development; it remains attributed material,
                // not a claim that a memory summary is an original personal experience.
                continue
            }
            val event = InternalStateEngine.rows(data, "events").firstOrNull { it.getString("id") == ref }
            // A user-supplied original is evidence of the submitted text, not a fabricated old occurrence.
            if (event?.optString("source") == "user_edit" && event.has("provided_source") &&
                event.getLong("at") == proposal.getLong("source_frame_at")) continue
            require(event != null && event.getLong("at") < proposal.getLong("source_frame_at") &&
                event.getString("source") !in setOf("state_report", "time")) { "当前断言或状态变化不能冒充先前发展依据" }
        }
    }

    private fun validateContent(row: JSONObject, records: InnerRows, data: JSONObject, frame: InternalStateFrame) {
        val family = text(row, "family", 30)
        require(family in families)
        val specific = when (family) {
            "drive" -> setOf("direction")
            "need" -> setOf("level", "parent_ref", "semantic_rule", "strength", "objects", "goals")
            "desire" -> setOf("parent_ref")
            "emotion", "sentiment", "feeling" -> setOf("unresolved", "current_state")
            "relation" -> setOf("label", "understanding", "feeling")
            "preference" -> setOf("orientation", "horizon", "ownership", "aspect")
            else -> emptySet()
        }
        fields(row, common + specific)
        CategoryEvents.validateLinks(data, row)
        text(row, "id", 64); text(row, "kind", 80); text(row, "description", Int.MAX_VALUE)
        require(text(row, "status", 20) in setOf("active", "retired"))
        optionalText(row, "object_ref", 160); optionalText(row, "context", Int.MAX_VALUE); optionalText(row, "exceptions", Int.MAX_VALUE)
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
            "drive" -> text(row, "direction", Int.MAX_VALUE)
            "need" -> {
                require(text(row, "level", 20) in setOf("broad", "specific"))
                if (row.getString("level") == "specific" || row.has("parent_ref")) parent(setOf("drive", "need"))
                if (row.has("semantic_rule")) {
                    require(text(row, "semantic_rule", 20) in setOf("life", "survival"))
                    if (row.getString("semantic_rule") == "life") require(row.getString("level") == "broad")
                    else {
                        require(row.getString("level") == "specific" && !row.has("legacy_ref"))
                        val p = records.getJSONObject(row.getString("parent_ref")).getJSONObject("content")
                        require(p.getString("family") == "need" && p.getString("level") == "broad" && p.optString("semantic_rule") == "life")
                        val strength = row.get("strength")
                        require(strength is Number && strength.toDouble().isFinite() && strength.toDouble() >= 0)
                        CategoryEvents.strings(row.getJSONArray("objects"), 8)
                        CategoryEvents.strings(row.getJSONArray("goals"), 8)
                    }
                }
                if (row.optString("semantic_rule") != "survival") require(!row.has("strength") && !row.has("objects") && !row.has("goals"))
                val old = records.optJSONObject(row.getString("id"))?.getJSONObject("content")
                if (old?.has("semantic_rule") == true) require(old.getString("semantic_rule") == row.optString("semantic_rule")) {
                    "已采用的需要特殊语义不能无声移除或更换"
                }
            }
            "desire" -> { parent(setOf("drive", "need")); text(row, "object_ref", 160) }
            "emotion", "sentiment", "feeling" -> {
                require(row.get("unresolved") is Boolean); text(row, "current_state", Int.MAX_VALUE)
            }
            "relation" -> {
                require(text(row, "object_ref", 160) in frame.innerObjects) { "关系对象必须使用已存在的对象身份" }
                require(row.getString("object_ref") != frame.roleId)
                text(row, "label", 100); text(row, "understanding", Int.MAX_VALUE); text(row, "feeling", Int.MAX_VALUE)
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
        require(!records.conflict(row)) { "同方向关系、特殊需要或同类同对象同情境的需要已有当前权威，请修订稳定ID" }
    }

    internal fun source(data: JSONObject, frame: InternalStateFrame, ref: String): JSONObject {
        frame.materialSources[ref]?.let { raw ->
            val material = JSONObject(raw)
            return JSONObject().put("hash", hash(material)).put("source", "retrieved_material")
                .put("snapshot", material)
                .put("locator", material.getJSONObject("locator")).put("type", material.getString("type"))
                .put("author", material.opt("author") ?: JSONObject.NULL).put("at", material.opt("at") ?: JSONObject.NULL)
                .put("not_new_occurrence", true)
        }
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
        if (ref in frame.materialSources) JSONObject(frame.materialSources.getValue(ref)).getString("text")
        else if (ref.startsWith("config:") || ref.startsWith("relation:")) requireNotNull(frame.innerSources[ref])
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

    /** Search all active adopted content before taking a bounded page for the shared budget. */
    fun material(data: JSONObject, input: String, cursor: Long): JSONObject {
        val records = InnerRows(data, false)
        val pending = InnerRows(data, true)
        val catalogue = records.rotating(cursor)
        val candidates = pending.rotating(cursor)
        // Paging before matching hid relevant beliefs beyond the rotating eight rows. Keep
        // relevance first; rotation supplies additional exposure without enlarging the input.
        val relatedIdentity = records.relevant(input, "identity")
        val relatedOther = records.relevant(input, "other")
        fun selected(parent: String, related: List<JSONObject>) = (related + records.rotating(cursor, parent))
            .filter { it.getJSONObject("content").getString("status") == "active" }
            .distinctBy { it.getString("id") }.take(8)
        val identity = selected("identity", relatedIdentity)
        val others = selected("other", relatedOther)
        return JSONObject().put("schema", SCHEMA)
            // Host-only handoff: the shared selector removes this before serializing. Without
            // it, splitting long records into a fragment lane loses their relevance ordering.
            .put("_relevance_first", JSONArray(listOfNotNull(relatedIdentity.firstOrNull(), relatedOther.firstOrNull())
                .map { it.getString("id") }))
            .put("identity", JSONArray(identity.filter { SubjectMaterialBudget.estimate(it.toString()) <= 384 }.map(::identityMaterial)))
            .put("selected", JSONArray(others.filter { SubjectMaterialBudget.estimate(it.toString()) <= 384 }.map(::identityMaterial)))
            .put("record_fragments", JSONArray((identity + others).distinctBy { it.getString("id") }
                .filter { SubjectMaterialBudget.estimate(it.toString()) > 384 }.map { InnerReadProgress.recordFragment(data, it) }))
            .put("pending", JSONArray(candidates.filter { SubjectMaterialBudget.estimate(it.toString()) <= 384 }))
            .put("pending_fragments", JSONArray(candidates.filter { SubjectMaterialBudget.estimate(it.toString()) > 384 && !InnerReadProgress.complete(data, it) }
                .map { InnerReadProgress.fragment(data, it) }))
            .put("pending_read", JSONArray(candidates.filter { InnerReadProgress.complete(data, it) }.map {
                JSONObject().put("id", it.getString("id")).put("hash", hash(it)).put("target", it.getJSONObject("record").getString("id"))
                    .put("notice", "该准确版本已在此前正常请求完整提供，可重新查阅后决定；不是自动采用")
            }))
            .put("pending_ids", JSONArray(candidates.map { it.getString("id") }))
            .put("catalogue", JSONArray(catalogue.map {
                JSONObject().put("id", it.getString("id")).put("version", it.getInt("version"))
                    .put("family", it.getJSONObject("content").getString("family"))
                    .put("status", it.getJSONObject("content").getString("status"))
            })).put("record_count", records.count()).put("pending_count", pending.count())
            .put("selection", "从全部有效已采用条目的正文与情境按字词相关性检索，轮换补充，再按统一材料预算选入；未选入仍有效，原文及全部候选可用 get 分页或按ID读取，实际读到后才能采用")
    }
    private fun identityMaterial(row: JSONObject) = JSONObject().put("id", row.getString("id"))
        .put("content", copy(row.getJSONObject("content")))
        .put("version", row.getInt("version")).put("origin", row.getString("origin")).put("source", row.getString("source"))
        .put("evidence_ids", JSONArray(row.optJSONObject("evidence")?.keys()?.asSequence()?.toList().orEmpty()))
        .also { if (SubjectRestDreams.privateProvenance(row)) it.put("subject_private_material", true) }

    fun page(data: JSONObject, offset: Int): JSONObject {
        val records = InnerRows(data, false)
        val pending = InnerRows(data, true)
        return JSONObject().put("records", JSONArray(records.page(offset))).put("pending", JSONArray(pending.page(offset)))
            .put("record_count", records.count()).put("pending_count", pending.count())
            .put("next_offset", if (maxOf(records.count(), pending.count()) > offset + 8) offset + 8 else JSONObject.NULL)
    }

    fun guard(data: JSONObject): String = if (InnerRows(data, false).count("identity") == 0L && data.optJSONObject("inner_structure")?.optBoolean("identity_established") != true)
        "尚未采用持续人格，原初始配置仍可用于正常身份与表达。配置、用户建议都不是主体已采用的发展。"
    else "持续身份已建立：已采用的人格、自我认识、价值和立场是主要依据；本轮选入部分见 inner_structure.identity，预算未选入的部分仍有效，先用 get 分页查阅，不能用初始模板补写其内容。" +
        "旧人设、导入卡、世界书语气及后续用户配置仅作为初始参考或新建议，不能覆盖已采用内容；明确停用的认识也不能从模板自动恢复。" +
        "其中身份事实、工具契约、权限与运行约束仍有效；不能把人格采用当作新授权。"

    val instructions = """
        [内在结构候选契约 v1]
        inner_structure 中的记录是主体自己的有来源认识，不是客观事实或他人的同意。思维方式只记录倾向与方法，不保存隐藏思维链。
        在同一 operit_state_report 增加可选 inner:[...]，缺省或空数组保持所有新结构；不增加另一份报告。
        提出：{op:"propose",id:"候选ID",record:{...},expected_version:已有记录版本或0,origin:"initial_configuration|subject_development|user_proposal|model_suggestion",evidence:["已读本主体事件ID或本轮来源ID"],reason:"简短依据"}。历史原文可经 get 分页读取后引用；不能引用本帧开始之后发生的事，也不能猜测未读经历。
        未采用候选可修订：用新id和replaces:"旧候选ID"提出完整新版，须先读取旧版；旧版保留且撤出待处理，新版不继承已读资格。已采用记录不得直接修改；仍须以当前版本为before提出新候选。完整description/context/exceptions等原文保存，标题或预览不是替代；精简只能另提建议，不能在采用时换成自己的摘要。
        采用先前候选：{op:"adopt",id:"候选ID",reason:"明确采用理由"}；放下候选用 op:"discard"。不能同轮提出并采用；用户说“你喜欢”只是候选来源，不能当作已采用偏好。
        record 必填 id,family,kind,description,status:"active|retired"；可带 object_ref,context,exceptions,legacy_ref（原数值维度ID，迁移后只作历史来源索引；当前数值与时间不再由旧维度计算）。
        family 可为 drive,need,desire,emotion,sentiment,feeling,personality,self_knowledge,value,stance,relation,preference；kind 自由命名细分类别，不靠复制同名记录表达不同对象。
        drive 另填 direction；need 另填 level:"broad|specific"，specific 必须 parent_ref 指向已采用 drive/need；desire 必须 parent_ref 和 object_ref。
        emotion/sentiment/feeling 分别表示当前反应、持续情感、体验解释，另填 unresolved 布尔值和 current_state；强度变弱不等于事情解决。
        relation 必须 object_ref 为本轮已知对象ID、label、understanding、feeling，方向始终是自己看待该对象，称谓与理解分开。
        preference 必须 aspect（自由类别，如语言方式/思维方式/爱好）、orientation:"like|dislike|neutral|uncertain"、horizon:"long_term|short_term|contextual"、ownership:"self|accommodation"、context、exceptions；后两项可为空，配合行为不是自身喜好。
        采用 personality/self_knowledge/value/stance 必须另给 development:{account:"明确发展/采用说明",before:候选before原样或null,after:候选record原样,anchors:[{ref:"候选依据ID",quote:"原文中确实存在的短引文",interpretation:"你如何理解其与变化的联系"}]}。
        来源、前后态和版本由宿主核验，观点意义由主体解释。初始配置采用保留 initial_configuration 来源；发展依据不能把当前断言冒充过往经历。每次只改变指定记录，不覆盖全部人格。
        候选、采用与保存不设业务条数上限。单次请求和模型窗口仍有限；大批内容分批提交、保留进度，不丢弃尾部。完整记录和候选用 companion_internal_state get 的 offset 按页或ID读取；未送入本轮的候选不能采用。
        长候选的 pending_fragments 是同一完整原文的连续片段，不是摘要；hash 绑定准确候选版本。宿主只登记实际请求返回后已提供的片段，完整提供前不能采用。可用 get section=inner_chunk id=候选ID offset=next_offset 按需继续，不能用编造片段补齐。pending_read 表示该版本已完整提供，仍由你决定是否采用或重新查阅。人格采用的大段 before/after 可改为 development:{account:说明,candidate_hash:候选完整hash,before_version:候选expected_version,anchors:[...]}，保存的始终是原候选全文，不是说明的摘要。
        恢复已有确定人格、立场、关系、偏好等，可用 origin:"established_import"，evidence:[]，不伪造历史原文。只在用户明确开启的48小时窗口内提交新候选；formed_at 可省略，提交时间不代表形成时间。人格等采用时仍给 development 的准确 before/after 和 account，anchors:[]。按时提交的候选在截止后仍可采用；改写须新候选标识，不继承旧版本已读事实。get section:inner_pending 查询候选。
        record_fragments 是已采用完整内容的连续原文片段，绝不是替换原文的摘要；同一hash/version的片段共同属于一份内容。可用 get section=inner_record_chunk id=记录ID offset=next_offset 继续读取。片段未出现的限定和例外仍有效，不能猜测它们或退回初始模板。
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
    private fun reason(action: JSONObject, content: JSONObject): String {
        if (content.getString("family") == "need" && content.optString("semantic_rule") == "survival") {
            optionalText(action, "reason", 600)
            return action.optString("reason", "")
        }
        return text(action, "reason", 600)
    }
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
    internal fun serialized(value: Any?): String = canonical(value)
    private fun canonical(v: Any?): String = when(v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }
}
