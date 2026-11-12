package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.InputProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionInputStateTest {
    @Test
    fun oldStreamCompletionAndErrorCannotOverwriteTheNextTurn() {
        val input = ExecutionInputState()
        input.begin(1)
        input.publish(1, InputProcessingState.Receiving("first response"))
        input.begin(2)
        val current = InputProcessingState.ExecutingTool("current tool")
        assertTrue(input.publish(2, current))

        assertFalse(input.publish(1, InputProcessingState.Completed))
        assertFalse(input.publish(1, InputProcessingState.Error("late transport error")))
        input.finish(1)

        assertEquals(current, input.state.value)
        assertTrue(input.publish(2, InputProcessingState.ProcessingToolResult("current tool")))
    }

    @Test
    fun cancellationAndPreparationRevokeTheOldStreamsStatus() {
        val input = ExecutionInputState()
        input.begin(1)
        input.set(InputProcessingState.Idle)
        assertFalse(input.publish(1, InputProcessingState.Receiving("late chunk")))
        assertEquals(InputProcessingState.Idle, input.state.value)

        val preparation = InputProcessingState.Processing("new turn")
        input.set(preparation)
        assertFalse(input.publish(1, InputProcessingState.Error("old failure")))
        assertEquals(preparation, input.state.value)
    }

    @Test
    fun backgroundExecutionCannotAlterForegroundStateAndCompletionStaysTerminal() {
        val input = ExecutionInputState()
        input.begin(1)
        val saving = InputProcessingState.Processing("saving response")
        input.publish(1, saving)

        // Background/subtask executions are deliberately not registered as UI owners.
        assertFalse(input.publish(2, InputProcessingState.Error("background failure")))
        assertEquals(saving, input.state.value)
        assertTrue(input.publish(1, InputProcessingState.Completed))
        input.finish(1)
        assertFalse(input.publish(1, InputProcessingState.Receiving("late callback")))
        assertEquals(InputProcessingState.Completed, input.state.value)
    }
}
