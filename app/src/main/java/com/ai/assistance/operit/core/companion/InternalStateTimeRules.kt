package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ceil

/** Conditional, local time evolution. Natural-language reasons never act as host predicates. */
object InternalStateTimeRules {
    val directions = listOf("increase", "decrease")
    val conditions = setOf("always", "not_contacted", "custom")

    data class Step(
        val from: Long,
        val at: Long,
        val value: Double,
        val rateSign: Int,
        val reasons: List<String>,
        val activeDirections: List<String>
    )

    fun isDefined(definition: JSONObject): Boolean =
        definition.has("time_rules") && !definition.isNull("time_rules")

    /** Absence is an explicit legacy state, not a guessed migration of a model-authored rule. */
    fun requireDefined(definition: JSONObject) {
        require(isDefined(definition)) { "新状态必须定义增加、降低的条件/分钟/数值与时间降低下限" }
        validate(definition)
    }

    fun validate(definition: JSONObject) {
        if (!isDefined(definition)) return
        val rules = definition.getJSONObject("time_rules")
        require(rules.keys().asSequence().all { it in setOf("increase", "decrease", "floor", "contact_window_minutes") }) {
            "time_rules 包含未知字段"
        }
        directions.forEach { direction ->
            val rule = rules.getJSONObject(direction)
            require(rule.keys().asSequence().all { it in setOf("condition", "reason", "interval_minutes", "amount") }) {
                "时间涨落规则包含未知字段"
            }
            require(rule.get("condition") is String && rule.getString("condition") in conditions) {
                "condition 必须为 always、not_contacted 或 custom"
            }
            require(rule.get("reason") is String && rule.getString("reason").isNotBlank() && rule.getString("reason").length <= 1000) {
                "时间规则原因必须为 1 至 1000 字的文本"
            }
            number(rule, "interval_minutes")
            number(rule, "amount")
        }
        number(rules, "floor")
        if (usesContact(rules) || rules.has("contact_window_minutes")) {
            number(rules, "contact_window_minutes")
        }
    }

    fun validateConditions(definition: JSONObject, reported: JSONObject) {
        requireDefined(definition)
        require(reported.length() > 0 && reported.keys().asSequence().all { it in directions }) {
            "time_conditions 只能包含 increase、decrease，且不能为空"
        }
        reported.keys().forEach { direction ->
            require(definition.getJSONObject("time_rules").getJSONObject(direction).getString("condition") == "custom") {
                "只有自定义原因可由模型确认，实际联系与时间由系统判断"
            }
            require(reported.get(direction) is Boolean) { "自定义条件是否满足必须是布尔值" }
        }
    }

    /** Renaming and changing a rate do not erase an unchanged condition's real confirmation. */
    fun clearChangedConditions(state: JSONObject, before: JSONObject, after: JSONObject) {
        val confirmations = state.optJSONObject("time_conditions") ?: return
        val origins = state.optJSONObject("time_condition_versions")
        directions.forEach { direction ->
            val oldRule = if (isDefined(before)) before.getJSONObject("time_rules").getJSONObject(direction) else null
            val newRule = if (isDefined(after)) after.getJSONObject("time_rules").getJSONObject(direction) else null
            val same = oldRule != null && newRule != null && newRule.getString("condition") == "custom" &&
                oldRule.getString("condition") == newRule.getString("condition") &&
                oldRule.getString("reason") == newRule.getString("reason")
            if (!same) {
                confirmations.remove(direction)
                origins?.remove(direction)
            }
        }
        if (confirmations.length() == 0) {
            state.remove("time_conditions")
            state.remove("time_condition_versions")
            state.optJSONObject("semantic_versions")?.remove("time_conditions")
        }
    }

    /** Each independently updated predicate retains its own disclosure origin. */
    fun writeConditions(state: JSONObject, definition: JSONObject, reported: JSONObject) {
        validateConditions(definition, reported)
        val values = state.optJSONObject("time_conditions") ?: JSONObject().also { state.put("time_conditions", it) }
        val versions = state.optJSONObject("time_condition_versions") ?: JSONObject().also { state.put("time_condition_versions", it) }
        reported.keys().forEach { direction ->
            values.put(direction, reported.getBoolean(direction))
            versions.put(direction, definition.getInt("version"))
        }
    }

