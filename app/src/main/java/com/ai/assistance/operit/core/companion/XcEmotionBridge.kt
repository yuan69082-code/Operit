package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** Read-only decoder for historical XC receipts. No connection, binding, synchronization or injection. */
object XcEmotionBridge {
    internal fun decodeSnapshot(response: JSONObject): JSONObject {
        val snapshot = response.optJSONObject("structuredContent") ?: error("XC 状态接口缺少结构化内容。")
        check(snapshot.optString("system") == "xinchao-dynamic-mind" && snapshot.optBoolean("delivered") &&
            snapshot.optString("additionalContext").isNotBlank()) { "XC 未交付有效的状态上下文。" }
        check(snapshot.toString().length <= 100_000) { "XC 状态返回过大。" }
        return snapshot
    }
}

