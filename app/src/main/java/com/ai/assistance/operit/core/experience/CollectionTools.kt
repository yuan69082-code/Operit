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
        description = "操作当前助手的收藏项目。list列出自己收藏的ID、标题与可调用状态；read读取卡片与文件；preview_state读取最近实际打开的预览页面文字、表单、local/session状态和render中的实际computed style/边界/脚本错误（未打开则为空，不可猜测渲染结果）；write_file按path/content写文件，必须附read返回的version防止覆盖并发编辑；run仅执行用户手动启用的tool.js，input为JSON对象，成功保存该卡片state。workspace_context读取当前会话所绑定工作区对应的收藏项目及参考目录；references/search_references按query列出/检索参考资料；read_reference按reference_id读完整快照；add_reference引用同一助手的source_id收藏，可选path引用源文件；promote_reference按reference_id/path将资料转为新项目文件；remove_reference移除引用。以上写操作均需version。export_workspace导出全新本机工作区，pull_workspace回收修改，收藏有并发修改时拒绝覆盖。引用是带来源的快照，不会因原收藏删除而丢失。不得声称未打开的预览已验证。",
        parametersStructured = listOf("action", "id", "path", "content", "version", "input", "query", "source_id", "reference_id").map {
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
                    if (action == "workspace_context") {
                        JSONArray(store.list().filter { it.getString("role") == owner && chat?.workspace != null && it.optJSONObject("workspace")?.optString("path") == chat.workspace }.map { item ->
                            JSONObject().put("id", item.getString("id")).put("title", item.getString("title")).put("version", item.getLong("version"))
                                .put("workspace", item.getJSONObject("workspace").getString("path"))
                                .put("references", JSONArray((0 until store.references(item.getString("id")).length()).map { i ->
                                    val ref = store.references(item.getString("id")).getJSONObject(i)
                                    JSONObject().put("id", ref.getString("id")).put("title", ref.getString("title")).put("characters", ref.getString("content").length)
                                }))
                        })
                    } else if (action == "list") JSONArray(store.list().filter { it.getString("role") == owner }.map {
                        JSONObject().put("id", it.getString("id")).put("title", it.getString("title"))
                            .put("toolEnabled", it.getBoolean("toolEnabled")).put("version", it.getLong("version"))
                    }) else {
                        val id = requireNotNull(p("id")); val item = store.read(id)
                        check(item.getString("role") == owner) { "不能读取其他助手的收藏" }
                        when (action) {
                            "read" -> item
                            "preview_state" -> item.getJSONObject("previewState")
                            "references", "search_references" -> {
                                val rows = store.references(id, p("query") ?: "")
                                JSONArray((0 until rows.length()).map { i ->
                                    val ref = rows.getJSONObject(i)
                                    JSONObject(ref.toString()).put("content", ref.getString("content").take(500))
                                        .put("characters", ref.getString("content").length)
                                })
                            }
                            "read_reference" -> {
                                val rows = store.references(id)
                                (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == p("reference_id") }
                            }
                            "add_reference" -> store.addReference(id, requireNotNull(p("source_id")), p("path"), requireNotNull(p("version")).toLong())
                            "remove_reference" -> store.removeReference(id, requireNotNull(p("reference_id")), requireNotNull(p("version")).toLong())
                            "promote_reference" -> store.promoteReference(id, requireNotNull(p("reference_id")), requireNotNull(p("path")), requireNotNull(p("version")).toLong())
                            "export_workspace" -> store.exportWorkspace(id, requireNotNull(p("version")).toLong())
                            "pull_workspace" -> store.pullWorkspace(id, requireNotNull(p("version")).toLong())
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
