package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.InputProcessingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A service may still be unwinding an old stream when the next turn starts. */
internal class ExecutionInputState {
    private val mutableState = MutableStateFlow<InputProcessingState>(InputProcessingState.Idle)
    val state = mutableState.asStateFlow()
    private var owner: Int? = null

    @Synchronized
    fun begin(executionId: Int) {
        owner = executionId
    }

    @Synchronized
    fun publish(executionId: Int, state: InputProcessingState): Boolean {
        // An old completion/error must not replace the new turn's receiving/tool state.
        if (owner != executionId) return false
        mutableState.value = state
        return true
    }

    @Synchronized
    fun finish(executionId: Int) {
        if (owner == executionId) owner = null
    }

    @Synchronized
    fun set(state: InputProcessingState) {
        // Explicit caller transitions (cancel or preparation) revoke the previous stream.
        owner = null
        mutableState.value = state
    }
}
