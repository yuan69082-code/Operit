package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.data.model.ModelParameter

/** Full window is independent of the subject-material quota; never add that quota a second time. */
internal object SubjectRequestWindow {
    data class Check(val inputEstimate: Long, val outputReserve: Long?, val window: Int) {
        val totalEstimate: Long? get() = outputReserve?.let { Math.addExact(inputEstimate, it) }
        val exceeds: Boolean get() = window > 0 && (totalEstimate ?: inputEstimate) > window
    }
    fun check(input: Long, parameters: List<ModelParameter<*>>, window: Int): Check {
        val output = parameters.filter { it.isEnabled && it.apiName in setOf(
            "max_tokens", "max_output_tokens", "max_completion_tokens", "maxOutputTokens", "max_new_tokens", "max_tokens_to_sample"
        ) }.mapNotNull { (it.currentValue as? Number)?.toLong()?.takeIf { n -> n > 0 } }.maxOrNull()
        return Check(input, output, window)
    }
}
