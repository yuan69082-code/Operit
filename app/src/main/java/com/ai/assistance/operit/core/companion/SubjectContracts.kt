package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Input limits concern authored input, never host-added provenance. Keep diagnostics value-free. */
internal object SubjectContracts {
    const val JSON_CHARS = 16384
    const val HELP = "输入细则可用 companion_internal_state get section=contract id=inner|episodes|unions|action|life|concerns|rest|foundation|legacy 查询；整份轮末报告最多16384个UTF-16字符，宿主来源附件不占此额度。工具续轮来源绑定只保留最近24份，早先材料仍存于历史；需重新引用时按需读取，不猜造ID。"

    fun expect(ok: Boolean, path: String, rule: String, hint: String) {
        if (!ok) throw InternalStateReportIssue("input", path, rule, hint)
    }

    fun fields(value: JSONObject, allowed: Set<String>, path: String = "$") =
        expect(value.keys().asSequence().all { it in allowed }, path, "UNKNOWN_FIELD", "存在未定义字段；请查询该入口契约。")

    fun text(value: JSONObject, key: String, max: Int = Int.MAX_VALUE, empty: Boolean = false, path: String = key): String {
        val raw = value.opt(key)
        expect(raw is String, path, "EXPECTED_STRING", "需要字符串；数字、对象和null不是字符串。")
        val result = raw as String
        expect(empty || result.isNotBlank(), path, "REQUIRED_STRING", "需要非空文字。")
        expect(result.length <= max, path, "TEXT_LENGTH", "此字段最多${max}个UTF-16字符；当前${result.length}。")
        return result
    }

    fun array(value: JSONObject, key: String, path: String = key): JSONArray {
        val raw = value.opt(key)
        expect(raw is JSONArray, path, "EXPECTED_ARRAY", "需要JSON数组；没有内容时使用[]。")
        return raw as JSONArray
    }

    fun strings(values: JSONArray, limit: Int = Int.MAX_VALUE, path: String = "$"): List<String> {
        expect(values.length() <= limit, path, "ITEM_LIMIT", "此数组最多${limit}项；当前${values.length()}。")
        val result = (0 until values.length()).map { i ->
            val raw = values.opt(i)
            expect(raw is String, "$path[$i]", "EXPECTED_STRING", "数组成员需要字符串。")
            (raw as String).also { expect(it.isNotBlank() && it.length <= 180, "$path[$i]", "REFERENCE_TEXT", "引用或对象标识须为1..180个UTF-16字符的非空字符串。") }
        }
        expect(result.distinct().size == result.size, path, "DUPLICATE_REFERENCE", "同一数组不要重复引用同一项。")
        return result
    }

    fun integer(value: JSONObject, key: String, path: String = key): Int {
        val raw = value.opt(key)
        expect(raw is Number && raw.toDouble().isFinite() && raw.toDouble() >= 0 &&
            raw.toDouble() == raw.toInt().toDouble(), path, "VERSION_NUMBER", "需要非负整数数字，不接受字符串或小数。")
        return (raw as Number).toInt()
    }

    fun choice(value: JSONObject, key: String, allowed: Set<String>, path: String = key): String =
        text(value, key, path = path).also {
            expect(it in allowed, path, "ENUM_VALUE", "允许值：${allowed.joinToString("|")}。")
        }

    fun jsonSize(value: JSONObject, path: String = "$") =
        expect(value.toString().length <= JSON_CHARS, path, "INPUT_SIZE", "本次JSON最多${JSON_CHARS}个UTF-16字符；请分批提交，不删改已存全文。")

    fun parseObject(raw: String, path: String): JSONObject = try { JSONObject(raw) } catch (_: org.json.JSONException) {
        throw InternalStateReportIssue("input", path, "JSON_SHAPE", "需要有效JSON对象；检查引号、括号和逗号。")
    }

    fun queryId(section: String, id: String?) {
        val required = setOf("dream_step", "foundation", "foundation_history", "episode_category", "inner_family",
            "inner_chunk", "inner_record_chunk", "source", "category_versions", "action_versions")
        if (section in required) expect(!id.isNullOrBlank(), "id", "QUERY_ID_REQUIRED",
            if (section == "dream_step") "dream_step需要id=参与式梦境过程ID；先get section=dream_process查询，offset只表示该梦的步骤分页。"
            else "${section}查询必须提供id；字段含义见get section=contract。")
    }

    fun dreamProcess(prior: JSONObject?): JSONObject {
        expect(prior != null, "id", "DREAM_PROCESS_NOT_FOUND",
            "未找到参与式梦境过程。先get section=dream_process取得ID；静态dream记录没有可恢复或结束的运行过程。")
        return requireNotNull(prior)
    }

