package com.ai.assistance.operit.api.chat.llmprovider

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VertexThinkingSelectionTest {
    private val config = ModelConfigData(
        id = "vertex", name = "Vertex", apiProviderType = ApiProviderType.VERTEX_AI,
        modelName = "gemini-2.5-flash", apiEndpoint = "https://aiplatform.googleapis.com",
        thinkingConfigurations = ModelThinkingConfigDefaults.forProvider("VERTEX_AI"),
        thinkingOptionId = "1024"
    )

    @Test fun modelChangeInitializesLevelControlAndRequestCanBeBuilt() {
        val option = ModelConfigManager.nextThinkingOptionIdForProvider(
            config, "VERTEX_AI", "gemini-3-pro-preview", config.apiEndpoint)
        val request = JSONObject()
        ThinkingConfigurationApplier.apply(request, "VERTEX_AI", "gemini-3-pro-preview",
            config.apiEndpoint, config.thinkingConfigurations, true, option)
        assertEquals("LOW", request.getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test fun sameControlPreservesUserBudget() {
        assertEquals("8192", ModelConfigManager.nextThinkingOptionIdForProvider(
            config.copy(thinkingOptionId = "8192"), "VERTEX_AI", "gemini-2.5-pro", config.apiEndpoint))
    }

    @Test fun migrationRepairsPersistedBudgetButPreservesValidAndCustomChoices() {
        for ((input, expected) in listOf(
            config.copy(modelName = "gemini-3-pro-preview") to "LOW",
            config.copy(modelName = "gemini-3-pro-preview", thinkingOptionId = "HIGH") to "HIGH",
            config to "1024",
            config.copy(modelName = "gemini-3-pro-preview", thinkingConfigurations = "[]") to "1024"
        )) {
            val key = stringPreferencesKey("config_vertex")
            val prefs = mutablePreferencesOf(
                ModelConfigManager.CONFIG_LIST_KEY to ModelConfigManager.json.encodeToString(listOf("vertex")),
                key to ModelConfigManager.json.encodeToString(input))
            ModelConfigManager.migratePreferencesFromVersionFour(prefs)
            val actual = ModelConfigManager.json.decodeFromString<ModelConfigData>(prefs[key]!!)
            assertEquals(expected, actual.thinkingOptionId)
        }
    }
}