    /** null means the condition has not been established; it does not accrue a rate. */
    fun activity(data: JSONObject, definition: JSONObject, state: JSONObject, chat: String, now: Long): JSONObject {
        if (!isDefined(definition)) return JSONObject()
        val rules = definition.getJSONObject("time_rules")
        val lastContact = if (usesContact(rules)) latestContact(data, definition, chat, now) else null
        val contact = contactActivity(rules, lastContact, now)
        return JSONObject().also { result ->
            directions.forEach { direction ->
                result.put(direction, active(rules.getJSONObject(direction), state, direction, contact) ?: JSONObject.NULL)
            }
        }
    }

    /** Integrates across real contact/expiry boundaries, so UI refresh frequency cannot change values. */
    fun steps(data: JSONObject, definition: JSONObject, state: JSONObject, chat: String, now: Long): List<Step> =
        stepSequence(data, definition, state, chat, now).toList()

    internal fun stepSequence(data: JSONObject, definition: JSONObject, state: JSONObject, chat: String, now: Long): Sequence<Step> = sequence {
        requireDefined(definition)
        val start = state.getLong("at")
        if (now <= start) return@sequence
        val rules = definition.getJSONObject("time_rules")
        val contacts = if (usesContact(rules)) contactsAfter(data, definition, chat, start, now) else emptySequence()
        val contactWindow = if (usesContact(rules)) windowMillis(rules) else 0L
        data class Interval(val from: Long, val at: Long, val rate: BigDecimal, val reasons: List<String>, val directions: List<String>)
        val intervals = sequence {
          val iterator = contacts.iterator()
          var nextContact = if (iterator.hasNext()) iterator.next() else null
          var lastContact = if (usesContact(rules)) latestContact(data, definition, chat, start) else null
          var from = start
          var previous: Interval? = null
          while (from < now) {
            while (nextContact != null && nextContact <= from) {
                lastContact = nextContact
                nextContact = if (iterator.hasNext()) iterator.next() else null
            }
            val expires = lastContact?.let { expiry(it, contactWindow) }?.takeIf { it > from }
            val at = minOf(now, nextContact ?: now, expires ?: now)
            val contact = contactActivity(rules, lastContact, from)
            var rate = BigDecimal.ZERO
            val reasons = mutableListOf<String>()
            val running = mutableListOf<String>()
            directions.forEach { direction ->
                val rule = rules.getJSONObject(direction)
                // Zero/nonpositive intervals explicitly pause a rule; a negative amount reverses
                // its selected direction. Neither requires guessing or silently rewriting input.
                if (active(rule, state, direction, contact) == true && rule.getDouble("amount") != 0.0 &&
                    rule.getDouble("interval_minutes") > 0.0) {
                    val magnitude = BigDecimal.valueOf(rule.getDouble("amount"))
                        .divide(BigDecimal.valueOf(rule.getDouble("interval_minutes")), MathContext.DECIMAL128)
                    rate = rate.add(if (direction == "increase") magnitude else magnitude.negate())
                    reasons += rule.getString("reason")
                    running += direction
                }
            }
            val old = previous
            previous = if (old != null && old.rate.compareTo(rate) == 0 && old.reasons == reasons && old.directions == running) {
                old.copy(at = at)
            } else {
                if (old != null) yield(old)
                Interval(from, at, rate, reasons, running)
            }
            from = at
          }
          previous?.let { yield(it) }
        }
        var value = state.getDouble("value")
        for (interval in intervals) {
            // Decimal arithmetic keeps even very large, opposing user-supplied rates finite
            // until the final state-range clamp; Double Infinity - Infinity would corrupt state.
            val elapsed = BigDecimal.valueOf(interval.at).subtract(BigDecimal.valueOf(interval.from))
                .divide(BigDecimal.valueOf(60000), MathContext.DECIMAL128)
            val next = BigDecimal.valueOf(value).add(interval.rate.multiply(elapsed))
            // A time floor limits a passive decrease. It never lifts an experience reported
            // below that floor, and it never limits the model's next event-based report.
            val limited = if (interval.rate.signum() < 0) {
                next.max(BigDecimal.valueOf(minOf(value, rules.getDouble("floor"))))
            } else next
            val bounded = limited.max(BigDecimal.valueOf(definition.getDouble("min")))
                .min(BigDecimal.valueOf(definition.getDouble("max")))
            // Preserve the time at which a bound is reached. Connecting the start directly to
            // a later poll would otherwise draw a slower slope that never actually happened.
            if (interval.rate.signum() != 0 && bounded.compareTo(next) != 0 && bounded.toDouble() != value) {
                val duration = bounded.subtract(BigDecimal.valueOf(value))
                    .divide(interval.rate, MathContext.DECIMAL128).multiply(BigDecimal.valueOf(60000))
                    .setScale(0, RoundingMode.CEILING).toLong()
                val hitAt = interval.from + duration.coerceIn(0L, interval.at - interval.from)
                yield(Step(interval.from, hitAt, bounded.toDouble(), interval.rate.signum(), interval.reasons, interval.directions))
                if (hitAt < interval.at) yield(Step(hitAt, interval.at, bounded.toDouble(), 0, emptyList(), emptyList()))
            } else yield(Step(interval.from, interval.at, bounded.toDouble(), interval.rate.signum(), interval.reasons, interval.directions))
            value = bounded.toDouble()
        }
    }

