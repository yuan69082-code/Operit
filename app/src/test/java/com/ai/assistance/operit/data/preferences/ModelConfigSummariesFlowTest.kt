package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelConfigSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ModelConfigSummariesFlowTest {

    @Test
    fun `summary AI review defaults on and persists independently of dialogue excerpts`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"id":"summary-review-test","name":"test","enableSummaryDialogueReview":false}"""
        val config = json.decodeFromString<ModelConfigData>(legacy)
        assertEquals(true, config.enableSummaryAiReview)
        val dataStore = TestPreferencesDataStore(MutableStateFlow<Preferences>(mutablePreferencesOf(
            stringPreferencesKey("config_${config.id}") to legacy)))
        val context = Mockito.mock(Context::class.java)
        ModelConfigManager(context, dataStore).updateSummaryAiReviewSettings(config.id, false)

        val reloaded = ModelConfigManager(context, dataStore).getModelConfigFlow(config.id).first()
        assertEquals(false, reloaded.enableSummaryAiReview)
        assertEquals(false, reloaded.enableSummaryDialogueReview)
        ModelConfigManager(context, dataStore).updateSummaryAiReviewSettings(config.id, true)
        assertEquals(true, ModelConfigManager(context, dataStore).getModelConfigFlow(config.id).first().enableSummaryAiReview)
    }

    @Test
    fun `active config summaries subscription receives created and updated models`() = runBlocking {
        val preferences = MutableStateFlow<Preferences>(mutablePreferencesOf())
        val dataStore = TestPreferencesDataStore(preferences)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val firstConfigId = "first-config"
        val thinkingConfigurations = """[{"id":"think-1","label":"Low"}]"""
        val firstConfig =
            ModelConfigData(
                id = firstConfigId,
                name = "first",
                modelName = "model-a",
                apiProviderType = ApiProviderType.OPENAI_GENERIC,
                thinkingConfigurations = thinkingConfigurations,
                thinkingOptionId = "think-1",
            )
        dataStore.updateData { current ->
            current.toMutablePreferences().apply {
                set(stringPreferencesKey("config_$firstConfigId"), json.encodeToString(firstConfig))
                set(ModelConfigManager.CONFIG_LIST_KEY, json.encodeToString(listOf(firstConfigId)))
            }
        }

        val context = Mockito.mock(Context::class.java)
        val manager = ModelConfigManager(context, dataStore)
        val emissions = Channel<List<ModelConfigSummary>>(Channel.UNLIMITED)
        val collector = launch {
            manager.configSummariesFlow.take(3).collect { emissions.send(it) }
        }

        val initialSummaries = emissions.receive()
        assertEquals(listOf(firstConfigId), initialSummaries.map { it.id })
        assertEquals("model-a", initialSummaries.single().modelName)
        assertEquals(thinkingConfigurations, initialSummaries.single().thinkingConfigurations)
        assertEquals("think-1", initialSummaries.single().thinkingOptionId)

        val secondConfigId = manager.createConfig("second")
        val afterCreateSummaries = emissions.receive()
        assertEquals(listOf(firstConfigId, secondConfigId), afterCreateSummaries.map { it.id })

        manager.updateConfigBase(firstConfigId, "renamed")
        val afterUpdateSummaries = emissions.receive()
        assertEquals("renamed", afterUpdateSummaries.first().name)
        assertEquals("second", afterUpdateSummaries.last().name)
        assertEquals(thinkingConfigurations, afterUpdateSummaries.first().thinkingConfigurations)
        assertEquals("think-1", afterUpdateSummaries.first().thinkingOptionId)
        collector.join()
    }

    private class TestPreferencesDataStore(
        private val preferences: MutableStateFlow<Preferences>
    ) : DataStore<Preferences> {
        override val data = preferences.asStateFlow()

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(preferences.value)
            preferences.value = updated
            return updated
        }
    }
}
