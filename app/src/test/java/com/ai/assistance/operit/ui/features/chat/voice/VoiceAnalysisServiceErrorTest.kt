package com.ai.assistance.operit.ui.features.chat.voice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class VoiceAnalysisServiceErrorTest {
    @Test fun nullErrorIsNotFailure() {
        assertNull(VoiceAnalysisServiceError.describe(JSONObject().put("error", JSONObject.NULL), "key"))
        assertNull(VoiceAnalysisServiceError.describe(JSONObject(), "key"))
    }
    @Test fun realReasonIsPreserved() {
        val json = JSONObject().put("error", JSONObject().put("code", "InvalidParameter").put("message", "Audio format is invalid"))
        val result = VoiceAnalysisServiceError.describe(json, "key")!!
        assertTrue(result.contains("InvalidParameter"))
        assertTrue(result.contains("Audio format is invalid"))
    }
    @Test fun secretsAndPayloadAreRedacted() {
        val json = JSONObject().put("error", "bad secret-123 https://example.com/private data:audio/wav;base64,AAAA Bearer token456")
        val result = VoiceAnalysisServiceError.describe(json, "secret-123")!!
        listOf("secret-123", "example.com", "AAAA", "token456").forEach { assertFalse(result.contains(it)) }
    }
    @Test fun nonJsonErrorPageDoesNotThrow() {
        assertNull(VoiceAnalysisServiceError.fromBody("<html>bad gateway</html>", "key"))
        assertNotNull(VoiceAnalysisServiceError.describe(JSONObject().put("error", JSONObject()), "key"))
    }
}
