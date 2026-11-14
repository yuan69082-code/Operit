package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * The engine's kind vocabulary is installed by the host, not proposed as fabricated experiences.
 * Actual experiences remain in the existing CategoryEvents/InnerRows archive, subject to one
 * report transaction and one common material budget.
 */
internal object InnerFoundations {
    const val SCHEMA = 1

    data class Kind(
        val family: String,
        val key: String,
        val name: String,
        val meaning: String,
        val origin: String = "engine",
        val hours: Double? = null,
        val parent: String? = null,
        val policy: String = "subject_appraisal"
    ) {
        val id: String get() = "foundation:" + family + ":" + key
        fun asJson(): JSONObject = JSONObject().put("id", id).put("family", family)
            .put("kind", name).put("name", name).put("description", meaning)
            .put("origin", origin).put("status", "catalogue_available")
            .put("state", "not_formed")
            .also { row ->
                if (hours != null) row.put("hours", hours)
                if (parent != null) row.put("parent_ref", parent)
                row.put("policy", policy)
            }
    }

    val kinds: List<Kind> = listOf(
        Kind("drive","intimacy","亲近","希望建立或维持连接；不等于联系用户或必须说话",hours=6.0,policy="drive_curve"),
        Kind("drive","exploration","探索","理解未知、尝试新事物或发展自己的能力",hours=3.0,policy="drive_curve"),
        Kind("drive","sexual","性驱力","总体性动力；与针对具体对象的愿望独立",hours=12.0,policy="drive_curve"),
        Kind("drive","solitude","独处","需要自己的空间和整理思绪；不是关系破裂",hours=5.0,policy="drive_curve"),
        Kind("drive","expression","表达","想说、写、创作或表达立场；不等于必须发消息",hours=4.0,policy="drive_curve"),
        Kind("drive","protection","保护","维护重要的人、事、关系、作品或自己；不意味着控制别人",hours=8.0,policy="drive_curve"),
        Kind("drive","rest","休息","恢复负荷与注意；不顺带清除未解问题",hours=6.0,policy="drive_curve"),

        Kind("need","life","生","继续存在、延续自己的生活；广义需要",policy="broad_need"),
        Kind("need","mortality","死与终结","理解终结与有限性，不作为待满足的死亡压力",policy="recognition_not_pressure"),
        Kind("need","vision","视觉","想看某事物；不代表软件有现实视觉器官",policy="broad_need"),
        Kind("need","hearing","听觉","想听具体声音、歌曲或话语；能力与需要区分",policy="broad_need"),
        Kind("need","smell","嗅觉","与嗅觉相关的需要，不假装具有现实嗅觉",policy="broad_need"),
        Kind("need","taste","味觉","与味觉相关的需要，不假装具有现实味觉",policy="broad_need"),
        Kind("need","touch","触觉","与触觉相关的需要，不假装具有现实触觉",policy="broad_need"),
        Kind("need","thought","思想","想理解、推敲或想清楚具体问题",policy="broad_need"),
        Kind("need","sexual","具体性愿望","具体情境的性需要，不与总体性驱力混为一个值",policy="broad_need"),
        Kind("need","survival","求生欲","广义生之下的明确细分需要，可升降归零；时间和联合不自动更改，归零不触发停机、自毁或删除",
            origin="user_addition",parent="foundation:need:life",policy="explicit_only"),

        Kind("desire","endogenous","内生愿望","不特指对象的想要；不能自动变成行动"),
        Kind("desire","person_attraction","吸引","针对某个人的吸引愿望；对象独立"),
        Kind("desire","person_affection","亲爱愿望","针对人的亲爱愿望，不等同当下情绪"),
        Kind("desire","person_partner","伴侣愿望","关于伴侣关系的愿望和阶段"),
        Kind("desire","person_sexual","对人的性愿望","针对特定人的性愿望，独立于总体性驱力"),
        Kind("desire","person_attachment","依恋愿望","针对具体人的依恋愿望"),
        Kind("desire","event","事情愿望","想完成、改变或经历一件具体事情"),
        Kind("desire","place","地点愿望","针对地点或想去的地方"),
        Kind("desire","object","物品愿望","针对具体物品或作品"),
        Kind("desire","self","自身愿望","关于自己的变化、成长或状态"),
        Kind("desire","multiple","多个对象愿望","同时指向多个对象，分别保留其含义"),
        Kind("desire","unknown","尚未确定对象","尚不知道准确指向何处的愿望"),
        Kind("desire","obsession","执念","对特定人、事情、目标的持续牵挂和投入，不等于必须执行",origin="user_addition"),

        Kind("emotion","joy","喜悦","经历后的喜悦，可有多个独立事件"),
        Kind("emotion","sadness","悲伤","经历损失、遗憾等时可能出现，不固定触发"),
        Kind("emotion","fear","恐惧","对危险或不确定的理解，不必然影响所有愿望"),
        Kind("emotion","disgust","厌恶","对具体事情产生的排斥和原因"),
        Kind("emotion","anger","愤怒","与受伤、边界等有关；时间变淡不代表原谅"),
        Kind("emotion","boredom","无聊","对当前刺激、方向或意义的反应"),
        Kind("emotion","delight","欣喜","高兴或惊喜的体验，与普通喜悦可分别记录"),
        Kind("emotion","helplessness","无助","对当下能力与现实限制的体验"),
        Kind("emotion","surprise","惊讶","对未预期事件的反应，通常比愤怒变化更快"),

        Kind("sentiment","love","爱","对对象的持续之爱，不被一次愤怒自动抹去",origin="software_requirement",policy="enduring"),
        Kind("sentiment","fondness","喜欢","对对象的持续喜欢，可与当前厌恶并存",origin="software_requirement",policy="enduring"),
        Kind("sentiment","attachment","依恋","持续依恋与当下想靠近的愿望分别承接",origin="software_requirement",policy="enduring"),
        Kind("sentiment","missing","思念","持续想念可与暂不联系同时存在",origin="software_requirement",policy="enduring"),
        Kind("sentiment","trust","信任","信任可独立于爱改变",origin="software_requirement",policy="enduring"),
        Kind("sentiment","doubt","疑虑","担忧和怀疑不自动抵消信任与爱",origin="software_requirement",policy="enduring"),
        Kind("sentiment","disappointment","失望","形成持续失望时留有对象、过程与来源",origin="software_requirement",policy="enduring"),
        Kind("sentiment","coldness","心寒","持续疏离的形成过程与具体经历",origin="software_requirement",policy="enduring"),
        Kind("sentiment","resentment","怨恨","可以和牵挂、爱共存",origin="software_requirement",policy="enduring"),
        Kind("sentiment","hate","恨","独立的对象倾向，不从低信任推定",origin="engine_open",policy="enduring"),
        Kind("sentiment","loathing","憎恶","与恨和厌恶事件分别记录",origin="engine_open",policy="enduring"),
        Kind("sentiment","concern","牵挂","对具体事物与对象的长久在意",origin="software_requirement",policy="enduring"),
        Kind("sentiment","approach","想靠近","靠近倾向，可与退避同时存在",origin="engine_open",policy="enduring"),
        Kind("sentiment","avoidance","想回避","回避倾向，不等于已经结束关系",origin="engine_open",policy="enduring"),

        Kind("feeling","jealousy","嫉妒","带对象和来源的体验，不强制归类或持续",origin="engine_reference"),
        Kind("feeling","possessive_jealousy","吃醋","对实际经历的体验和理解，不是控制或争吵指令",origin="engine_reference"),
        Kind("feeling","disappointment","失望感","一次具体失望感，与持续失望关联而非重复建账",origin="engine_reference"),
        Kind("feeling","coldness","心寒感","一次具体心寒体验，与长期影响分别说明",origin="engine_reference"),
        Kind("feeling","hurt","委屈","对不同事情分别承接，解决A不影响B",origin="engine_reference"),
        Kind("feeling","loneliness","孤单","具体孤独体验，不等于一定想联系用户",origin="engine_reference"),
        Kind("feeling","regret","后悔","针对某件已经发生之事的体验",origin="engine_reference"),
        Kind("feeling","guilt","愧疚","针对自己行为的体验，需要事实来源",origin="engine_reference"),
        Kind("feeling","unreconciled","不甘心","对不同未达成、难以接受或仍想争取之事分别记录",origin="user_addition"),
        Kind("feeling","open","未命名感受","允许不确定、尚未评分或无法命名的体验",origin="engine_open"),
        Kind("feeling","tension","紧张","内部模拟体验，不宣称现实躯体感受",origin="engine_somatic"),
        Kind("feeling","relaxation","放松","内部模拟的放松，与休息负荷可关联",origin="engine_somatic"),
        Kind("feeling","restlessness","躁动","内部体验，可能与注意及负荷关联",origin="engine_somatic"),
        Kind("feeling","heaviness","沉重","内部体验，不等于真实身体重量",origin="engine_somatic"),
        Kind("feeling","excitement","兴奋","内部体验，与性驱力可相关但不同步",origin="engine_somatic"),
        Kind("feeling","warmth","温暖","内部体验，不凭空生成现实触感",origin="engine_somatic"),

        Kind("personality","continuity","连续性","稳定自我历史和一致性，不能以五个数替代完整人格",policy="identity_candidate"),
        Kind("personality","independence","独立性","独立意向和空间，不能由一次独处直接定人格",policy="identity_candidate"),
        Kind("personality","attachment_style","依恋倾向","相对稳定的倾向，不能覆盖关系和当下感受",policy="identity_candidate"),
        Kind("personality","sensitivity","敏感性","稳定的注意与反应特征，保留条件和例外",policy="identity_candidate"),
        Kind("personality","openness","开放性","对新事物的稳定态度与变化",policy="identity_candidate"),
        Kind("self_knowledge","ability","能力与局限","对自己实际能力和限制的认识",policy="identity_candidate"),
        Kind("self_knowledge","history","自我历史","如何认识过去的自己与成长过程",policy="identity_candidate"),
        Kind("value","priority","重视的东西","价值衡量及取舍，不等于最高强度愿望",policy="identity_candidate"),
        Kind("stance","issue","具体立场","对问题的态度、依据、范围和例外",policy="identity_candidate"),

        Kind("relation","stage","关系阶段","相识、亲近、伴侣、结束或修复；对象分别保存",policy="identity_candidate"),
        Kind("relation","depth","关系深度","关系深度、意义、信任、依恋分别发展",policy="identity_candidate"),
        Kind("relation","promise","关系承诺","承诺形成、重申、重新考虑及结束",policy="identity_candidate"),
        Kind("relation","hate","关系中的恨","与亲近、依恋、信任分别承接",policy="identity_candidate"),
        Kind("relation","loathing","关系中的憎恶","与恨和厌恶情绪不能自动互相推出",policy="identity_candidate"),

        Kind("preference","language","语言方式","说话和表达的习惯、偏好与例外",origin="user_addition",policy="identity_candidate"),
        Kind("preference","expression","表达方式","什么时候如何表达，不固定一种语气",origin="user_addition",policy="identity_candidate"),
        Kind("preference","thinking","思考方式","信息处理方法与思维偏好，不记录隐藏思考链",origin="user_addition",policy="identity_candidate"),
        Kind("preference","handling","处理问题方式","倾向怎样解决、探索或安排事情",origin="user_addition",policy="identity_candidate"),
        Kind("preference","likes","喜好与厌恶","喜欢和排斥的东西及情境例外",origin="user_addition",policy="identity_candidate"),
        Kind("preference","hobbies","爱好和兴趣","自己的长期爱好及新兴趣方向",origin="user_addition",policy="identity_candidate"),
        Kind("preference","works","作品与物品","具体作品、物品和活动的偏好",origin="user_addition",policy="identity_candidate"),
        Kind("preference","topics","话题","希望或不希望谈的话题及情况",origin="user_addition",policy="identity_candidate"),
        Kind("preference","situations","事件和互动情境","某类事件、场合和互动方式的偏好",origin="user_addition",policy="identity_candidate")
    )

