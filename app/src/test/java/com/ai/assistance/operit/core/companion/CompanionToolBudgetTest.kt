package com.ai.assistance.operit.core.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionToolBudgetTest {
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