    fun query(topic: String?): JSONObject {
        val topics = setOf("inner", "episodes", "unions", "action", "life", "concerns", "rest", "foundation", "legacy")
        if (topic == null) return JSONObject().put("topics", JSONArray(topics.toList())).put("help", HELP)
        expect(topic in topics, "id", "CONTRACT_TOPIC", "id须为inner|episodes|unions|action|life|concerns|rest|foundation|legacy。")
        val contract = when (topic) {
            "concerns" -> SubjectConcerns.instructions
            "inner" -> InnerStructure.instructions + "\n标识id/replaces/parent_ref最多64字符，kind/aspect最多80，object_ref最多160，relation.label最多100。description/context/exceptions/direction/current_state/understanding/feeling及发展说明没有额外短字数上限，受本次输入预算约束；原文完整保存。evidence/experience_refs/anchors不设额外条数上限，引用字符串最多180字符且不得重复。候选和已采用内容按版本读取；采用不能修改候选原文。"
            "episodes" -> CategoryEvents.instructions + "\nid/category_ref最多64字符；对象、目标及来源标识为非空字符串，每项最多180字符且不得重复。objects/goals省略时保留既有值，新建为[]，显式[]清空关联。对象/目标/来源/关联不设额外条数上限。importance/long_term_impact/understanding/reason须非空，无独立短字数上限。related只能指向已存在且不同于自身的事件。重点capacity仍为3..6，events不得超过所选capacity。"
            "unions" -> InnerInteractions.instructions + "\nid及成员id最多64字符，from/to最多90；interpretation/decision/meaning须非空，无独立短字数上限。至少2个不同权威成员，没有额外成员或作用条数上限；来源须非空并已提供。版本须为非负整数数字。"
            "action" -> ActionUnderstanding.instructions + "\n__operit_action须为JSON字符串对象，最多16384个UTF-16字符。decision为文字；intention_refs和evidence可省略或[]；refs每项恰有kind/id/version，version为非负整数数字。capability可省略，提供时须恰有id/version/stage/target，id/target为1..500字符字符串；version可为1..500字符字符串或非负有限数字，version:\"1\"和version:1均可，原样保存；stage为build|configure|run|adjust。retry_of为已读操作ID。decisions/action_feedback不设每轮4条限制；id最多64字符，meaning须非空，expected_version为整数数字；反馈须匹配实际操作回执。"
            "rest" -> SubjectRestDreams.instructions + "\n梦醒理解、来源、对象不设额外条数上限，meaning/feeling/content无独立短字数上限，仍受整份报告预算约束；title最多160字符，id最多64。dream_plan的theme_mode是single/multiple/random；theme_count仅multiple可用2..7；旧theme/tone/form/recall兼容保留，各最多1000字符，consolidation最多2000字符；这是送入梦境/整理请求的计划材料，正文另存。运行梦的steps上限6是明确的额外模型调用预算，与保存了多少梦境/理解无关。"
            "foundation" -> "foundation:[{id,expected_version,value?,evidence:[],reason,objects?:[],goals?:[],time_conditions?:{increase/decrease:布尔值}}]。仅已承接原规则的drive可提交原condition=custom的time_conditions，并可仅确认条件而省略value；其他情况value仍必填。只更新已实际提供的基础drive及foundation:need:survival；其他心理类别用episodes/inner，不造第二份权威。expected_version须为当前非负整数版本；drive.value为0..1，求生欲为非负数字，不按时间自动改变；求生欲reason可空或省略，其余须非空文字。objects/goals只用于求生欲，每项1..180字符，无条数上限。evidence至少1项且是本轮实际来源，无额外6条限制；理由无独立短字数上限。"
            "legacy" -> "旧兼容数值维度的create/revise/updates仍按完整时间规则、来源、当前版本处理；活跃兼容维度上限64（不是inner/episodes新结构的总量限制）。新基础类别按foundation/inner/episodes使用，不能复制造第二份权威。reason须非空，desire/choice须字符串，均无独立短字数上限；来源数组须非空、无重复且来自本轮实际材料。ID最多64字符，object_ref最多160。"
            else -> LifeUnderstandings.instructions + "\nlife.id最多100字符；expected_version为非负整数数字；text/scope/uncertainty为非空字符串，purpose/goal/resolve/ambition可为空字符串。review_at为不早于提交时间的整数毫秒，可省略，不自动预约。dream_step必须id=参与式dream_process的ID，offset为步骤分页起点；先查dream_process。静态dream是已保存梦境内容，没有可恢复或结束的运行过程。"
        }
        return JSONObject().put("topic", topic).put("json_chars", JSON_CHARS).put("help", HELP).put("contract", contract)
    }
}