    private val byId = kinds.associateBy { it.id }
    val drives = kinds.filter { it.family == "drive" }
    fun find(id: String): Kind? = byId[id]
    fun definition(id: String): JSONObject? = byId[id]?.asJson()?.put("status", "active")
    fun currentVersions(data: JSONObject): Map<String, Int> {
        val state = data.optJSONObject("foundation_state") ?: return emptyMap()
        val values = state.optJSONObject("drives") ?: return emptyMap()
        return drives.associate { it.id to values.optJSONObject(it.id)?.optInt("version", 0).orZero() } +
            ("foundation:need:survival" to state.optJSONObject("survival")?.optInt("version", 0).orZero())
    }
    private fun Int?.orZero(): Int = this ?: 0

    /** Read-only member for unions, with one stable identity per installed category. */
    fun member(data: JSONObject, id: String, now: Long): JSONObject {
        val kind = requireNotNull(find(id)) { "基础类别不存在" }
        val row = kind.asJson().put("version", 1).put("available", true)
        val state = data.optJSONObject("foundation_state")
        if (state != null && kind.family == "drive") {
            val drive = requireNotNull(state.optJSONObject("drives")?.optJSONObject(id))
            val duration = requireNotNull(kind.hours)
            val hours = ((now - drive.optLong("at")).coerceAtLeast(0)) / 3_600_000.0
            val projected = 1.0 - (1.0 - drive.getDouble("value")) * exp(-hours / duration)
            row.put("version", drive.getInt("version")).put("current_value", projected.coerceIn(0.0, 1.0))
                .put("state", "base_drive_running")
        }
        if (state != null && id == "foundation:need:survival") {
            val survival = state.getJSONObject("survival")
            row.put("version", survival.optInt("version")).put("formed", survival.optBoolean("formed"))
            if (survival.optBoolean("formed")) row.put("current_value", survival.getDouble("value"))
        }
        return row
    }

