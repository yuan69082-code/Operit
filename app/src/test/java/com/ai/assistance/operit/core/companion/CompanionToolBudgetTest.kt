package com.ai.assistance.operit.core.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionToolBudgetTest {
    @Test fun internalStateToolPromptKeepsApiContractWithoutRepeatingTheSubjectManual() {
        val prompt = CompanionTools.prompts().single { it.name == "companion_internal_state" }
        assertTrue("Tool overview should stay concise", prompt.description.length < 300)
        assertTrue(prompt.description.contains("operit_state_report"))
        val parameters = prompt.parametersStructured.orEmpty().associateBy { it.name }
        assertTrue(parameters["definition"]?.description?.contains("time_rules") == true)
        assertTrue(parameters["definition"]?.description?.contains("not_contacted") == true)
        assertTrue(parameters["section"]?.description?.contains("inner_record_chunk") == true)
        assertTrue(parameters["evidence"]?.description?.contains("事件ID") == true)
    }

    @Test fun exhaustedWakeReturnsDenialWithoutThrowingOrIncreasingCount() {
        val budget = CompanionToolBudget()
        repeat(12) { assertTrue(budget.admit("read_file", null)) }
        repeat(50) { assertFalse(budget.admit("read_file", null)) }
        assertEquals(12, budget.used)
    }

    @Test fun wakeCanStillArrangeOrPauseItsOwnExplorationAfterLimit() {
        val budget = CompanionToolBudget()
        repeat(12) { budget.admit("read_file", null) }
        assertTrue(budget.admit("companion_schedule", "schedule"))
        assertTrue(budget.admit("companion_schedule", "cancel"))
        assertTrue(budget.admit("companion_explore", "schedule"))
        assertTrue(budget.admit("companion_explore", "pause"))
        assertFalse(budget.admit("companion_explore", "status"))
        assertFalse(budget.admit("companion_explore", null))
        assertEquals(12, budget.used)
    }

    @Test fun nextWakeStartsWithItsOwnFullAllowance() {
        val budget = CompanionToolBudget()
        repeat(12) { budget.admit("read_file", null) }
        assertFalse(budget.admit("read_file", null))
        budget.reset()
        assertEquals(0, budget.used)
        assertTrue(budget.admit("read_file", null))
        assertEquals(1, budget.used)
    }
}
