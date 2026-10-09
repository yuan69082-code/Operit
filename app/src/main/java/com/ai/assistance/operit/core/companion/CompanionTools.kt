package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

object CompanionTools {
    val names = setOf("companion_state", "companion_library", "companion_memory", "companion_capabilities")

    fun prompts(): List<ToolPrompt> = listOf(
        ToolPrompt(name = "companion_state",
            description = "读取绑定的 XC 状态或回传真实互动，情绪由 XC 保存。action=get|event。event 必须提供 event_id 和 exchange（用户实际说的话及你的实际回应）；重试复用 event_id，不与直接 xinchao_event 重复回传。get 会交接 XC 上下文及待领信号，普通对话软件已经自动读取，无需每轮再调。开关关闭时不调用 XC。",
            parametersStructured = listOf(param("action", "get 或 event", true), param("event_id", "真实互动的唯一ID，重试必须复用，最多120字"),
                param("exchange", "4–1500字真实对话片段，由XC判断互动类型，不虚构对话"),
                param("tone", "可选 neutral/calm/warm/guarded/conflicted/focused/playful/tired"))),
        ToolPrompt(name = "companion_library",
            description = "角色资料库。action=categories|category|list|search|get|save|delete。category 创建分类；save 保存知识、能力笔记或文件副本，可提供 id 更新或移动到另一分类；get/delete 必须提供 id。search 提供 query。list/search 每页20项，可传 offset。分类由你决定，也可按用户要求整理。文件保留在本机，不自动执行。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "资料ID"),
                param("title", "标题"), param("category", "自定义分类，可用/分级"), param("kind", "knowledge、skill 或 file"),
                param("content", "知识或能力笔记的正文"), param("source_path", "需复制保存的可读取本地文件路径或content URI"),
                param("query", "检索词"), param("offset", "分页起点，非负整数"))),
        ToolPrompt(name = "companion_memory",
            description = "控制本会话压缩。action=get|pin|unpin|draft。pin 用 id 命名并将 text 原样保留，合计最多16000字；unpin 取消同名片段；draft 保存你写的摘要草稿，最多8000字。软件在摘要前提供草稿，每轮独立注入保留片段。压缩历史仍在，不代表无限上下文。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "保留片段名称"), param("text", "须原样保留的文本或摘要草稿"))),
        ToolPrompt(name = "companion_capabilities",
            description = "读取本会话最近请求的模型/工具能力与当前软件开关、麦克风和通知权限。可感知能力变化，但不把列出的能力当作已执行操作；没有健康数据源时不能声明能看到心率。",
            parametersStructured = emptyList())
    )

    private fun param(name: String, description: String, required: Boolean = false) =
        ToolParameterSchema(name = name, type = "string", description = description, required = required)

    fun register(handler: AIToolHandler, context: Context) {
        names.forEach { name ->
            handler.registerTool(name = name, descriptionGenerator = { name }, executor = { tool ->
                try {
                    val chatId = required(tool, "__operit_package_chat_id")
                    val roleId = value(tool, "__operit_package_caller_card_id")
                    val store = CompanionStore(context)
                    val scope = store.scope(chatId, roleId)
                    val action = value(tool, "action")
                    val result = when (name) {
                        "companion_state" -> {
                            check(store.emotionEnabled) { "软件情绪延续已关闭。" }
                            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                when (action) {
                                    "event" -> XcEmotionBridge.event(context, chatId, roleId, required(tool, "event_id"), required(tool, "exchange"), value(tool, "tone"))
                                    "get" -> XcEmotionBridge.read(context, chatId, roleId)
                                    else -> error("action 须为 get 或 event")
                                }
                            }
                        }
                        "companion_memory" -> {
                            if (action != "get") store.setMemory(chatId, required(tool, "action"), value(tool, "id").orEmpty(), value(tool, "text").orEmpty())
                            store.read("summary:$chatId")
                        }
                        "companion_capabilities" -> {
                            check(store.capabilitiesEnabled) { "软件能力感知已关闭。" }
                            CompanionContext.permissions(context)
                                .put("emotion_continuity", store.emotionEnabled).put("proactive_contact", store.proactiveEnabled)
                                .put("call_silence_callback", store.silenceEnabled)
                                .put("last_request", store.read("capabilities:$chatId").optJSONObject("snapshot"))
                        }
                        else -> library(store, scope, tool, required(tool, "action"))
                    }
                    ToolResult(toolName = tool.name, success = true, result = StringResultData(result.toString()))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogger.e("CompanionTools", "Companion operation failed: $name", error)
                    ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = error.message.orEmpty())
                }
            })
        }
    }

    private fun library(store: CompanionStore, scope: String, tool: AITool, action: String): JSONObject = when (action) {
        "categories" -> JSONObject().put("categories", JSONArray(store.categories(scope)))
        "category" -> { store.createCategory(scope, required(tool, "category")); JSONObject().put("categories", JSONArray(store.categories(scope))) }
        "save" -> store.saveEntry(scope, value(tool, "id"), required(tool, "title"), required(tool, "category"),
            required(tool, "kind"), value(tool, "content").orEmpty(), value(tool, "source_path"))
        "get" -> store.entries(scope).firstOrNull { it.getString("id") == required(tool, "id") } ?: error("资料不存在。")
        "delete" -> { store.deleteEntry(scope, required(tool, "id")); JSONObject().put("deleted", true) }
        "list", "search" -> {
            val query = if (action == "search") required(tool, "query") else ""
            val category = value(tool, "category")
            val offset = value(tool, "offset")?.let { it.toIntOrNull() ?: error("offset 须为非负整数") } ?: 0
            require(offset >= 0) { "offset 须为非负整数" }
            val found = store.entries(scope).filter {
                (category == null || it.getString("category") == category) &&
                    (query.isBlank() || listOf("title", "content", "category").any { field -> it.optString(field).contains(query, ignoreCase = true) })
            }.sortedByDescending { it.getLong("updated") }
            val page = found.drop(offset).take(20).map {
                JSONObject().put("id", it.getString("id")).put("title", it.getString("title"))
                    .put("category", it.getString("category")).put("kind", it.getString("kind"))
                    .put("preview", it.optString("content").take(180)).put("has_file", it.optString("file").isNotBlank())
            }
            JSONObject().put("total", found.size).put("offset", offset).put("entries", JSONArray(page))
        }
        else -> error("未知资料库操作")
    }

    private fun value(tool: AITool, name: String): String? = tool.parameters.firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }
    private fun required(tool: AITool, name: String): String = value(tool, name) ?: error("缺少参数 $name")
}