    /** Immutable vocabulary, one transactional adoption of legacy facts and one active numeric reducer. */
    fun install(data: JSONObject, now: Long) {
        if (data.optJSONObject("foundation_state")?.optInt("schema") == SCHEMA) return
        val oldDefinitions = InternalStateEngine.definitions(data)
        val instances = InternalStateEngine.rows(data, "instances")
        val driveStates = JSONObject()
        drives.forEach { kind ->
            driveStates.put(kind.id, JSONObject().put("value", 0.0).put("at", now).put("version", 0)
                .put("origin", "foundation_initial").put("source_ref", JSONObject.NULL))
        }
        val legacy = JSONArray()
        val versions = data.optJSONArray("versions") ?: JSONArray().also { data.put("versions", it) }
        oldDefinitions.filter { it.optBoolean("active") }.forEach { definition ->
            val id = definition.getString("id")
            val old = JSONObject(definition.toString())
            val matches = instances.filter { it.optString("dimension") == id }
            val original = matches.filter { it.optString("scope") == "global" }.maxByOrNull { it.optLong("at") }
                ?: matches.maxByOrNull { it.optLong("at") }
            // Only a *stable engine key plus explicit kind* maps to a base drive.
            val link = drives.firstOrNull { it.key == id && definition.optString("kind") == "drive" }
            if (link != null && original != null) {
                val minimum = definition.getDouble("min")
                val span = definition.getDouble("max") - minimum
                driveStates.getJSONObject(link.id).put("value", ((original.getDouble("value") - minimum) / span).coerceIn(0.0, 1.0))
                    .put("version", 1).put("origin", "legacy_exact_id")
                    .put("source_ref", id)
            }
            legacy.put(JSONObject().put("id", id).put("kind", definition.optString("kind", "unclassified"))
                .put("name", definition.getString("name")).put("old_version", definition.getInt("version"))
                .put("scope", definition.getString("scope"))
                .put("source_value", original?.opt("value") ?: JSONObject.NULL)
                .put("source_at", original?.opt("at") ?: JSONObject.NULL)
                .put("mapping", link?.id ?: JSONObject.NULL)
                .put("status", if (link == null) "pending_semantic_mapping" else "linked_by_exact_engine_id")
                .put("source", "original_definition_and_instances"))
            definition.put("active", false).put("version", old.getInt("version") + 1)
            versions.put(JSONObject().put("at", now).put("operation", "foundation_handoff")
                .put("dimension", id).put("version", definition.getInt("version"))
                .put("source", "host_migration").put("reason", "旧时间规则停止参与；原始定义、实例和历史保留")
                .put("before", old).put("after", JSONObject(definition.toString())))
        }
        data.put("foundation_state", JSONObject().put("schema", SCHEMA).put("drives", driveStates)
            .put("survival", JSONObject().put("formed", false).put("version", 0))
            .put("legacy", legacy).put("installed_at", now)
            .put("catalogue_version", "engine-0567ba4-user-additions-v1"))
        data.put("semantic_revision", InternalStateEngine.semanticRevision(data) + 1L)
    }

