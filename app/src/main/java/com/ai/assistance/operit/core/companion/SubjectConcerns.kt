package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Attention is a projection of existing life records. This index owns no goal or emotion. */
internal object SubjectConcerns {
    const val INDEX = "attention_index"
    val horizons = setOf("long_term", "short_term")
    val modes = setOf("mixed", "relevant", "rotation", "random")
    private val active = setOf("active", "considering", "uncertain")
    private fun archive(data: JSONObject) = requireNotNull((data as? InternalStateDocument)?.history)
    fun config(data: JSONObject): JSONObject = JSONObject(data.optJSONObject("concern_settings")?.toString()
        ?: """{"version":0,"long_term":2,"short_term":2,"old":1,"mode":"mixed","initialized":false}""")

    fun configure(data: JSONObject, patch: JSONObject) {
        SubjectContracts.fields(patch, setOf("expected_version", "long_term", "short_term", "old", "mode"))
        val old = config(data)
        SubjectContracts.expect(SubjectContracts.integer(patch, "expected_version") == old.getInt("version"),
            "expected_version", "VERSION_CHANGED", "便签配置已改变，先读取concern_config。")
        val next = JSONObject(old.toString())
        for (key in listOf("long_term", "short_term", "old")) {
            val value = SubjectContracts.integer(patch, key)
            // Slot counts are only exposure requests; actual text shares the model material budget.
            SubjectContracts.expect(value >= 0, key, "NEGATIVE_SLOTS", "便签数量须为非负整数；这不是保存条数上限。")
            next.put(key, value)
        }
        next.put("mode", SubjectContracts.choice(patch, "mode", modes))
        next.put("version", Math.addExact(old.getInt("version"), 1))
        data.put("concern_settings", next)
    }

    /** Missing optional fields preserve an existing concern; an ordinary progress report
     * must never accidentally unpin it or erase its original purpose. */
    fun prepare(old: JSONObject?, action: JSONObject, frame: InternalStateFrame): JSONObject {
        val next = JSONObject(action.toString())
        val prior = old?.getJSONObject("content")
        if (prior?.has("attention") == true) {
            for (key in listOf("text", "scope", "uncertainty", "status", "attention", "retention", "review_at", "purpose", "goal", "resolve", "ambition", "related"))
                if (!next.has(key) && prior.has(key)) next.put(key, prior.get(key))
            if (action.opt("attention") is JSONObject) {
                val merged = JSONObject(prior.getJSONObject("attention").toString())
                action.getJSONObject("attention").keys().forEach { key -> merged.put(key, action.getJSONObject("attention").get(key)) }
                next.put("attention", merged)
            }
        }
        val a = next.optJSONObject("attention") ?: run {
            SubjectContracts.expect(!next.has("attention"), "attention", "EXPECTED_OBJECT", "attention须为对象；省略时保留已存便签。")
            return next
        }
        SubjectContracts.fields(a, setOf("horizon", "brief", "importance", "focus", "pinned", "protected", "progress", "waiting", "tags"), "attention")
        SubjectContracts.choice(a, "horizon", horizons, "attention.horizon")
        for (key in listOf("brief", "importance", "focus")) SubjectContracts.text(a, key, path = "attention.$key")
        for (key in listOf("pinned", "protected")) SubjectContracts.expect(a.opt(key) is Boolean,
            "attention.$key", "EXPECTED_BOOLEAN", "需要true或false，不是字符串。")
        for (key in listOf("progress", "waiting")) if (a.has(key)) SubjectContracts.text(a, key, empty = true, path = "attention.$key")
        if (a.has("tags")) SubjectContracts.strings(SubjectContracts.array(a, "tags"), path = "attention.tags")
        val oldAttention = prior?.optJSONObject("attention")
        if (oldAttention != null) {
            val oldRow = requireNotNull(old)
            val changedPosition = oldAttention.getBoolean("pinned") &&
                (!a.getBoolean("pinned") || a.getString("horizon") != oldAttention.getString("horizon") ||
                    (prior.optString("status") in active && next.optString("status") !in active))
            val changedMeaning = oldAttention.getBoolean("protected") &&
                (!a.getBoolean("protected") || listOf("text", "scope", "purpose", "goal", "resolve", "ambition")
                    .any { prior.optString(it) != next.optString(it) } || oldAttention.getString("brief") != a.getString("brief"))
            if (changedPosition || changedMeaning) {
                SubjectContracts.expect(next.optString("attention_change").isNotBlank(), "attention_change", "EXPLICIT_CONCERN_CHANGE",
                    "常驻或本意已保护；先get section=concerns id=kind:ID读详情，明确填写attention_change说明完成、放下、转向或修订。进度可独立更新。")
                val read = frame.materialSources.values.any { raw -> JSONObject(raw).let { source ->
                    source.optBoolean("full_concern_read") && source.optJSONObject("locator")?.let {
                        it.optString("kind") == oldRow.getString("storage_kind") && it.optString("id") == oldRow.getString("id") &&
                            it.optInt("version") == oldRow.getInt("version")
                    } == true
                } }
                SubjectContracts.expect(read, "attention_change", "CONCERN_DETAIL_REQUIRED", "改变常驻或本意前须读取当前详情；简况不能替代原文。")
            }
        }
        if (next.has("attention_change")) SubjectContracts.text(next, "attention_change")
        return next
    }

