package com.ai.assistance.operit.core.companion

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import org.json.JSONObject

enum class DreamTrigger(val label: String, val instruction: String) {
    HALF("50% 随机", "进入睡眠时系统只抽一次，概率50%。"),
    SUBJECT("主体主动选择", "入睡时提交dream_plan表示想做梦，省略表示此段不安排。"),
    STIMULUS("当天强烈刺激", "当天采用过强度≥0.75且处于前景的心理事件时，由系统触发。");

    companion object {
        fun read(id: String) = entries.firstOrNull { it.name == id } ?: HALF
    }

    internal fun apply(rest: JSONObject, prior: JSONObject?, data: JSONObject, now: Long,
                       draw: () -> Boolean = { Random.nextBoolean() }) {
        if (rest.optString("mode") != "sleep") {
            if (prior?.has("dream_trigger") == true) rest.remove("dream_plan")
            return
        }
        if (prior?.optString("state") == "resting" && prior.optString("mode") == "sleep") {
            if (prior.has("dream_trigger")) {
                rest.put("dream_trigger", prior.getString("dream_trigger"))
                rest.put("dream_stimulus", prior.optJSONObject("dream_stimulus"))
            }
            rest.remove("dream_plan")
            rest.put("dream_plan", prior.optJSONObject("dream_plan"))
            return
        }

        val stimulus = if (this == STIMULUS) strongEpisode(data, now) else null
        val plan = rest.optJSONObject("dream_plan")
        val admitted = when (this) {
            HALF -> draw()
            SUBJECT -> plan != null
            STIMULUS -> stimulus != null
        }
        rest.put("dream_trigger", name)
        if (admitted) rest.put("dream_plan", plan ?: JSONObject().put("steps", 3).put("theme_mode", "random"))
        else rest.remove("dream_plan")
        stimulus?.let {
            val content = it.getJSONObject("content")
            rest.put("dream_stimulus", JSONObject().put("id", it.getString("id"))
                .put("version", it.getInt("version")).put("at", it.getLong("at"))
                .put("category_ref", content.getString("category_ref"))
                .put("understanding", content.optString("understanding").take(280)))
        }
    }

    private fun strongEpisode(data: JSONObject, now: Long): JSONObject? {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val staged = data.optJSONObject("category_commit")?.optJSONArray("rows")
        val current = staged?.let { rows ->
            (rows.length() - 1 downTo 0).asSequence().map { rows.getJSONObject(it) }
                .firstOrNull { qualifiesDreamStimulus(it, start, now) }
        }
        return current ?: (data as? InternalStateDocument)?.history?.strongDreamEpisode(start, now)
    }
}

internal fun qualifiesDreamStimulus(row: JSONObject, start: Long, now: Long): Boolean {
    if (row.optString("storage_kind") != "episode" || row.optLong("at") !in start..now) return false
    val content = row.optJSONObject("content") ?: return false
    return content.optString("attention") == "foreground" && content.optDouble("intensity") >= 0.75
}