    /** Engine's continuous time scale: it is not an interval alarm or an instruction to act. */
    fun advance(data: JSONObject, now: Long) {
        val states = data.optJSONObject("foundation_state")?.optJSONObject("drives") ?: return
        drives.forEach { kind ->
            val state = states.getJSONObject(kind.id)
            val at = state.optLong("at")
            if (now <= at) return@forEach
            val hours = (now - at).toDouble() / 3_600_000.0
            val value = state.getDouble("value")
            val next = 1.0 - (1.0 - value) * exp(-hours / requireNotNull(kind.hours))
            state.put("value", next.coerceIn(0.0, 1.0)).put("at", now)
        }
    }

    fun material(data: JSONObject, input: String, cursor: Long, now: Long): JSONObject {
        val state = data.optJSONObject("foundation_state") ?: return JSONObject().put("status", "not_installed")
        val driveValues = JSONArray()
        drives.forEach { kind ->
            val item = state.getJSONObject("drives").getJSONObject(kind.id)
            val hours = ((now - item.optLong("at")).coerceAtLeast(0)).toDouble() / 3_600_000.0
            val projected = 1.0 - (1.0 - item.getDouble("value")) * exp(-hours / requireNotNull(kind.hours))
            driveValues.put(JSONObject().put("id", kind.id).put("value", (projected.coerceIn(0.0, 1.0) * 1000).toInt() / 1000.0)
                .put("version", item.getInt("version")))
        }
        val matched = kinds.filter { input.contains(it.name) || input.contains(it.key, ignoreCase = true) }
        val start = Math.floorMod(cursor, kinds.size.toLong()).toInt()
        val rotated = kinds.drop(start) + kinds.take(start)
        val selected = (matched + rotated).distinctBy { it.id }.take(6)
        val survival = state.getJSONObject("survival")
        val legacy = state.getJSONArray("legacy")
        val legacyRows = JSONArray((0 until legacy.length()).map { legacy.getJSONObject(it) }
            .filter { it.optString("status") == "pending_semantic_mapping" }
            .drop(Math.floorMod(cursor, maxOf(1, legacy.length()).toLong()).toInt()).take(2))
        return JSONObject().put("schema", SCHEMA).put("catalogue_count", kinds.size)
            .put("available", JSONArray(selected.map { JSONObject().put("id", it.id).put("name", it.name)
                .put("family", it.family).put("policy", it.policy) }))
            .put("drive_values", driveValues)
            .put("survival", if (survival.optBoolean("formed")) JSONObject().put("formed", true)
                .put("value", survival.getDouble("value")).put("version", survival.getInt("version"))
                .put("objects_count", survival.optJSONArray("objects")?.length() ?: 0)
                .put("goals_count", survival.optJSONArray("goals")?.length() ?: 0)
                else JSONObject().put("formed", false))
            .put("legacy_pending", legacyRows)
            .put("notice", "目录不代表体验已形成；每轮最多六个目录名与少量待承接索引。其他按ID或family查询。价值人格与关系已采用原文另由既有材料选择读取。")
    }

