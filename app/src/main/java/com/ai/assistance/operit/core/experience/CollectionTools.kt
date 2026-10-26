package com.ai.assistance.operit.core.experience

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object CollectionTools {
    val names = setOf("collection_project")
    fun prompts() = listOf(ToolPrompt(name = "collection_project",
        description = "操作当前助手的收藏项目。list列出自己收藏的ID、标题与可调用状态；read读取卡片与文件；preview_state读取最近实际打开的预览页面文字、表单、local/session状态（未打开则为空，不可猜测渲染结果）；write_file按path/content写文件，必须附read返回的version防止覆盖并发编辑；run仅执行用户手动启用的tool.js，input为JSON对象，成功保存该卡片state。不得声称未打开的预览已验证。",
        parametersStructured = listOf("action", "id", "path", "content", "version", "input").map {
            ToolParameterSchema(name = it, type = "string", description = it, required = it == "action")
        }))
    fun register(handler: AIToolHandler, context: Context) {
        handler.registerTool(name = "collection_project", descriptionGenerator = { "收藏项目" }, executor = { tool ->
            try {
                fun p(key: String) = tool.parameters.firstOrNull { it.name == key }?.value
                val role = requireNotNull(p("__operit_package_caller_card_id"))
                val store = CollectionStore(context)
                val result = runBlocking(Dispatchers.IO + ToolExecutionManager.inheritedRuntimeContext()) {
                    val chatId = requireNotNull(p("__operit_package_chat_id"))
                    val chat = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId)
                    val owner = chat?.characterGroupId?.let { "group:$it" } ?: role
                    val action = requireNotNull(p("action"))
                    if (action == "list") JSONArray(store.list().filter { it.getString("role") == owner }.map {
                        JSONObject().put("id", it.getString("id")).put("title", it.getString("title"))
                            .put("toolEnabled", it.getBoolean("toolEnabled")).put("version", it.getLong("version"))
                    }) else {
                        val id = requireNotNull(p("id")); val item = store.read(id)
                        check(item.getString("role") == owner) { "不能读取其他助手的收藏" }
                        when (action) {
                            "read" -> item
                            "preview_state" -> item.getJSONObject("previewState")
                            "write_file" -> store.saveFile(id, requireNotNull(p("path")), requireNotNull(p("content")), requireNotNull(p("version")).toLong())
                            "run" -> store.execute(id, owner, JSONObject(p("input") ?: "{}"))
                            else -> error("未知操作")
                        }
                    }
                }
                ToolResult(toolName = tool.name, success = true, result = StringResultData(result.toString()))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ToolResult(toolName = tool.name, success = false, result = StringResultData("收藏项目操作失败"), error = e.message) }
        })
    }
}
