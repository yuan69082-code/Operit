package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateTimeRulesTest {
    private val minute = 60000L
    private fun rule(condition: String = "always", amount: Double = 0.0, interval: Double = 1.0) = JSONObject()
        .put("condition", condition).put("reason", "由主体填写的原因")
        .put("interval_minutes", interval).put("amount", amount)
    private fun definition(increase: JSONObject = rule(), decrease: JSONObject = rule(), floor: Double = 0.0,
                           scope: String = "global") = JSONObject()
        .put("id", "felt_state").put("name", "自己的状态").put("description", "由真实体验定义")
        .put("kind", "psychology").put("min", 0).put("max", 100).put("baseline", 50)
        .put("scope", scope).put("private", false).put("share", false).put("participates", true)
        .put("effects", JSONArray()).put("time_rules", JSONObject().put("increase", increase)
            .put("decrease", decrease).put("floor", floor).put("contact_window_minutes", 2))
    private fun data(d: JSONObject = definition()) = JSONObject().also { data ->
        InternalStateEngine.event(data, "seed", "wake", "a", "真实唤醒", 0, applyTriggers = false)
        InternalStateEngine.evolve(data, "create", d.getString("id"), 0, d, null,
            "建立自己的状态", listOf("seed"), "a", 0)
    }
    private fun value(data: JSONObject, chat: String = "a") = InternalStateEngine.snapshot(data, chat, 0).single().getDouble("value")
    private fun contact(data: JSONObject, id: String, at: Long, receivedAt: Long = at, chat: String = "a") =
        InternalStateEngine.event(data, "message:$id", "user_message", chat, "真实用户消息", receivedAt,
            applyTriggers = false, contactAt = at)

    @Test fun numericChoicesAcceptZeroFractionsSignedValuesAndLargeFiniteCustomValues() {
        InternalStateEngine.validate(definition(rule(amount = 1e300, interval = 1e-300), rule(amount = -0.25, interval = 0.0), floor = -1e300))
        InternalStateEngine.validate(definition(rule(interval = -3.0), floor = 1e300))
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateEngine.validate(definition(rule().put("amount", "1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateEngine.validate(definition(rule("contacted")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateEngine.validate(definition().put("kind", "custom"))
        }
    }

    @Test fun zeroOrNegativeIntervalPausesAndZeroAmountDoesNotChangeValue() {
        val data = data(definition(rule(amount = 100.0, interval = 0.0), rule(amount = 100.0, interval = -1.0)))
        InternalStateEngine.advance(data, "a", 100 * minute)
        assertEquals(50.0, value(data), 0.0)
        assertEquals(100 * minute, InternalStateEngine.rows(data, "instances").single().getLong("at"))
        val zero = data(definition(rule(amount = 0.0, interval = 0.01)))
        InternalStateEngine.advance(zero, "a", 100 * minute)
        assertEquals(50.0, value(zero), 0.0)
    }

    @Test fun opposingVeryLargeRatesCancelBeforeClampingAndSignedAmountReversesDirection() {
        val balanced = data(definition(rule(amount = 1e300, interval = 1e-300), rule(amount = 1e300, interval = 1e-300)))
        InternalStateEngine.advance(balanced, "a", minute)
        assertEquals(50.0, value(balanced), 0.0)
        val huge = data(definition(rule(amount = 1e300, interval = 1e-300)))
        InternalStateEngine.advance(huge, "a", minute)
        assertEquals(100.0, value(huge), 0.0)
        val signed = data(definition(rule(amount = -2.5)))
        InternalStateEngine.advance(signed, "a", 2 * minute)
        assertEquals(45.0, value(signed), 0.0)
    }

    @Test fun floorOnlyLimitsPassiveDecreaseAndNeverLiftsAnExperienceBelowIt() {
        val data = data(definition(decrease = rule(amount = 10.0), floor = 30.0))
        InternalStateEngine.advance(data, "a", 10 * minute)
        assertEquals(30.0, value(data), 0.0)
        InternalStateEngine.rows(data, "instances").single().put("value", 20.0)
        InternalStateEngine.advance(data, "a", 20 * minute)
        assertEquals(20.0, value(data), 0.0)
        val high = data(definition(decrease = rule(amount = 10.0), floor = 10000.0))
        InternalStateEngine.advance(high, "a", 10 * minute)
        assertEquals(50.0, value(high), 0.0)
        val low = data(definition(decrease = rule(amount = 10.0), floor = -10000.0))
        InternalStateEngine.advance(low, "a", 10 * minute)
        assertEquals(0.0, value(low), 0.0)
    }

    @Test fun hittingABoundRecordsTheRealHitTimeInsteadOfTheLaterPollTime() {
        val data = data(definition(rule(amount = 10.0)))
        InternalStateEngine.advance(data, "a", 10 * minute)
        val change = InternalStateEngine.rows(InternalStateEngine.rows(data, "events").last(), "changes").single()
        assertEquals(5 * minute, change.getLong("interval_end"))
        assertEquals(100.0, change.getDouble("after"), 0.0)
        assertEquals(10 * minute, InternalStateEngine.rows(data, "instances").single().getLong("at"))
    }

    @Test fun requestRegenerationIsNotContactAndUnknownContactDoesNotInventElapsedAbsence() {
        val data = data(definition(rule("not_contacted", amount = 1.0)))
        InternalStateEngine.event(data, "input:retry", "user_message", "a", "已有输入的重新生成", minute, applyTriggers = false)
        InternalStateEngine.advance(data, "a", 5 * minute)
        assertEquals(50.0, value(data), 0.0)
        contact(data, "first", 5 * minute)
        InternalStateEngine.advance(data, "a", 10 * minute)
        assertEquals(53.0, value(data), 0.0)
        InternalStateEngine.event(data, "input:regenerate", "user_message", "a", "再次生成", 10 * minute, applyTriggers = false)
        InternalStateEngine.advance(data, "a", 11 * minute)
        assertEquals(54.0, value(data), 0.0)
    }

    @Test fun contactExpiryAndFractionalRateAreIndependentOfPollingFrequency() {
        val once = data(definition(rule("not_contacted", amount = 2.5, interval = 2.0)))
        contact(once, "start", 0)
        val often = JSONObject(once.toString())
        InternalStateEngine.advance(once, "a", 10 * minute)
        (1..100).forEach { InternalStateEngine.advance(often, "a", it * minute / 10) }
        assertEquals(60.0, value(once), 1e-8)
        assertEquals(value(once), value(often), 1e-8)
        val time = InternalStateEngine.rows(once, "events").last { it.getString("source") == "time" }
        val change = InternalStateEngine.rows(time, "changes").single()
        assertEquals(2 * minute, change.getLong("interval_start"))
        assertEquals(10 * minute, change.getLong("interval_end"))
    }

    @Test fun delayedMessageUsesActualContactTimeWithoutReplayingSettledHistory() {
        val data = data(definition(rule("not_contacted", amount = 1.0)))
        contact(data, "first", 0)
        InternalStateEngine.advance(data, "a", 3 * minute)
        contact(data, "delayed", 4 * minute, receivedAt = 8 * minute)
        assertEquals(54.0, value(data), 1e-8) // 2..4 and 6..8 minutes accrue exactly once.
        contact(data, "delayed", 4 * minute, receivedAt = 20 * minute)
        assertEquals(54.0, value(data), 1e-8)
    }

    @Test fun globalContactCrossesWindowsButSessionContactDoesNot() {
        val global = data(definition(rule("not_contacted", amount = 1.0)))
        val local = data(definition(rule("not_contacted", amount = 1.0), scope = "session"))
        contact(global, "other", 0, chat = "b")
        contact(local, "other", 0, chat = "b")
        InternalStateEngine.advance(global, "a", 3 * minute)
        InternalStateEngine.advance(local, "a", 3 * minute)
        InternalStateEngine.advance(local, "b", 3 * minute)
        assertEquals(51.0, value(global), 0.0)
        assertEquals(50.0, value(local), 0.0)
        assertEquals(51.0, value(local, "b"), 0.0)
    }

    @Test fun customConditionNeedsConfirmationAndChangedMeaningClearsOnlyThatDirection() {
        val data = data(definition(rule("custom", amount = 1.0), rule("custom", amount = 0.5)))
        val state = InternalStateEngine.rows(data, "instances").single()
        val old = InternalStateEngine.definitions(data).single()
        InternalStateEngine.advance(data, "a", minute)
        assertEquals(50.0, value(data), 0.0)
        InternalStateTimeRules.writeConditions(state, old, JSONObject().put("increase", true).put("decrease", false))
        InternalStateEngine.advance(data, "a", 3 * minute)
        assertEquals(52.0, value(data), 0.0)
        val renamed = JSONObject(old.toString()).put("name", "新名字")
        InternalStateTimeRules.clearChangedConditions(state, old, renamed)
        assertTrue(state.getJSONObject("time_conditions").getBoolean("increase"))
        val different = JSONObject(renamed.toString())
        different.getJSONObject("time_rules").getJSONObject("increase").put("reason", "另一个条件")
        InternalStateTimeRules.clearChangedConditions(state, renamed, different)
        assertFalse(state.getJSONObject("time_conditions").has("increase"))
        assertFalse(state.getJSONObject("time_conditions").getBoolean("decrease"))
        assertFalse(state.getJSONObject("time_condition_versions").has("increase"))
    }

    @Test fun conditionalDefinitionDoesNotStackLegacyHalfLifeOrNumericEventTriggers() {
        val definition = definition(rule(amount = 1.0)).put("halfLifeHours", 1).put("maxDelta", 20)
            .put("triggers", JSONArray().put(JSONObject().put("source", "time").put("delta", 20))
                .put(JSONObject().put("source", "reading").put("delta", 20)))
        val data = data(definition)
        InternalStateEngine.event(data, "read", "reading", "a", "真实阅读", minute)
        assertEquals(51.0, value(data), 0.0)
    }

    @Test fun zeroContactWindowIsImmediateAndHugeWindowDoesNotOverflow() {
        val immediate = definition(rule("not_contacted", amount = 1.0))
        immediate.getJSONObject("time_rules").put("contact_window_minutes", 0)
        val data = data(immediate)
        contact(data, "first", 0)
        InternalStateEngine.advance(data, "a", minute)
        assertEquals(51.0, value(data), 0.0)
        val huge = definition(rule("not_contacted", amount = 1.0))
        huge.getJSONObject("time_rules").put("contact_window_minutes", 1e300)
        val waiting = data(huge)
        contact(waiting, "first", 0)
        InternalStateEngine.advance(waiting, "a", 1000 * minute)
        assertEquals(50.0, value(waiting), 0.0)
    }

    @Test fun privateCustomDirectionStaysHiddenAfterOtherDirectionIsReportedPublicly() {
        val privateDefinition = definition(rule("custom", amount = 1.0), rule("custom", amount = 1.0)).put("private", true)
        val data = data(privateDefinition)
        val state = InternalStateEngine.rows(data, "instances").single()
        InternalStateTimeRules.writeConditions(state, InternalStateEngine.definitions(data).single(),
            JSONObject().put("increase", true).put("decrease", true))
        val publicDefinition = definition(rule("custom", amount = 1.0), rule("custom", amount = 1.0))
        InternalStateEngine.evolve(data, "revise", "felt_state", 1, publicDefinition, null,
            "从当前开始公开", listOf("seed"), "a", 0)
        InternalStateTimeRules.writeConditions(state, InternalStateEngine.definitions(data).single(), JSONObject().put("increase", false))
        val snapshot = InternalStateEngine.snapshot(data, "a", 0).single()
        val labels = snapshot.getJSONObject("disclosure").getJSONObject("fields")
        assertFalse(labels.getJSONObject("time_conditions").getJSONObject("fields").getJSONObject("increase").getBoolean("private"))
        assertTrue(labels.getJSONObject("time_activity").getJSONObject("fields").getJSONObject("decrease").getBoolean("private"))
        val shown = InternalStateEngine.observation(data, "a", 0, false).getJSONArray("states").getJSONObject(0)
        assertFalse(shown.getJSONObject("time_conditions").has("decrease"))
        assertFalse(shown.getJSONObject("time_activity").has("decrease"))
        assertFalse(shown.getJSONObject("time_activity").getBoolean("increase"))
        InternalStateEngine.advance(data, "a", minute)
        val view = InternalStateEngine.observation(data, "a", minute, false)
        assertEquals(0, view.getJSONArray("events").length())
        assertEquals(49.0, view.getJSONArray("states").getJSONObject(0).getDouble("value"), 0.0)
    }

    @Test fun hiddenHistoryBreaksTrendContinuityEvenWhenValuesMatchAgain() {
        val d = definition(rule(amount = 1.0))
        val data = data(d)
        InternalStateEngine.advance(data, "a", minute)
        InternalStateEngine.evolve(data, "revise", "felt_state", 1, JSONObject(d.toString()).put("private", true), null,
            "收起接下来的体验", listOf("seed"), "a", minute)
        InternalStateEngine.advance(data, "a", 2 * minute)
        InternalStateEngine.evolve(data, "revise", "felt_state", 2, d, null,
            "从当前重新公开", listOf("seed"), "a", 2 * minute)
        InternalStateEngine.advance(data, "a", 3 * minute)
        val changes = InternalStateEngine.rows(InternalStateEngine.observation(data, "a", 3 * minute, false), "events")
            .flatMap { InternalStateEngine.rows(it, "changes") }
        assertEquals(2, changes.size)
        assertFalse(changes.last().getBoolean("continuous_from_previous"))
    }
}