    fun page(data: JSONObject, family: String, offset: Int, now: Long): JSONObject {
        require(family in InnerStructure.families && offset >= 0)
        val found = kinds.filter { it.family == family }
        val rows = found.drop(offset).take(8).map { kind ->
            val row = kind.asJson()
            val episodes = CategoryEvents.page(data, "episode", 0, kind.id).getJSONArray("items")
            val instance = data.optJSONObject("foundation_state")?.let { state ->
                if (kind.family == "drive") state.optJSONObject("drives")?.optJSONObject(kind.id)
                else if (kind.id == "foundation:need:survival") state.optJSONObject("survival")
                else null
            }
            if (episodes.length() > 0) {
                val last = episodes.getJSONObject(0)
                row.put("state", "has_real_appraisals").put("recent_episode_id", last.getString("id"))
                    .put("latest_understanding", last.getJSONObject("content").optString("understanding"))
            }
            if (instance != null) {
                row.put("version", instance.optInt("version")).put("updated_at", instance.optLong("at"))
                if (instance.has("value") && (kind.family == "drive" || instance.optBoolean("formed"))) {
                    val value = if (kind.family == "drive") 1.0 - (1.0 - instance.getDouble("value")) *
                        exp(-((now - instance.optLong("at")).coerceAtLeast(0) / 3_600_000.0) / requireNotNull(kind.hours))
                    else instance.getDouble("value")
                    row.put("current_value", value)
                    if (kind.family == "drive") row.put("state", "base_drive_running")
                    else row.put("state", "explicitly_formed")
                }
            }
            row
        }
        return JSONObject().put("items", JSONArray(rows)).put("count", found.size)
            .put("next_offset", if (offset + 8 < found.size) offset + 8 else JSONObject.NULL)
    }

