package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.data.model.ApiProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XaiProviderReasoningTest {
    private fun mapping(model: String) = ThinkingQualityMappingRegistry.resolve(
        ApiProviderType.XAI.name, model, ModelThinkingConfigDefaults.forProvider(ApiProviderType.XAI.name)
    )
    @Test
    fun defaultConfigUsesTheOfficialXaiEndpointAndModel() {
        assertEquals(
            "grok-4.6",
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/models",
            ModelListFetcher.getModelsListUrl(
                "https://api.x.ai/v1/chat/completions",
                ApiProviderType.XAI
            )
        )
    }

    @Test
    fun enabledOptionsMapToXaiEfforts() {
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            listOf("low", "medium", "high", "xhigh").map {
                mapping("grok-4.6").textValueFor(it)
            }
        )
    }

    @Test
    fun mapperPreservesTheSelectedEffort() {
        assertEquals(
            "high",
            mapping("grok-4.6").textValueFor("high")
        )
    }

    @Test
    fun reasoningEffortUsesTheGrokFamilyRule() {
        listOf("grok-4.6", "grok-4.5-latest", "grok-3-mini").forEach {
            assertEquals(ThinkingQualityControl.LEVELS, mapping(it).control)
            assertEquals("reasoning_effort", mapping(it).parameterLabel)
        }
    }
}