    fun index(data: JSONObject, frame: InternalStateFrame, row: JSONObject) {
        val c = row.getJSONObject("content")
        val a = c.optJSONObject("attention") ?: return
        val parent = if (c.getString("status") in active) a.getString("horizon") + if (a.getBoolean("pinned")) ":pinned" else ":rotating" else "inactive"
        // Derived current heads are small; full text and all meaningful revisions remain
        // in the original life journal, not embedded into the request or snapshot.
        CategoryEvents.append(data, frame, JSONObject().put("storage_kind", INDEX)
            .put("id", row.getString("storage_kind") + ":" + row.getString("id")).put("parent", parent)
            .put("kind", row.getString("storage_kind")).put("target", row.getString("id"))
            .put("version", row.getInt("version")).put("owner", row.getString("owner")).put("at", row.getLong("at"))
            .put("brief", a.getString("brief").take(160)).put("status", c.getString("status"))
            .put("progress", a.optString("progress").take(120)).put("importance", a.getString("importance").take(120)) )
    }

    fun page(data: JSONObject, offset: Int): JSONObject = CategoryEvents.page(data, INDEX, offset)

    fun selected(data: JSONObject): List<JSONObject> {
        val settings = config(data)
        val cursor = data.optLong("material_query_cursor").coerceAtLeast(0)
        val history = archive(data)
        val result = mutableListOf<JSONObject>()
        for (horizon in horizons) {
            // No whole-history materialization even if a user asks for an enormous slot count.
            val slots = settings.getInt(horizon).coerceAtMost(SubjectMaterialBudget.MAX_TOKENS / 32)
            for (category in listOf("pinned", "rotating")) {
                val parent = "$horizon:$category"
                val count = history.categoryCount(INDEX, parent)
                if (count == 0L) continue
                val already = result.count { it.getJSONObject("content").getJSONObject("attention").getString("horizon") == horizon }
                val remaining = (slots - already).coerceAtLeast(0)
                var offset = if (category == "pinned") 0 else (cursor % count).toInt()
                var read = 0
                while (read < minOf(remaining.toLong(), count).toInt()) {
                    val size = minOf(20, remaining - read, (count - offset).toInt())
                    val rows = history.categoryPage(INDEX, offset, size, parent)
                    if (rows.isEmpty()) break
                    rows.forEach { entry -> CategoryEvents.row(data, entry.getString("kind"), entry.getString("target"))?.let(result::add) }
                    read += rows.size; offset = if (offset + rows.size >= count) 0 else offset + rows.size
                }
            }
        }
        return result
    }