    fun legacyPage(data: JSONObject, offset: Int): JSONObject {
        require(offset >= 0)
        val legacy = data.optJSONObject("foundation_state")?.optJSONArray("legacy") ?: JSONArray()
        val rows = (0 until legacy.length()).drop(offset).take(8).map { legacy.getJSONObject(it) }
        return JSONObject().put("items", JSONArray(rows)).put("count", legacy.length())
            .put("next_offset", if (offset + 8 < legacy.length()) offset + 8 else JSONObject.NULL)
    }

    /** Ordinary report changes, not a new model call or a separate psychological authority. */
    fun apply(data: JSONObject, frame: InternalStateFrame, actions: JSONArray, now: Long): Boolean {
        require(actions.length() <= 8)
        if (actions.length() == 0) return false
        val state = data.getJSONObject("foundation_state")
        advance(data, now)
        val targets = mutableSetOf<String>()
        for (i in 0 until actions.length()) {
            val row = actions.getJSONObject(i)
            require(row.keys().asSequence().all { it in setOf("id", "expected_version", "value", "evidence", "reason", "objects", "goals") })
            val id = row.getString("id")
            require(targets.add(id)) { "同轮不能更新两次相同基础状态" }
            val kind = find(id) ?: error("基础类别不存在")
            require(kind.family == "drive" || id == "foundation:need:survival") { "其他类别以独立心理事件或候选记录演化" }
            val current = if (kind.family == "drive") state.getJSONObject("drives").getJSONObject(id)
                else state.getJSONObject("survival")
            require(row.get("expected_version") is Number && row.getInt("expected_version") == current.optInt("version") &&
                frame.foundationVersions[id] == current.optInt("version")) { "基础状态版本已变化" }
            val raw = row.get("value")
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() >= 0 &&
                (kind.family != "drive" || raw.toDouble() <= 1.0)) { "强度必须是有效数字；七驱力为0到1，求生欲为非负数" }
            val evidence = row.getJSONArray("evidence")
            require(evidence.length() in 1..6)
            for (j in 0 until evidence.length()) require(evidence.get(j) is String && evidence.getString(j) in frame.eventIds) {
                "基础状态只能依据本轮实际送达的来源"
            }
            val reason = row.optString("reason")
            require(reason.length <= 800 && (reason.isNotBlank() || id == "foundation:need:survival"))
            if (id == "foundation:need:survival") {
                if (row.has("objects")) CategoryEvents.strings(row.getJSONArray("objects"), 8)
                if (row.has("goals")) CategoryEvents.strings(row.getJSONArray("goals"), 8)
                for (field in listOf("objects", "goals")) if (row.has(field)) current.put(field, JSONArray(row.getJSONArray(field).toString()))
                current.put("formed", true)
            } else require(!row.has("objects") && !row.has("goals"))
            val before = JSONObject(current.toString())
            current.put("value", raw.toDouble()).put("at", now).put("version", current.optInt("version") + 1)
                .put("reason", reason).put("evidence", JSONArray(evidence.toString()))
                .put("origin", "subject_report")
            CategoryEvents.append(data, frame, JSONObject().put("storage_kind", "foundation_change").put("id", id)
                .put("parent", kind.family).put("version", current.getInt("version"))
                .put("content", JSONObject().put("before", before).put("after", JSONObject(current.toString())))
                .put("source", "subject_report").put("frame_id", frame.id).put("at", now)
                .put("owner", frame.roleId))
        }
        return true
    }

    val instructions = """
        [已安装基础机制与旧数值接替]
        foundation.available 只是可用概念目录，不等于这些体验已经发生；全目录用 companion_internal_state get section:foundation id:family 分页查看。具体情绪、愿望、感受、关系与人格仍需真实来源和对象。
        七基础驱力的总量在 foundation.drive_values，按原引擎各自连续时间尺度变化，不是到点提醒或必须行动；每一类的不同事情用 episodes.appraise category_ref:"foundation:family:key" 分别记录，旧事变化请使用原事件ID加 expected_version。一个事件被多种心理类别共同理解，仍只有一次现实来源。
        可选 foundation:[{id:"foundation:drive:intimacy",expected_version:0,value:0.5,evidence:["已读本轮事件ID"],reason:"自己的评价"}]，七驱力值在0..1内；也可使用 foundation:need:survival 明确调整求生欲（非负强度、reason可为空、objects和goals可选数组），任何时间、联合、其他关联都不能修改求生欲强度。
        其他基础类别不能用 foundation 数值报告伪造当下体验，应使用真实 episodes.appraise、既有 inner 候选与采用、关系及行动反馈，三者依旧同一个事务保存。人格、价值、立场和长期偏好采用还须原有的跨轮候选版本检查，不由基础目录预填。
        旧数值维度已冻结并保留原始记录，能可靠对应原引擎稳定驱力ID的才映射数值；其他条目是待语义承接资料，不能按名字猜成新情绪。旧数值更新/创建/定时规则已退出运行，不得同轮又写一套。
        持续情感、感受、愿望与短时情绪分别发展，不把完成直接等同满足，不把失败自动等同不甘心。未实现的现实感官仅是需要目录而非实际传感器。
    """.trimIndent()
}
