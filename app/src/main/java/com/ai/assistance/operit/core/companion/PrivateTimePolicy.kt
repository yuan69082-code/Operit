package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Chosen calendar commitments, not an idle-time or psychological controller. */
internal object PrivateTimePolicy {
    val activities = setOf("explore", "review", "diary", "write", "sleep", "wander", "other")
    val targets = setOf("memory", "diary", "reading", "files", "context", "summary", "todos",
        "personality", "relationships", "directions", "internal_state", "dreams", "essence")
    val terminal = setOf("completed", "cancelled")
    data class Window(val date: String, val start: Long, val end: Long)
    data class Notice(val key: String, val text: String)

    fun window(c: JSONObject, date: LocalDate): Window {
        val zone = ZoneId.of(c.getString("zone"))
        val start = LocalTime.parse(c.getString("start"))
        val end = LocalTime.parse(c.getString("end"))
        require(start != end) { "自由时间开始和结束不能相同" }
        return Window(date.toString(), date.atTime(start).atZone(zone).toInstant().toEpochMilli(),
            (if (end <= start) date.plusDays(1) else date).atTime(end).atZone(zone).toInstant().toEpochMilli())
    }

    fun upcoming(c: JSONObject, now: Long): Window {
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(c.getString("zone"))).toLocalDate()
        val yesterday = window(c, date.minusDays(1))
        if (now in yesterday.start until yesterday.end) return yesterday
        val today = window(c, date)
        return if (now < today.end) today else window(c, date.plusDays(1))
    }

    fun validateConfig(c: JSONObject) {
        require(c.get("enabled") is Boolean) { "enabled须为布尔值" }
        window(c, LocalDate.of(2026, 1, 1))
        if (c.optString("sleep_time").isNotBlank()) LocalTime.parse(c.getString("sleep_time"))
        if (c.optString("fatigue_episode").isNotBlank()) {
            val threshold = c.getDouble("fatigue_threshold")
            require(threshold.isFinite() && threshold in 0.0..100.0) { "fatigue_threshold范围0..100，与所选事件强度同量纲" }
        }
    }

    fun plan(c: JSONObject, request: JSONObject, old: JSONObject?, now: Long): JSONObject {
        require(request.getInt("expected_version") == (old?.getInt("version") ?: 0)) { "计划版本已变化，先free_status读取" }
        val date = LocalDate.parse(request.getString("date"))
        val w = window(c, date)
        require(w.end > now) { "不能给已结束的自由时间新排活动" }
        val items = request.getJSONArray("items")
        val ids = mutableSetOf<String>()
        val normalized = mutableListOf<JSONObject>()
        for (i in 0 until items.length()) {
            val a = items.getJSONObject(i)
            SubjectContracts.fields(a, setOf("id", "start", "end", "activity", "goal", "targets", "checks"))
            val id = SubjectContracts.text(a, "id", 100)
            require(ids.add(id)) { "items[$i].id重复" }
            require(a.getString("activity") in activities) { "items[$i].activity未知；可用other并说明具体打算" }
            SubjectContracts.text(a, "goal")
            val start = a.getLong("start")
            val end = a.getLong("end")
            require(start >= w.start && end <= w.end && start < end) { "items[$i]必须在自由时间内，start/end为毫秒时间戳" }
            val previous = old?.optJSONArray("items")?.objects()?.firstOrNull { it.getString("id") == id }
            require(end > now || previous != null) { "不能新建已经过期的时段" }
            val checks = a.optJSONArray("checks") ?: JSONArray()
            val checkTimes = (0 until checks.length()).map { checks.getLong(it) }
            require(checkTimes.distinct().size == checkTimes.size && checkTimes.all { it > start && it < end }) { "checks须为时段内不重复的毫秒时间；不需要防退回时传空数组" }
            val selected = a.optJSONArray("targets") ?: JSONArray()
            val selectedTargets = (0 until selected.length()).map { selected.getString(it) }
            require(selectedTargets.distinct().size == selectedTargets.size && selectedTargets.all { it in targets }) { "targets须从free_status菜单选择，可多选" }
            require(a.getString("activity") != "review" || selectedTargets.isNotEmpty()) { "回顾整理须明确选择targets，不能只写笼统的整理" }
            val result = JSONObject(a.toString()).put("checks", checks).put("targets", selected)
            if (previous != null) {
                // Decisions and checkpoints survive schedule edits. A new time is not a new task.
                for (key in listOf("status", "progress", "notice_keys", "started", "review_results"))
                    if (previous.has(key)) result.put(key, previous.get(key))
                require(previous.optString("status") !in terminal ||
                    listOf("start", "end", "activity", "goal", "targets").all { result.opt(it).toString() == previous.opt(it).toString() }) {
                    "已完成或取消的活动保留原记录；新决定请用新id"
                }
                if (previous.getString("activity") != a.getString("activity") || previous.getJSONArray("targets").toString() != selected.toString()) {
                    require(previous.optString("status", "planned") == "planned") { "已开始活动改变范围请另建id，保留原进度" }
                }
            } else result.put("status", "planned").put("notice_keys", JSONArray())
            normalized += result
        }
        // Omitted items are not deleted: cancellation is an explicit, reviewable choice.
        old?.optJSONArray("items")?.objects()?.filter { it.getString("id") !in ids }?.forEach {
            require(it.getString("status") in terminal) { "删去未完成活动前先free_progress明确cancelled" }
            normalized += it
        }
        val active = normalized.filter { it.getString("status") !in terminal }.sortedBy { it.getLong("start") }
        active.zipWithNext().forEach { (a, b) -> require(a.getLong("end") <= b.getLong("start")) { "活动时段重叠；睡眠与清醒整理不能同时安排" } }
        return (old ?: JSONObject().put("id", w.date).put("parent", "calendar"))
            .put("date", w.date).put("window_start", w.start).put("window_end", w.end).put("zone", c.getString("zone"))
            .put("items", JSONArray(normalized)).put("updated_at", now)
    }

    fun progress(plan: JSONObject, a: JSONObject, receipt: (String) -> JSONObject?): JSONObject {
        require(a.getInt("expected_version") == plan.getInt("version")) { "计划版本已变化，先free_status读取" }
        val item = plan.getJSONArray("items").objects().firstOrNull { it.getString("id") == a.getString("slot_id") }
            ?: error("slot_id不存在")
        require(item.getString("status") !in terminal) { "该活动已结束；新安排使用新id" }
        val status = a.getString("status")
        require(status in setOf("working", "paused", "completed", "cancelled")) { "status: working|paused|completed|cancelled" }
        val note = SubjectContracts.text(a, "note")
        if (a.has("review_results")) item.put("review_results", a.getJSONArray("review_results"))
        if (status == "completed" && item.getString("activity") == "review") {
            val results = item.optJSONArray("review_results")?.objects().orEmpty()
            val selected = item.getJSONArray("targets").let { (0 until it.length()).map(it::getString) }
            require(results.map { it.getString("target") }.toSet() == selected.toSet() && results.size == selected.size) { "每个选中范围须有一份review_results，不能只看完就标整理完成" }
            results.forEach { r ->
                SubjectContracts.text(r, "reviewed")
                SubjectContracts.text(r, "reason")
                require(r.getString("organization") in setOf("changed", "not_needed")) { "organization: changed|not_needed；后者须在reason说明已整理、不必动或保留原样的理由" }
                if (r.getString("organization") == "changed") {
                    val ids = r.getJSONArray("action_ids")
                    require(ids.length() > 0) { "changed须关联实际整理操作action_ids；只读不算整理" }
                    for (i in 0 until ids.length()) {
                        val action = requireNotNull(receipt(ids.getString(i))) { "整理回执不存在：${ids.getString(i)}" }
                        require(action.optString("status") == "completed" && action.optJSONObject("receipt")?.optBoolean("success") == true) { "整理操作没有成功回执" }
                        val request = action.optJSONObject("request")
                        val tool = request?.optString("tool").orEmpty()
                        val operation = request?.optJSONArray("parameters")?.objects()?.firstOrNull { it.optString("name") == "action" }?.optString("value").orEmpty()
                        val readOnly = tool in setOf("read_file", "list_files", "search", "web_search", "companion_internal_state") ||
                            (tool.startsWith("companion_") && operation in setOf("get", "list", "read", "sources", "queue", "history", "retained", "versions", "notes", "activity", "events", "activities", "free_status", "essence_get"))
                        require(!readOnly) { "纯读取工具不能作为整理操作；保存索引、分类、关联或改稿后引用其回执" }
                    }
                }
                require(r.getString("refinement") in setOf("none", "formed")) { "refinement: none|formed；不强制产出精华" }
                if (r.getString("refinement") == "formed") SubjectContracts.text(r, "insight")
            }
        }
        item.put("status", status).put("progress", note)
        return plan
    }

    fun due(plan: JSONObject, now: Long): Notice? {
        for (item in plan.getJSONArray("items").objects().sortedBy { it.getLong("start") }) {
            if (item.getString("status") in terminal || item.getString("status") == "paused") continue
            val start = item.getLong("start"); val end = item.getLong("end")
            if (now < start) continue
            val seen = item.getJSONArray("notice_keys")
            val seenKeys = (0 until seen.length()).map(seen::getString).toSet()
            val key = when {
                now >= end -> "end:$end"
                !item.optBoolean("started") -> "start"
                else -> item.getJSONArray("checks").let { times ->
                    (0 until times.length()).map(times::getLong).filter { it <= now }.maxOrNull()?.let { "check:$it" }
                }
            } ?: continue
            if (key in seenKeys) continue
            // Coalesce overdue checks, never replay all missed reminders after a restart.
            if (key == "start") {
                item.put("started", true)
                item.getJSONArray("checks").let { times -> for (i in 0 until times.length())
                    if (times.getLong(i) <= now && "check:${times.getLong(i)}" !in seenKeys) seen.put("check:${times.getLong(i)}") }
            }
            seen.put(key)
            val phase = when { now >= end -> "时段结束，请核对进度后决定收尾、另约或取消；未完成不能写成已完成。"
                key == "start" -> "你安排的时段到了。" else -> "这是你自己选定的续接提醒；先核对进度与真实回执，再继续，不能重放已执行操作。" }
            return Notice("${plan.getString("date")}:${item.getString("id")}:$key", phase +
                " 活动=${item.getString("activity")}，slot_id=${item.getString("id")}，目标=${item.getString("goal")}；" +
                "用companion_life free_status date=${plan.getString("date")}取得最新计划版本和进度。" +
                if (item.getString("activity") == "sleep") "睡不睡仍由你决定。若现在睡，正常报告采用rest与dream_plan，再按现有机制抽签；不要先写好梦。" else
                "可以改变安排，也可以在过程中给园园留言。开始/阶段进度/完成请free_progress保存；一次模型回复结束不表示整个活动完成。")
        }
        return null
    }

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    val menu = """
        这是你自己安排的自由时间，可单选、多选、留空档、改变或取消；不是每日学习考核。
        explore 探索：学习、查资料、浏览网站、延续书房研究或开新方向；用现有搜索/浏览和companion_reading保存实际来源与进度。
        review 回顾→整理→可能提炼：必须再选targets，可多选。memory本地/外置记忆（先看人工设置的OB位置，未设置先请园园指定，不能猜）；diary日记；reading书房阅读/写作；files资料；context实际上下文；summary摘要；todos待办；personality人格/自我/价值/偏好；relationships关系；directions目的/目标/决心/野心；internal_state驱力/需要/情绪/情感/感受及关联；dreams梦与醒后余韵；essence精华角。
        回顾是实际查看选中材料；整理是归类、补索引、建立关联、核对版本、合并确实重复的内容、调整待办或改稿，不要求压缩或删除原文。不能只看过就说整理完。已整理好、无需改动、想保留原样，可以organization=not_needed并写具体理由；否则须有实际整理操作及成功回执。新理解保留来源；过去原话不能被后来理解改写。提炼是说明从中学到什么，没有值得提炼的可refinement=none，不凑精华。人格等仍走原候选/采用流程，不能绕过已有权威状态。
        diary 写自己的日记；write 在书房写作、续写、改稿、批注；sleep 睡觉做梦，入睡前正常采用rest/dream_plan后才抽签；wander 看看世界或玩：天文、人文、历史、随兴趣逛网站，也可用已连接HY工具去玩，不要求产出学习成果；other 其他自己想做的事，说明目标。
        所有活动都可以给园园留言/发消息，你不是只能埋头做这一项。使用实际可用的工具与权限；工具未连接就如实记录限制，不能编造完成。
        每个时段可自选checks中途续接时间，空数组表示不需要。计划与进度可靠保存后才算已预约；按需分页读取材料，不把全部记忆或菜单堆进每轮上下文。
    """.trimIndent()
    val help = """
        companion_life 自由时间操作（details为JSON）：
        free_status: {date?:YYYY-MM-DD}读取配置、本日/下一窗口计划及菜单；free_history用offset分页计划索引。
        free_config: {expected_version,enabled,start:"HH:mm",end:"HH:mm",zone:"Asia/Shanghai",sleep_time?:"HH:mm或空",fatigue_episode?:"实际疲劳事件ID或空",fatigue_threshold?:0..100}。仅设置自己的安排；OB位置由园园在页面人工设定，不可替填。未设置时先自行定义自由时段，不能把示例当默认。
        free_plan: {date,expected_version:无计划用0,items:[{id,start:毫秒,end:毫秒,activity:"explore|review|diary|write|sleep|wander|other",goal,targets:[],checks:[毫秒]}]}。替换整份计划，保留已有id与进度；取消先free_progress。睡眠与清醒整理不能重叠，可先整理后睡。
        free_progress: {date,expected_version,slot_id,status:"working|paused|completed|cancelled",note,review_results?:[{target,reviewed:"实际看了什么",organization:"changed|not_needed",reason,action_ids:[实际整理操作ID],refinement:"none|formed",insight?:提炼}]}。暂停/取消后不自动恢复，继续须free_progress working；时段外继续另排。changed须成功的写入/整理回执，纯读取不算；not_needed说明理由即可，不由程序评判理由是否重要。
        sleep_choice: {choice:"decline|defer|sleep",note,until?:延迟到的毫秒}。不想睡可拒绝，defer只约这一次；sleep仍须正常报告采用rest，不伪造睡眠。
        essence_get用offset按1200字符读草稿/当前精华；essence_save: {expected_version,text,source_actions:[实际读过来源的操作ID]}保存可修改草稿；essence_confirm:{expected_version,confirmed:true}明确确认精华，进入下一轮统一材料预算。精华全文总预算384 token，过长时保留草稿并提示重新提炼，不能静默截断；空草稿确认可停用当前精华，旧版用companion_internal_state get section=category_versions id=private_time_essence:current分页查。精华不是人格改写入口。
    """.trimIndent()
}
