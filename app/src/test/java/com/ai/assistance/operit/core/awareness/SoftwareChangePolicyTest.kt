package com.ai.assistance.operit.core.awareness

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SoftwareChangePolicyTest {
    @Test fun modelChangesExposeNamesButNeverCredentials() {
        val model = JSONObject().put("name", "新模型").put("modelName", "model-x")
            .put("apiKey", "PRIVATE_KEY_123").put("apiEndpoint", "https://private.example/token")
        val row = SoftwareChangePolicy.changes("model_configs", emptyMap(), mapOf("config_x" to model.toString())).single()
        assertEquals("model", row.kind)
        assertTrue(row.summary.contains("新模型"))
        assertEquals("model-x", row.detail.getJSONObject("after").getString("model"))
        assertFalse(row.detail.toString().contains("PRIVATE_KEY_123"))
        assertFalse(row.detail.toString().contains("private.example"))
    }
    @Test fun deletedModelRetainsItsPublicIdentity() {
        val row = SoftwareChangePolicy.changes("model_configs", mapOf("config_x" to "{\"name\":\"old\"}"), emptyMap()).single()
        assertTrue(row.summary.contains("删除"))
        assertEquals("old", row.detail.getJSONObject("before").getString("name"))
        assertTrue(row.detail.isNull("after"))
    }
    @Test fun rotatingApiKeyDoesNotPretendUserEditedModel() {
        val old = "{\"name\":\"m\",\"currentKeyIndex\":0}"
        val new = "{\"name\":\"m\",\"currentKeyIndex\":1}"
        assertTrue(SoftwareChangePolicy.changes("model_configs", mapOf("config_x" to old), mapOf("config_x" to new)).isEmpty())
    }
    @Test fun ordinaryTextAndNumericSecretsAreRedacted() {
        val row = SoftwareChangePolicy.changes("api_settings", emptyMap(),
            mapOf("prompt" to "PRIVATE_PROMPT", "token" to 12345, "enabled" to true)).single()
        assertFalse(row.detail.toString().contains("PRIVATE_PROMPT"))
        assertFalse(row.detail.toString().contains("12345"))
        assertTrue(row.detail.toString().contains("true"))
    }
    @Test fun noopsUsageCountersAndObserverBookkeepingCannotRecur() {
        val value = mapOf("a" to 4)
        assertTrue(SoftwareChangePolicy.changes("api_settings", value, value).isEmpty())
        assertTrue(SoftwareChangePolicy.changes("token_stats_preferences", emptyMap(), value).isEmpty())
        assertTrue(SoftwareChangePolicy.changes("companion_options", emptyMap(), mapOf("software_revision" to 9)).isEmpty())
        assertTrue(SoftwareChangePolicy.changes("companion_options", emptyMap(), mapOf(
            "assistant_window:a" to "new-private", "assistant_windows:a" to "[new-private]", "assistant_routing_v1" to true)).isEmpty())
        assertFalse(SoftwareChangePolicy.mayWake("web_session_history"))
    }
    @Test fun membershipIsAttributedToTheNewAssistant() {
        val row = SoftwareChangePolicy.changes("character_cards", mapOf("character_card_list" to setOf("a")),
            mapOf("character_card_list" to setOf("a", "b"), "character_card_b_name" to "B")).single()
        assertEquals("b", row.detail.getString("assistant_id"))
        assertEquals("created", row.detail.getString("action"))
    }
}