    fun material(row: JSONObject, detail: Boolean = false): JSONObject {
        val c = row.getJSONObject("content")
        val a = c.getJSONObject("attention")
        val view = JSONObject().put("brief", a.getString("brief").take(160)).put("status", c.getString("status"))
            .put("horizon", a.getString("horizon")).put("pinned", a.getBoolean("pinned")).put("protected", a.getBoolean("protected"))
        for (key in listOf("importance", "focus", "progress", "waiting")) if (a.has(key)) view.put(key, a.getString(key).take(120))
        val kind = row.getString("storage_kind"); val id = row.getString("id")
        return JSONObject().put("id", "material:$kind:$id:${row.getInt("version")}")
            .put("type", "subject_$kind").put("owner", row.getString("owner")).put("author", row.getString("owner"))
            .put("at", row.getLong("at")).put("status", "retrieved").put("not_new_occurrence", true)
            .put("subject_private_material", true).put("selection_reason", row.optString("selection_reason", "ROTATION"))
            .put("text", view.toString()).put("excerpt", true).put("full_concern_read", detail)
            .put("origin", c.optString("original_source", row.optString("source"))).put("detail", "companion_internal_state get section=concerns id=$kind:$id")
            .put("locator", JSONObject().put("kind", kind).put("id", id).put("version", row.getInt("version")))
    }

    fun validateCapacity(data: JSONObject) {
        val settings = config(data)
        val history = archive(data)
        for (horizon in horizons) {
            var count = history.categoryCount(INDEX, "$horizon:pinned")
            val journal = data.optJSONObject("category_commit")?.optJSONArray("rows") ?: JSONArray()
            val edits = (0 until journal.length()).map { journal.getJSONObject(it) }.filter { it.optString("storage_kind") == INDEX }
                .associateBy { it.getString("id") }
            edits.values.forEach { row ->
                val old = history.categoryRow(INDEX, row.getString("id"))
                if (old?.optString("parent") == "$horizon:pinned") count--
                if (row.getString("parent") == "$horizon:pinned") count++
            }
            SubjectContracts.expect(count <= settings.getInt(horizon), "attention.pinned", "PINNED_SLOTS",
                "常驻数量超过${horizon}配置；先增加名额或明确释放原便签，不会自动挤掉常驻项。")
        }
    }

