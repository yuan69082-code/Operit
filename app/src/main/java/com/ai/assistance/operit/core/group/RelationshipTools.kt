package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.AssistantScope
import kotlinx.coroutines.*
import org.json.JSONArray

object RelationshipTools {
    fun prompts() = listOf(ToolPrompt(name = "assistant_relationship", description = "维护独立助手关系网。list 查看涉及自己的关系（主助手可看全部）；save 完整保存自己对另一位助手的关系、看法、进展、共同经历；delete 删除自己的记录。主助手可修改任意记录。from和to是稳定助手ID；普通助手只能以自己为from，不能替对方声称同意。阅读他人的看法/经历不等于自己亲历；没有证据不要编造共同经历。", parametersStructured = listOf(
        p("action", "list/save/delete", true), p("from", "观点所属助手ID，默认你自己"), p("to", "另一位助手ID"),
        p("relation", "关系名称"), p("opinion", "from对to的看法"), p("progress", "目前进展"), p("experiences", "共同经历，保留参与者/时间/来源；本局游戏须明确标为游戏"))))
    private fun p(name: String, description: String, required: Boolean = false) = ToolParameterSchema(name = name, type = "string", description = description, required = required)
    fun register(handler: AIToolHandler, context: Context) {
        handler.registerTool(name = "assistant_relationship", descriptionGenerator = { "关系网" }, executor = { tool ->
            runBlocking(Dispatchers.IO + ToolExecutionManager.inheritedRuntimeContext()) {
                try {
                    fun arg(key: String) = tool.parameters.firstOrNull { it.name == key }?.value?.trim()
                    val actor = requireNotNull(AssistantScope.callerId()) { "缺少执行助手身份" }
                    val network = RelationshipNetwork(context)
                    val from = arg("from") ?: actor
                    val result: Any = when (arg("action")) {
                        "list" -> JSONArray(network.list(actor))
                        "save" -> network.save(actor, from, requireNotNull(arg("to")), requireNotNull(arg("relation")), arg("opinion").orEmpty(), arg("progress").orEmpty(), arg("experiences").orEmpty(), arg("__operit_package_chat_id").orEmpty())
                        "delete" -> { network.delete(actor, from, requireNotNull(arg("to"))); "deleted" }
                        else -> error("未知关系网操作")
                    }
                    ToolResult(toolName = tool.name, success = true, result = StringResultData(result.toString()))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = failure.message) }
            }
        })
    }
}
