package com.ai.assistance.operit.core.experience

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object ExperienceTools {
    val names = setOf("theme_studio")
    fun prompts() = listOf(ToolPrompt(
        name = "theme_studio",
        description = "修改当前助手原生界面主题。action=inspect|generate|preview|accept|cancel|list|save|restore。inspect读取真实主题字段；generate按family与hue生成并试穿；preview传patch JSON（strings/booleans/ints/floats字段映射），仅修改提供字段。先inspect再局部改色，颜色为ARGB有符号整数。generate和preview立即可见，用户可在助手空间的主题工坊保留或取消；仅用户明确要求时accept/cancel。save保存皮肤；restore按id试穿。不要声称支持CSS或未返回的字段。",
        parametersStructured = listOf(
            param("action", true), param("family"), param("hue"), param("seed"), param("patch"), param("name"), param("id")
        )))
    private fun param(name: String, required: Boolean = false) = ToolParameterSchema(name = name, type = "string", description = when(name) {
        "family" -> "雾玻璃|纸张|糖果|墨色|织物|暗夜"
        "hue" -> "0到359色相"
        else -> name
    }, required = required)

    fun register(handler: AIToolHandler, context: Context) {
        handler.registerTool(name = "theme_studio", descriptionGenerator = { "主题工坊" }, executor = { tool ->
            try {
                fun value(name: String) = tool.parameters.firstOrNull { it.name == name }?.value
                val role = requireNotNull(value("__operit_package_caller_card_id")) { "缺少当前助手身份" }
                val studio = ThemeStudio(context)
                val result = runBlocking(Dispatchers.IO) {
                    val chatId = requireNotNull(value("__operit_package_chat_id")) { "缺少当前对话" }
                    val chat = requireNotNull(com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId))
                    val target: ActivePrompt = chat.characterGroupId?.takeIf { it.isNotBlank() }?.let {
                        ActivePrompt.CharacterGroup(it)
                    } ?: ActivePrompt.CharacterCard(role)
                    when (val action = value("action")) {
                        "inspect" -> ThemeStudio.encode(studio.current(target))
                        "list" -> studio.library(target)
                        "generate" -> studio.preview(target, ThemeStudio.generate(requireNotNull(value("family")), requireNotNull(value("hue")).toFloat(), value("seed")?.toInt() ?: 0), value("name") ?: "AI 主题")
                        "preview" -> studio.preview(target, JSONObject(requireNotNull(value("patch"))), value("name") ?: "局部调整")
                        "accept" -> studio.settle(target, true)
                        "cancel" -> studio.settle(target, false)
                        "save" -> studio.saveSkin(target, requireNotNull(value("name")))
                        "restore" -> studio.restore(target, requireNotNull(value("id")), false)
                        else -> error("未知操作 $action")
                    }
                }
                ToolResult(toolName = tool.name, success = true, result = StringResultData(result.toString()))
            } catch (e: Exception) {
                AppLogger.e("ExperienceTools", "Theme tool failed", e)
                ToolResult(toolName = tool.name, success = false, result = StringResultData("主题操作失败"), error = e.message)
            }
        })
    }
}