    /** Resolve linked members at their CURRENT versions. Historical references describe
     * the past and cannot authorize an edit to a member that was never actually delivered. */
    fun connect(data: JSONObject, payload: JSONObject, concerns: List<JSONObject>, chat: String, now: Long) {
        if (concerns.isEmpty()) return
        fun add(target: JSONArray, row: JSONObject) {
            if ((0 until target.length()).none { target.getJSONObject(it).optString("id") == row.optString("id") })
                target.put(row.put("selection_reason", "CONCERN_LINK"))
        }
        val refs = concerns.flatMap { row ->
            val a = row.getJSONObject("content").optJSONArray("related") ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it) }
        }.distinctBy { it.getString("kind") + ":" + it.getString("id") }
        // Bound preparation as well as final token selection. Rotate related members when
        // many are present; links and their original versions remain in full details.
        val start = if (refs.isEmpty()) 0 else Math.floorMod(data.optLong("material_query_cursor"), refs.size.toLong()).toInt()
        for (ref in (refs.drop(start) + refs.take(start)).take(8)) {
            val id = ref.getString("id")
            when (ref.getString("kind")) {
                "inner" -> InnerStructure.record(data, id)?.let { row ->
                    if (row.getJSONObject("content").getString("status") == "active") {
                        val inner = payload.getJSONObject("inner_structure")
                        val supplied = listOf("identity", "selected").any { field ->
                            val rows = inner.getJSONArray(field)
                            (0 until rows.length()).any { rows.getJSONObject(it).optString("id") == id }
                        }
                        if (supplied) return@let
                        if (SubjectMaterialBudget.estimate(row.toString()) <= 384)
                            add(inner.getJSONArray("selected"), InnerStructure.identityMaterial(row))
                        else {
                            val parts = inner.getJSONArray("record_fragments")
                            if ((0 until parts.length()).none { parts.getJSONObject(it).optString("record_id") == id })
                                parts.put(InnerReadProgress.recordFragment(data, row).put("selection_reason", "CONCERN_LINK"))
                        }
                    }
                }
                "episode" -> CategoryEvents.row(data, "episode", id)?.let {
                    add(payload.getJSONObject("category_events").getJSONArray("episodes"), CategoryEvents.requestProjection(CategoryEvents.timeView(data, it, now)))
                }
                "foundation" -> InnerFoundations.find(id)?.let { definition ->
                    if (definition.family == "drive") add(payload.getJSONObject("foundation").getJSONArray("drive_values"), InnerFoundations.member(data, id, now))
                }
                "experience", "direction" -> CategoryEvents.row(data, ref.getString("kind"), id)?.let {
                    add(payload.getJSONArray("retrieved_materials"), LifeUnderstandings.material(it))
                }
            }
        }
        val keys = concerns.map { it.getString("storage_kind") + ":" + it.getString("id") }.toSet()
        val query = keys.joinToString(" ")
        val unions = InnerInteractions.material(data, chat = chat, at = now, input = query).getJSONArray("items")
        for (i in 0 until unions.length()) {
            val row = unions.getJSONObject(i)
            val members = row.getJSONObject("content").getJSONArray("members")
            if ((0 until members.length()).any { members.getJSONObject(it).let { m -> m.getString("kind") + ":" + m.getString("id") in keys } })
                add(payload.getJSONObject("unions").getJSONArray("items"), row)
        }
        ActionUnderstanding.materials(data, query, chat).forEach { material ->
            val content = JSONObject(material.getString("text"))
            val links = content.optJSONArray("historical_intention_refs") ?: JSONArray()
            if ((0 until links.length()).any { links.getJSONObject(it).let { m -> m.getString("kind") + ":" + m.getString("id") in keys } })
                add(payload.getJSONArray("retrieved_materials"), material)
        }
    }

    val briefInstructions = "持续牵挂沿用life记录。使用前可get section=contract id=concerns读取完整字段与规则；get section=concerns按direction:ID/experience:ID看详细原文，concern_config查数量。常驻简况和旧事浮现不等于行动命令；可与内在状态、联合和真实回执双向影响。进度可更新；改变锁定的本意或常驻位置、完成/暂停常驻，须先读当前详情，再用life.attention_change明确说明。遗漏可选关注字段保留原值，旧原文保留历史；稳定人格仍走原候选采用。需要后续活动仍须用原工具真实预约。"

    val instructions = """
        [持续牵挂便签]
        便签沿用life的experience/direction原记录，不是另一套待办。需要持续挂心时才在life条目附attention:{horizon:"long_term|short_term",brief:"简况",importance:"长期重要程度",focus:"此刻注意",pinned:true,protected:true,progress?:"实际进展及未核实之处",waiting?:"等待条件",tags?:["野心","目标","决定"]}。不把每个念头自动变成目标。
        常驻简况在正常聊天和自主活动共用的材料预算内提供；详情默认不注入。get section=concerns分页看索引，id="direction:ID"或"experience:ID"读原文、来源、当前进度；category_versions id=kind:ID查变化。简况被省略不等于已处理。不会因便签存在或分数高强制行动、自动唤醒或续约。
        pinned固定位置，protected保护text/scope/purpose/goal/resolve/ambition与brief；进度、等待、重要程度和当前注意分别更新。已有便签可用life:[{kind,id,expected_version,evidence:[本轮来源],attention:{progress:"新进度"}}]补丁更新；省略核心原文字段、attention内字段和既存可选目的字段会保留，不要求重抄全文。related省略保留历史关联，显式提供须实际读取当前版本。改变本意、取消常驻、改变常驻长短期、结束或暂停常驻前，先读当前详情，并在life条目写attention_change明确说明；版本仍须匹配。旧原文与变更历史保留，不因一轮摘要或新事分数高自动换掉。
        便签仍用原kind/id/version参与related、unions和__operit_action.intention_refs。情绪/情感/感受/驱力/需要/欲望、人格/自我认识/价值/关系/偏好可以影响牵挂；牵挂与真实进展也能反过来影响这些部分，多个方向可以拉扯。按实际理解通过原life/episodes/foundation/inner/unions/action_feedback采用，不预设成功必满足或失败必坚持。人格等仍走原候选跨轮采用。行动回执与进度自述分开，需预约仍用既有工具，以成功回执为准。
        数量用get section=concern_config查看，初始长期2/短期2/旧事1；是本轮位置而非保存上限。可在轮末attention_config:{expected_version,long_term,short_term,old,mode:"mixed|relevant|rotation|random"}明确调整。所有材料共用总预算；配置很多位置不保证全文全部送达，遗漏见material_budget。旧事只是一次再考虑的机会，可继续、暂缓、放下，也可没有新结论。
    """.trimIndent()
}
