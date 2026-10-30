package com.ai.assistance.operit.ui.features.settings.screens

import com.ai.assistance.operit.core.companion.InternalStateEngine
import com.ai.assistance.operit.core.companion.InternalStateTimeRules
import org.json.JSONObject
import java.math.BigDecimal

internal data class StateDirectionDraft(
    val condition: String = "",
    val reason: String = "",
    val interval: String = "",
    val amount: String = "",
)

internal data class StateTimeDraft(
    val increase: StateDirectionDraft = StateDirectionDraft(),
    val decrease: StateDirectionDraft = StateDirectionDraft(),
    val floor: String = "",
    val contactWindow: String = "",
)

internal data class StateEditDraft(
    val value: String,
    val reason: String,
    val kind: String,
    val time: StateTimeDraft?,
)

internal data class StateEditSubmission(val changes: JSONObject, val reason: String)

internal fun stateEditDraft(state: JSONObject): StateEditDraft {
    fun direction(rule: JSONObject) = StateDirectionDraft(
        rule.getString("condition"), rule.getString("reason"),
        stateEditNumber(rule.getDouble("interval_minutes")), stateEditNumber(rule.getDouble("amount")),
    )
    val time = if (InternalStateTimeRules.isDefined(state)) state.getJSONObject("time_rules").let {
        StateTimeDraft(direction(it.getJSONObject("increase")), direction(it.getJSONObject("decrease")),
            stateEditNumber(it.getDouble("floor")),
            if (it.has("contact_window_minutes")) stateEditNumber(it.getDouble("contact_window_minutes")) else "")
    } else null
    return StateEditDraft(
        stateEditNumber(state.getDouble("value")),
        if (state.has("reason") && state.getString("reason").isNotBlank()) state.getString("reason") else "手动调整",
        InternalStateEngine.kind(state), time,
    )
}

/** Send only edited fields: opening the form must not overwrite elapsed time or define old rules. */
internal fun stateEditSubmission(state: JSONObject, draft: StateEditDraft): StateEditSubmission {
    val changes = JSONObject()
    val value = stateEditParseNumber(draft.value, "当前值")
    require(value in state.getDouble("min")..state.getDouble("max")) {
        "当前值须在 " + stateEditNumber(state.getDouble("min")) + " 至 " + stateEditNumber(state.getDouble("max")) + " 之间"
    }
    if (value != state.getDouble("value")) changes.put("value", value)
    val reason = draft.reason.trim()
    require(reason.isNotEmpty() && reason.length <= 1500) { "修改原因须填写 1 至 1500 字" }
    if (draft.kind != InternalStateEngine.kind(state)) {
        require(draft.kind in InternalStateEngine.kinds) { "请选择驱力、情感、情绪或心理" }
        changes.put("kind", draft.kind)
    }
    val originalTime = stateEditDraft(state).time
    if (draft.time != originalTime) {
        requireNotNull(draft.time) { "已有时间规则不能在此删除；可将变化间隔或变化量设为 0 暂停" }
        fun direction(input: StateDirectionDraft, label: String): JSONObject {
            require(input.condition in InternalStateTimeRules.conditions) { "请选择" + label + "条件" }
            val explanation = input.reason.trim()
            require(explanation.isNotEmpty() && explanation.length <= 1000) { label + "原因须填写 1 至 1000 字" }
            return JSONObject().put("condition", input.condition).put("reason", explanation)
                .put("interval_minutes", stateEditParseNumber(input.interval, label + "间隔"))
                .put("amount", stateEditParseNumber(input.amount, label + "变化量"))
        }
        val rules = JSONObject().put("increase", direction(draft.time.increase, "增加"))
            .put("decrease", direction(draft.time.decrease, "减少"))
            .put("floor", stateEditParseNumber(draft.time.floor, "时间下降最低值"))
        if (draft.time.increase.condition == "not_contacted" || draft.time.decrease.condition == "not_contacted") {
            rules.put("contact_window_minutes", stateEditParseNumber(draft.time.contactWindow, "未联系等待时间"))
        }
        InternalStateTimeRules.validate(JSONObject().put("time_rules", rules))
        val originalRules = state.optJSONObject("time_rules")
        if (originalRules == null || !sameStateTimeRules(originalRules, rules)) changes.put("time_rules", rules)
    }
    require(changes.length() > 0 || reason != state.optString("reason").trim()) {
        "尚未修改内容，请调整数值、原因、分类或时间规则后再保存"
    }
    return StateEditSubmission(changes, reason)
}

private fun sameStateTimeRules(before: JSONObject, after: JSONObject): Boolean {
    if (before.getDouble("floor") != after.getDouble("floor")) return false
    if (InternalStateTimeRules.directions.any { direction ->
        val first = before.getJSONObject(direction)
        val second = after.getJSONObject(direction)
        first.getString("condition") != second.getString("condition") || first.getString("reason") != second.getString("reason") ||
            first.getDouble("interval_minutes") != second.getDouble("interval_minutes") || first.getDouble("amount") != second.getDouble("amount")
    }) return false
    return !after.has("contact_window_minutes") || before.has("contact_window_minutes") &&
        before.getDouble("contact_window_minutes") == after.getDouble("contact_window_minutes")
}

private val stateEditNumberPattern = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

private fun stateEditParseNumber(text: String, label: String): Double {
    val cleaned = text.trim()
    require(stateEditNumberPattern.matches(cleaned)) { label + "只能填写数字，支持 0、小数和自定义值" }
    val value = cleaned.toDoubleOrNull()
    require(value != null && value.isFinite()) { label + "须填写可保存的有限数字" }
    return value
}

internal fun stateEditNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
