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
import org.junit.Assert.*
import org.junit.Test

class GeminiLevelCompatibilityTest {
    @Test fun supportedLevelsFollowModelCapabilitiesOnEveryNativeProvider() {
        for (provider in listOf("GOOGLE", "GEMINI_GENERIC", "VERTEX_AI")) {
            val rules = ModelThinkingConfigDefaults.forProvider(provider)
            for ((model, levels) in listOf(
                "gemini-3-pro-preview" to listOf("LOW", "HIGH"),
                "models/gemini-3-pro-image-preview" to listOf("LOW", "HIGH"),
                "gemini-3.1-pro-preview" to listOf("LOW", "MEDIUM", "HIGH"),
                "gemini-3.7-flash" to listOf("LOW", "MEDIUM", "HIGH"),
                "gemini-3.8-flash" to listOf("LOW", "MEDIUM", "HIGH"),
                "gemini-4-argon" to listOf("LOW", "MEDIUM", "HIGH"),
                "gemini-3.1-flash-image-preview" to listOf("MINIMAL", "HIGH"),
                "gemini-3-flash-preview" to listOf("MINIMAL", "LOW", "MEDIUM", "HIGH"),
            )) {
                val mapping = ThinkingQualityMappingRegistry.resolve(provider, model, rules)
                assertEquals("$provider/$model", levels, mapping.options.map { it.id })
                val body = JSONObject()
                applyGeminiThinkingConfiguration(body, provider, model, "https://example.test", rules, false, levels.first())
                assertEquals(levels.first(), body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
                assertFalse(body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getBoolean("includeThoughts"))
            }
        }
    }

    @Test fun publishedBuiltInsMigrateOnceAndPreserveValidOrCustomSelections() {
        for (provider in listOf(ApiProviderType.GOOGLE, ApiProviderType.GEMINI_GENERIC, ApiProviderType.VERTEX_AI)) {
            val legacy = ModelThinkingConfigDefaults.legacyGeminiRulesForProvider(provider.name)
            for ((model, input, expected, custom) in listOf(
                Case("gemini-3.8-flash", "MINIMAL", "LOW"),
                Case("gemini-3-pro-preview", "MINIMAL", "LOW"),
                Case("gemini-4-argon", "HIGH", "HIGH"),
                Case("gemini-3-flash-preview", "MINIMAL", "MINIMAL"),
                Case("gemini-3.8-flash", "MINIMAL", "MINIMAL", true),
            )) {
                val original = ModelConfigData(id = "test", name = "test", modelName = model, apiProviderType = provider,
                    thinkingConfigurations = if (custom) "[]" else legacy, thinkingOptionId = input)
                val key = stringPreferencesKey("config_test")
                val prefs = mutablePreferencesOf(ModelConfigManager.CONFIG_LIST_KEY to "[\"test\"]",
                    key to ModelConfigManager.json.encodeToString(original))
                ModelConfigManager.migratePreferencesFromVersionFive(prefs)
                val first = prefs[key]
                ModelConfigManager.migratePreferencesFromVersionFive(prefs)
                assertEquals(first, prefs[key])
                val actual = ModelConfigManager.json.decodeFromString<ModelConfigData>(first!!)
                assertEquals(original.copy(thinkingConfigurations = if (custom) "[]" else ModelThinkingConfigDefaults.forProvider(provider.name),
                    thinkingOptionId = expected), actual)
            }
        }
    }

    private data class Case(val model: String, val input: String, val expected: String, val custom: Boolean = false)
}