    private fun number(value: JSONObject, key: String): Double {
        val raw = value.get(key)
        require(raw is Number && raw.toDouble().isFinite()) { "$key 必须是有限数字，不能用文字代替数字" }
        return raw.toDouble()
    }

    private fun usesContact(rules: JSONObject): Boolean = directions.any {
        rules.getJSONObject(it).getString("condition") == "not_contacted"
    }

    private fun contactTimes(data: JSONObject, definition: JSONObject, chat: String, now: Long): List<Long> =
        (InternalStateEngine.rows(data, "events").asSequence()
            .filter { it.getString("source") == "user_message" &&
                (it.has("contact_at") || it.getString("id").startsWith("message:")) &&
                (definition.getString("scope") == "global" || it.getString("chat") == chat) }
            .map { if (it.has("contact_at")) it.getLong("contact_at") else it.getLong("at") } +
            InternalStateEngine.rows(data, "archived_contacts").asSequence()
                .filter { definition.getString("scope") == "global" || it.getString("chat") == chat }
                .map { it.getLong("at") })
            .filter { it <= now }.distinct().sorted().toList()

    private fun latestContact(data: JSONObject, definition: JSONObject, chat: String, at: Long): Long? =
        listOfNotNull(contactTimes(data, definition, chat, at).lastOrNull(),
            (data as? InternalStateDocument)?.history?.latestContact(
                if (definition.getString("scope") == "global") null else chat, at)).maxOrNull()

    private fun contactsAfter(data: JSONObject, definition: JSONObject, chat: String, start: Long, now: Long): Sequence<Long> = sequence {
        val hot = contactTimes(data, definition, chat, now).filter { it > start }.iterator()
        val archived = ((data as? InternalStateDocument)?.history?.contacts(
            if (definition.getString("scope") == "global") null else chat, start, now) ?: emptySequence()).iterator()
        var a = if (hot.hasNext()) hot.next() else null
        var b = if (archived.hasNext()) archived.next() else null
        while (a != null || b != null) {
            val next = minOf(a ?: Long.MAX_VALUE, b ?: Long.MAX_VALUE)
            yield(next)
            if (a == next) a = if (hot.hasNext()) hot.next() else null
            if (b == next) b = if (archived.hasNext()) archived.next() else null
        }
    }

    private fun windowMillis(rules: JSONObject): Long {
        val minutes = rules.getDouble("contact_window_minutes")
        if (minutes <= 0.0) return 0L
        val millis = minutes * 60000.0
        return if (millis >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else ceil(millis).toLong()
    }

    private fun expiry(at: Long, window: Long): Long = if (at > Long.MAX_VALUE - window) Long.MAX_VALUE else at + window

    private fun contactActivity(rules: JSONObject, lastContact: Long?, at: Long): Boolean? {
        if (!usesContact(rules) || lastContact == null) return null
        return at < expiry(lastContact, windowMillis(rules))
    }

    private fun active(rule: JSONObject, state: JSONObject, direction: String, contact: Boolean?): Boolean? =
        when (rule.getString("condition")) {
            "always" -> true
            "not_contacted" -> contact?.not()
            "custom" -> state.optJSONObject("time_conditions")?.let { if (it.has(direction)) it.getBoolean(direction) else null }
            else -> error("未知时间条件")
        }
}
