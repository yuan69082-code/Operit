package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import com.ai.assistance.operit.core.companion.PrivateTimePolicy.objects

/** Uses the existing assistant archive and dispatcher. Nothing here calls a model or writes
 * psychological state. Reads of the calendar never load past plans or memory contents. */
internal class PrivateTime(private val context: Context) {
    private val store = CompanionStore(context)
    private val cachePrefix = context.filesDir.absolutePath + ":"
    fun config(role: String): JSONObject? = cached("$role:config") { store.activityRead(role, "private_time_config", "current") }
    private fun plan(role: String, date: String) = cached("$role:$date") { store.activityRead(role, "private_time_plan", date) }
    private fun cached(key: String, read: () -> JSONObject?): JSONObject? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cache.size > 64) cache.entries.removeAll { now - it.value.first >= 30_000 }
        while (cache.size >= 128) cache.entries.minByOrNull { it.value.first }?.let { cache.remove(it.key, it.value) } ?: break
        val entry = cache[cachePrefix + key]
        val text = if (entry != null && now - entry.first < 30_000) entry.second else read()?.toString().also {
            cache[cachePrefix + key] = now to it
        }
        return text?.let(::JSONObject)
    }
    private fun invalidate(role: String, date: String = "config") { cache.remove(cachePrefix + "$role:$date") }

    fun configure(role: String, a: JSONObject, manual: Boolean = false): JSONObject {
        SubjectContracts.fields(a, setOf("expected_version", "enabled", "start", "end", "zone", "sleep_time", "fatigue_episode", "fatigue_threshold") +
            if (manual) setOf("memory_location") else emptySet())
        PrivateTimePolicy.validateConfig(a)
        val row = store.activityWrite(role, "private_time_config", "current", "settings") { old ->
            require(a.getInt("expected_version") == (old?.getInt("version") ?: 0)) { "配置版本已变化，请先读取" }
            JSONObject(a.toString()).apply {
                remove("expected_version")
                for (key in listOf("sleep_time", "fatigue_episode", "fatigue_threshold"))
                    if (!has(key) && old?.has(key) == true) put(key, old.get(key))
                PrivateTimePolicy.validateConfig(this)
            }
                .put("id", "current").put("parent", "settings").put("owner", role)
                .put("memory_location", if (manual) a.optString("memory_location") else old?.optString("memory_location").orEmpty())
                .put("at", System.currentTimeMillis())
        }
        invalidate(role)
        return row
    }

    fun status(role: String, date: String? = null, menu: Boolean = true): JSONObject {
        val c = config(role)
        val selected = date?.also(LocalDate::parse) ?: c?.let { PrivateTimePolicy.upcoming(it, System.currentTimeMillis()).date }
        return JSONObject().put("configuration", c ?: JSONObject.NULL)
            .put("setup_required", c == null).put("date", selected ?: JSONObject.NULL)
            .put("plan", selected?.let { plan(role, it) } ?: JSONObject.NULL)
            .put("now", System.currentTimeMillis()).apply {
                if (menu) put("menu", PrivateTimePolicy.menu).put("operations", PrivateTimePolicy.help)
            }
    }
    fun history(role: String, offset: Int): JSONObject {
        val rows = store.activityPage(role, "private_time_plan", "calendar", offset)
        return JSONObject().put("items", JSONArray(rows.take(8).map {
            JSONObject().put("date", it.getString("date")).put("version", it.getInt("version"))
                .put("items", JSONArray(it.getJSONArray("items").objects().map { item ->
                    JSONObject().put("id", item.getString("id")).put("activity", item.getString("activity")).put("status", item.getString("status"))
                }))
        })).put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
    }
    fun savePlan(role: String, a: JSONObject): JSONObject {
        val c = requireNotNull(config(role)) { "先free_config定义自己的自由时间" }
        val date = a.getString("date")
        val row = store.activityWrite(role, "private_time_plan", date, "calendar") { old ->
            PrivateTimePolicy.plan(c, a, old, System.currentTimeMillis()).put("owner", role)
        }
        invalidate(role, date)
        return row
    }
    fun progress(role: String, a: JSONObject): JSONObject {
        val date = a.getString("date")
        // Read receipts before the plan write transaction, avoiding a second SQLite transaction
        // inside the first one. Their completed outcome is immutable.
        val receipts = mutableMapOf<String, JSONObject?>()
        val records = mutableMapOf<String, JSONObject?>()
        val priorResults = plan(role, date)?.getJSONArray("items")?.objects()?.firstOrNull { it.getString("id") == a.getString("slot_id") }?.optJSONArray("review_results")
        (a.optJSONArray("review_results") ?: priorResults)?.objects()?.forEach { r ->
            r.optJSONArray("action_ids")?.let { ids -> for (i in 0 until ids.length()) receipts[ids.getString(i)] = store.actionRead(role, ids.getString(i)) }
            r.optJSONArray("record_refs")?.objects()?.forEach { ref ->
                val kind = ref.getString("kind"); val id = ref.getString("id")
                require(kind in setOf("inner", "episode", "experience", "direction", "union")) { "record_refs.kind未知" }
                records["$kind:$id"] = if (kind == "inner") InnerStructure.record(InternalStateStore(context).read(role), id)
                    else store.activityRead(role, kind, id)
            }
        }
        val row = store.activityWrite(role, "private_time_plan", date, "calendar") {
            PrivateTimePolicy.progress(requireNotNull(it) { "当天没有计划" }, a, { ref -> records["${ref.getString("kind")}:${ref.getString("id")}"] }) { id -> receipts[id] }
        }
        invalidate(role, date)
        return row
    }

    fun sleepChoice(role: String, a: JSONObject): JSONObject {
        require(a.getString("choice") in setOf("sleep", "decline", "defer")) { "choice: sleep|decline|defer" }
        SubjectContracts.text(a, "note")
        if (a.getString("choice") == "defer") require(a.getLong("until") > System.currentTimeMillis()) { "延期时间须在未来" }
        val row = store.activityWrite(role, "private_time_sleep", "current", "settings") { old ->
            JSONObject(a.toString()).put("id", "current").put("parent", "settings").put("owner", role)
                .put("at", System.currentTimeMillis()).put("fatigue_latched", old?.optBoolean("fatigue_latched") ?: false)
        }
        invalidate(role, "sleep")
        return row
    }

    fun next(role: String, chat: String, now: Long, claim: (String, String) -> SubjectLife.Work?): SubjectLife.Work? {
        val c = config(role) ?: return null
        if (!c.getBoolean("enabled")) return null
        val w = PrivateTimePolicy.upcoming(c, now)
        if (now >= w.start - 3_600_000 && now < w.end && plan(role, w.date) == null) {
            claim("free-prep:${w.date}", "你的自由时间快到了或已经开始，范围${c.getString("start")}～${c.getString("end")}（${c.getString("zone")}），日期${w.date}。" +
                "先companion_life free_status读取活动菜单和配置，再自己决定本次做什么，free_plan保存时间表。可以留空、取消，不必把时段填满。主动选checks才有中途续接。" + "可以探索、回顾整理提炼、写日记/书房作品、睡觉做梦、随兴趣逛世界或HY；不是必须学习，也可以随时给园园留言。完整活动范围和操作格式先从free_status读取。")?.let { return it }
        }
        // Include the just-ended window so the last slot gets one wrap-up opportunity.
        val zone = ZoneId.of(c.getString("zone"))
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (date in listOf(today.minusDays(1).toString(), today.toString()).distinct()) {
            val p = plan(role, date) ?: continue
            if (now > p.getLong("window_end") + 3_600_000) continue // no stale replay storm
            var pending = p.optJSONObject("pending_notice")
            if (pending == null) {
                if (PrivateTimePolicy.due(JSONObject(p.toString()), now) == null) continue
                val saved = store.activityWrite(role, "private_time_plan", date, "calendar") { current ->
                    requireNotNull(current)
                    // Recompute from the locked current version; never overwrite a new choice
                    // with the dispatcher snapshot. The notice is a durable outbox.
                    if (!current.has("pending_notice")) PrivateTimePolicy.due(current, now)?.let { notice ->
                        val slot = current.getJSONArray("items").objects().first { it.getString("id") == notice.slotId }
                        val priorActivities = slot.optJSONArray("activity_ids") ?: JSONArray()
                        val text = notice.text + " 本时段之前的活动索引：$priorActivities。先companion_life activities按id核对原进度，再读activity_part/action真实回执；索引不代表已经执行。"
                        current.put("pending_notice", JSONObject().put("key", notice.key).put("text", text).put("slot_id", notice.slotId))
                        priorActivities.put(ActivityNotes.scheduledId(role, "wake:free:${notice.key}"))
                        slot.put("activity_ids", priorActivities)
                    }
                    current
                }
                invalidate(role, date)
                pending = saved.optJSONObject("pending_notice") ?: continue
            }
            val key = pending.getString("key")
            val work = claim("free:$key", pending.getString("text"))
            store.activityWrite(role, "private_time_plan", date, "calendar") { current ->
                requireNotNull(current)
                if (current.optJSONObject("pending_notice")?.optString("key") == key) current.remove("pending_notice")
                current
            }
            invalidate(role, date)
            if (work != null) return work
        }
        val choice = cached("$role:sleep") { store.activityRead(role, "private_time_sleep", "current") }
        if (choice?.optString("choice") == "defer" && choice.getLong("until") <= now)
            claim("sleep-defer:${choice.getInt("version")}", "你自己延后的睡觉时间到了。现在要睡、拒绝还是再延后？sleep_choice保存选择；睡觉再通过正常rest报告采用。")?.let { return it }
        val time = c.optString("sleep_time")
        if (time.isNotBlank()) {
            val at = today.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
            if (now in at until at + 3_600_000 && (choice?.optLong("at") ?: 0) < at) claim("sleep-time:$today:$time",
                "你自定义的睡觉提醒时间到了。现在要睡一会、拒绝还是延后？用sleep_choice保存，不想睡不必睡；睡眠与清醒整理分开安排。")?.let { return it }
        }
        // Only a subject/user-bound real episode is considered fatigue. Rest drive, number of
        // requests and clock time are not silently converted into a new fatigue score.
        val fatigue = c.optString("fatigue_episode")
        if (fatigue.isNotBlank()) {
            val episode = cached("$role:fatigue:$fatigue") { store.activityRead(role, "episode", fatigue) }?.optJSONObject("content")
            val intensity = episode?.optDouble("intensity", Double.NaN) ?: Double.NaN
            val high = intensity.isFinite() && intensity >= c.getDouble("fatigue_threshold")
            val latched = choice?.optBoolean("fatigue_latched") ?: false
            if (high != latched) {
                store.activityWrite(role, "private_time_sleep", "current", "settings") { old ->
                    (old ?: JSONObject().put("id", "current").put("parent", "settings")).put("fatigue_latched", high)
                }
                invalidate(role, "sleep")
                if (high) return claim("sleep-fatigue:${choice?.optInt("version", 0) ?: 0}:$now",
                    "你指定的疲劳事件：${fatigue}强度达到自定提醒线（$intensity）。要不要睡一会？可以拒绝或延后，sleep_choice保存。提醒不改变任何感受，也不自动入睡。")
            }
        }
        return null
    }

    fun essence(role: String, offset: Int = 0): JSONObject {
        val row = store.activityRead(role, "private_time_essence", "current") ?: return JSONObject().put("version", 0).put("draft", "").put("confirmed", "")
        require(offset >= 0)
        return JSONObject(row.toString()).apply {
            for (key in listOf("draft", "confirmed")) {
                val full = row.optString(key)
                put(key, full.drop(offset).take(1200)).put("${key}_next_offset", if (full.length > offset + 1200) offset + 1200 else JSONObject.NULL)
            }
        }
    }
    fun saveEssence(role: String, a: JSONObject, confirm: Boolean): JSONObject {
        val sourceRecords = if (confirm) JSONArray() else a.optJSONArray("source_records") ?: JSONArray()
        sourceRecords.objects().forEach { ref ->
            val kind = ref.getString("kind"); val id = ref.getString("id")
            require(kind in setOf("inner", "episode", "experience", "direction", "union", "dream_understanding")) { "source_records.kind未知" }
            val row = if (kind == "inner") InnerStructure.record(InternalStateStore(context).read(role), id) else store.activityRead(role, kind, id)
            require(row?.optInt("version") == ref.getInt("version")) { "精华来源记录或版本不存在，请先实际读取" }
        }
        val bindings = if (!confirm) (a.optJSONArray("source_actions") ?: JSONArray()).let { ids ->
            require(ids.length() + sourceRecords.length() > 0 || a.getString("text").isBlank()) { "精华草稿须关联实际来源操作source_actions或已有理解source_records" }
            JSONArray((0 until ids.length()).map { index ->
                val id = ids.getString(index)
                val action = requireNotNull(store.actionRead(role, id)) { "来源操作不存在：$id" }
                require(action.optString("status") == "completed" && action.optJSONObject("receipt")?.optBoolean("success") == true) { "来源操作没有成功返回" }
                id
            })
        } else null
        val row = store.activityWrite(role, "private_time_essence", "current", "private") { old ->
            require(a.getInt("expected_version") == (old?.getInt("version") ?: 0)) { "精华版本已变化，请先essence_get" }
            val result = old ?: JSONObject().put("id", "current").put("parent", "private").put("owner", role)
            if (confirm) {
                require(a.getBoolean("confirmed")) { "只有明确确认才进入上下文" }
                require(result.has("draft")) { "先保存草稿" }
                require(SubjectMaterialBudget.estimate(result.getString("draft")) <= 384) { "精华全文超过384 token材料份额；草稿仍在，请再提炼或归档部分内容，未替换当前精华" }
                result.put("confirmed", result.getString("draft")).put("confirmed_sources", JSONObject().put("actions", result.getJSONArray("source_actions")).put("records", result.optJSONArray("source_records") ?: JSONArray()))
                    .put("confirmed_at", System.currentTimeMillis())
            } else result.put("draft", a.getString("text")).put("source_actions", bindings).put("source_records", sourceRecords)
            result.put("at", System.currentTimeMillis())
        }
        return JSONObject().put("saved", true).put("version", row.getInt("version")).put("confirmed", confirm)
    }

    fun materials(role: String): List<JSONObject> = buildList {
        if (config(role) == null) add(JSONObject().put("id", "private-time-setup").put("type", "private_time_setup")
            .put("owner", role).put("text", "自由时间尚未定义。可用companion_life free_status查看菜单，然后free_config选择自己的时段。OB记忆位置请园园在私域自由时间页指定。没有设置不会自动唤醒。")
            .put("subject_private_material", true))
        val e = store.activityRead(role, "private_time_essence", "current")
        if (!e?.optString("confirmed").isNullOrBlank()) add(JSONObject().put("id", "essence:${e!!.getInt("version")}")
            .put("type", "confirmed_essence").put("status", "retrieved").put("owner", role).put("author", role).put("at", e.getLong("confirmed_at"))
            .put("text", e.getString("confirmed")).put("subject_private_material", true).put("not_new_occurrence", true)
            .put("locator", JSONObject().put("tool", "companion_life essence_get").put("version", e.getInt("version"))))
    }

    companion object {
        private val cache = ConcurrentHashMap<String, Pair<Long, String?>>()
    }
}
