package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XcEmotionEnvelopeTest {
    private fun envelope() = JSONObject().put("system", "xinchao-dynamic-mind")
        .put("delivered", true).put("additionalContext", "当前状态：平静")

    @Test fun contextIsTakenFromStructuredEnvelopeWithoutRewritingIt() {
        val payload = envelope().put("generatedAt", "2026-10-09T00:00:00Z")
        val parsed = XcEmotionBridge.decodeSnapshot(JSONObject().put("structuredContent", payload))
        assertSame(payload, parsed)
        assertEquals("当前状态：平静", parsed.getString("additionalContext"))
    }

    @Test fun missingOrUndeliveredContextCannotBeTreatedAsCurrentEmotion() {
        assertThrows(IllegalStateException::class.java) { XcEmotionBridge.decodeSnapshot(JSONObject()) }
        listOf(envelope().put("delivered", false), envelope().put("additionalContext", ""),
            envelope().put("system", "another-system")).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) {
                XcEmotionBridge.decodeSnapshot(JSONObject().put("structuredContent", invalid))
            }
        }
    }

    @Test fun excessiveEnvelopeIsRejectedBeforePersistence() {
        assertThrows(IllegalStateException::class.java) {
            XcEmotionBridge.decodeSnapshot(JSONObject().put("structuredContent",
                envelope().put("additionalContext", "x".repeat(100001))))
        }
    }
}
