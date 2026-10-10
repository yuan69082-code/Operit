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
    val names = setOf("companion_state", "companion_library", "companion_todo", "companion_schedule", "companion_capabilities")

    fun prompts(): List<ToolPrompt> = listOf(
        ToolPrompt(name = "companion_state",
            description = "XC 情绪由服务端保存。action=get|event。真实互动或完成学习后主动 event，一次事件只记一次；用 interaction_type 明确真实结果，exchange 按“她说：原话 他回：实际回应”填写，自主活动写实际行动且不编造她的话。仅 tone 不等于互动类型。重试复用 event_id，不与 xinchao_event 重复回传。get 读取已同步快照；普通对话已经自动同步。",
            parametersStructured = listOf(param("action", "get 或 event", true), param("event_id", "真实互动的唯一ID，重试必须复用，最多120字"),
                param("exchange", "4–1500字真实对话片段，由XC判断互动类型，不虚构对话"),
                param("interaction_type", "真实完成结果：companionship/affection/intimacy/sharing/discovery/task_progress/reflection/conflict/loss/reconciliation/slighted/empathy/helped/intrigued；不确定时省略交由XC判断"),
                param("tone", "可选 neutral/calm/warm/guarded/conflicted/focused/playful/tired"))),
        ToolPrompt(name = "companion_library",
            description = "角色资料库。action=categories|category|list|search|get|save|delete。category 创建分类；save 保存知识、能力笔记或文件副本，可提供 id 更新或移动到另一分类；get/delete 必须提供 id。search 提供 query。list/search 每页20项，可传 offset。分类由你决定，也可按用户要求整理。文件保留在本机，不自动执行。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "资料ID"),
                param("title", "标题"), param("category", "自定义分类，可用/分级"), param("kind", "knowledge、skill 或 file"),
                param("content", "知识或能力笔记的正文"), param("source_path", "需复制保存的可读取本地文件路径或content URI"),
                param("query", "检索词"), param("offset", "分页起点，非负整数"))),
        ToolPrompt(name = "companion_todo",
            description = "角色待办。action=list|save|delete|distill。save 新建或用 id 更新，判断紧急性、时间线与完成状态；distill 将已完成事项的实际收获 content 存到资料库并移除待办。不要把计划当作已完成。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "待办ID"), param("title", "标题"),
                param("content", "待办说明或实际沉淀内容"), param("urgency", "urgent 或 normal"),
                param("horizon", "short 或 long"), param("status", "open 或 done"))),
        ToolPrompt(name = "companion_schedule",
            description = "安排本会话下次主动唤醒。action=get|set|cancel。set 用 minutes 指定1至10080分钟后醒来，purpose 写下醒来准备做什么；可以联系、学习或保持安静。只在软件主动联系开启且本会话为目标时生效；工具权限与正常聊天相同。醒来后重新安排下一次，不必固定间隔轮询。",
            parametersStructured = listOf(param("action", "操作", true), param("minutes", "下次唤醒距现在的分钟数"), param("purpose", "醒来要检查或做的事，最多500字"))),
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
                                    "event" -> XcEmotionBridge.event(context, chatId, roleId, required(tool, "event_id"), required(tool, "exchange"), value(tool, "tone"), value(tool, "interaction_type"))
                                    "get" -> XcEmotionBridge.cached(context, chatId, roleId)
                                    else -> error("action 须为 get 或 event")
                                }
                            }
                        }
                        "companion_todo" -> {
                            when (action) {
                                "list" -> Unit
                                "save" -> store.saveTodo(scope, value(tool, "id"), required(tool, "title"), value(tool, "content").orEmpty(),
                                    required(tool, "urgency"), required(tool, "horizon"), required(tool, "status"))
                                "delete", "distill" -> store.finishTodo(scope, required(tool, "id"), action, value(tool, "content").orEmpty())
                                else -> error("未知待办操作")
                            }
                            JSONObject().put("todos", JSONArray(store.todos(scope)))
                        }
                        "companion_schedule" -> {
                            check(store.proactiveEnabled && store.preferences.getString("target_chat", null) == chatId) { "请开启主动联系并选择本会话。" }
                            when (action) {
                                "get" -> Unit
                                "set" -> {
                                    val minutes = required(tool, "minutes").toIntOrNull() ?: error("minutes 须为整数")
                                    val purpose = required(tool, "purpose")
                                    require(purpose.length <= 500) { "目的最多500字。" }
                                    store.update("schedule:$chatId") { it.put("at", CompanionPolicy.wakeAt(System.currentTimeMillis(), minutes))
                                        .put("purpose", purpose).put("owner", "ai").put("paused", false) }
                                    store.log(chatId, "安排", "${minutes}分钟后：$purpose")
                                }
                                "cancel" -> {
                                    store.update("schedule:$chatId") { it.put("paused", true) }
                                    store.log(chatId, "安排", "AI 暂停了下一次唤醒")
                                }
                                else -> error("未知唤醒操作")
                            }
                            store.read("schedule:$chatId")
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
