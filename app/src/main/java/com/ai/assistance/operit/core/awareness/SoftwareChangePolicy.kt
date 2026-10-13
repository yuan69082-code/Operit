package com.ai.assistance.operit.core.awareness

import org.json.JSONArray
import org.json.JSONObject

/** Only public labels and scalar settings leave the preference transaction. Never retain raw JSON. */
object SoftwareChangePolicy {
    data class Change(val kind: String, val summary: String, val detail: JSONObject)

    fun changes(source: String, before: Map<String, Any?>, after: Map<String, Any?>): List<Change> {
        val keys = (before.keys + after.keys).filter { before[it] != after[it] && !ignored(source, it) }.sorted()
        if (keys.isEmpty()) return emptyList()
        if (source == "model_configs") {
            return keys.filter { it.startsWith("config_") && it != "config_list" }.mapNotNull { key ->
                val old = (before[key] as? String)?.let { JSONObject(it) }
                val new = (after[key] as? String)?.let { JSONObject(it) }
                val action = when { old == null -> "新增模型配置"; new == null -> "删除模型配置"; else -> "修改模型配置" }
                val name = (new ?: checkNotNull(old)).optString("name").take(100)
                val fields = ((old?.keys()?.asSequence()?.toSet().orEmpty()) + (new?.keys()?.asSequence()?.toSet().orEmpty()))
                    .filter { it != "currentKeyIndex" && old?.opt(it)?.toString() != new?.opt(it)?.toString() }.sorted()
                if (old != null && new != null && fields.isEmpty()) return@mapNotNull null
                Change("model", "$action：$name", JSONObject().put("config_id", key.removePrefix("config_"))
                    .put("before", modelLabel(old)).put("after", modelLabel(new))
                    .put("changed_fields", JSONArray(fields)).put("secret_values", "omitted"))
            }
        }
        if (source == "character_cards" && "character_card_list" in keys) {
            val old = (before["character_card_list"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            val new = (after["character_card_list"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            val membership = (new - old).map { id ->
                Change("assistant", "新增助手：" + after["character_card_${id}_name"].toString().take(100),
                    JSONObject().put("assistant_id", id).put("action", "created"))
            } + (old - new).map { id ->
                Change("assistant", "删除助手：" + before["character_card_${id}_name"].toString().take(100),
                    JSONObject().put("assistant_id", id).put("action", "deleted"))
            }
            if (membership.isNotEmpty()) return membership
        }
        val fields = keys.chunked(40).map { chunk ->
            Change(if (source == "character_cards") "assistant" else "settings", "设置已变更：${label(source)}",
                JSONObject().put("section", source).put("fields", JSONArray(chunk.map { key ->
                    JSONObject().put("field", key.take(180))
                        .put("before", scalar(key, before[key])).put("after", scalar(key, after[key]))
                })).put("total_changed_fields", keys.size))
        }
        return fields
    }

    private fun modelLabel(value: JSONObject?): Any = if (value == null) JSONObject.NULL else JSONObject()
        .put("name", value.optString("name").take(100))
        .put("model", value.optString("modelName").take(160))
        .put("provider", value.optString("apiProviderType").take(80))

    // Strings, sets and structured values can contain tokens, prompts, addresses or private text.
    private fun scalar(key: String, value: Any?): Any = if (
        Regex("(?i)secret|password|token|api.?key|credential|authorization|cookie").containsMatchIn(key)
    ) "[内容不记录]" else when (value) {
        null -> JSONObject.NULL
        is Boolean -> value
        is Number -> value
        else -> "[内容不记录]"
    }

    private fun ignored(source: String, key: String): Boolean =
        key.startsWith("__operit_") || source in setOf("codex_usage_preferences", "token_stats_preferences", "crash_recovery_state", "current_chat_id", "free_usage_preferences", "persona_card_chat_history") ||
            source == "companion_options" && (key.startsWith("software_") || key == "last_status") ||
            key.contains("last_used", true) || key.contains("scroll_position", true)

    fun label(source: String): String = when (source) {
        "character_cards" -> "助手设定与能力"
        "character_groups" -> "群聊设置"
        "model_configs" -> "模型"
        "functional_configs" -> "功能模型分配"
        "api_settings" -> "聊天与工具"
        "user_preferences", "display_preferences" -> "界面与用户设置"
        "companion_options" -> "状态延续与主动活动"
        "mcp_configuration" -> "MCP配置"
        else -> source.take(100)
    }

    // Runtime caches can be logged without launching a fresh model turn after every write.
    fun mayWake(source: String): Boolean = source in setOf(
        "model_configs", "character_cards", "character_groups", "functional_configs", "api_settings",
        "user_preferences", "display_preferences", "companion_options", "android_permission_preferences",
        "speech_services_preferences", "speech_service_profiles", "wake_word_preferences", "prompt_tags",
        "custom_emoji_settings", "external_http_api_preferences", "env_preferences", "mcp_configuration"
    )
}
