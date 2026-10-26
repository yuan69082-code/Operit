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
    val names = CollectionTools.names + setOf("theme_studio")
    fun prompts() = CollectionTools.prompts() + listOf(ToolPrompt(
        name = "theme_studio",
        description = "修改当前助手原生界面主题。action=inspect_render|inspect|generate|preview|accept|cancel|list|save|restore。inspect读取真实主题字段；inspect_render读取当前已挂载界面的实际边界、颜色、形状和未挂载区域，先打开目标界面再检查；generate按family与hue生成并试穿；preview传patch JSON（strings/booleans/ints/floats字段映射），仅修改提供字段。先inspect再局部改色，颜色为ARGB有符号整数。generate和preview立即可见，用户可在助手空间的主题工坊保留或取消；仅用户明确要求时accept/cancel。save保存皮肤；restore按id试穿。不要声称支持CSS或未返回的字段。",
        parametersStructured = listOf(
            param("action", true), param("family"), param("hue"), param("seed"), param("patch"), param("name"), param("id"), param("color_count"), param("mood"), param("star_opacity"), param("star_size"), param("star_glow"), param("warmth")
        )))
    private fun param(name: String, required: Boolean = false) = ToolParameterSchema(name = name, type = "string", description = when(name) {
        "family" -> "雾玻璃|纸张|糖果|墨色|织物|暗夜"
        "hue" -> "0到359色相"
        "color_count" -> "1到6配色数量"
        "mood", "star_opacity", "warmth" -> "0到1氛围浓度/星光透明度/暖度"
        "star_size" -> "0到10星光大小"
        "star_glow" -> "0到30星光光晕"
        else -> name
    }, required = required)

    fun register(handler: AIToolHandler, context: Context) {
        CollectionTools.register(handler, context)
        handler.registerTool(name = "theme_studio", descriptionGenerator = { "主题工坊" }, executor = { tool ->
            try {
                fun value(name: String) = tool.parameters.firstOrNull { it.name == name }?.value
                val role = requireNotNull(value("__operit_package_caller_card_id")) { "缺少当前助手身份" }
                val studio = ThemeStudio(context)
                val result = runBlocking(Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                    val chatId = requireNotNull(value("__operit_package_chat_id")) { "缺少当前对话" }
                    val chat = requireNotNull(com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId))
                    val target: ActivePrompt = chat.characterGroupId?.takeIf { it.isNotBlank() }?.let {
                        ActivePrompt.CharacterGroup(it)
                    } ?: ActivePrompt.CharacterCard(role)
                    when (val action = value("action")) {
                        "inspect" -> ThemeStudio.encode(studio.current(target))
                        "inspect_render" -> kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { com.ai.assistance.operit.ui.theme.RenderInspection.inspect(com.ai.assistance.operit.ui.theme.RenderInspection.owner(target)) }
                        "list" -> studio.library(target)
                        "generate" -> studio.generatePreview(target, requireNotNull(value("family")), requireNotNull(value("hue")).toFloat(), value("seed")?.toInt() ?: 0, value("name") ?: "AI 主题",
                            value("color_count")?.toInt() ?: 3, value("mood")?.toFloat() ?: .5f, value("star_opacity")?.toFloat() ?: 0f,
                            value("star_size")?.toFloat() ?: 3f, value("star_glow")?.toFloat() ?: 8f, value("warmth")?.toFloat() ?: .5f)
                        "preview" -> studio.preview(target, JSONObject(requireNotNull(value("patch"))), value("name") ?: "局部调整")
                        "accept" -> studio.settle(target, true)
                        "cancel" -> studio.settle(target, false)
                        "save" -> studio.saveSkin(target, requireNotNull(value("name")))
                        "restore" -> studio.restore(target, requireNotNull(value("id")), false)
                        else -> error("未知操作 $action")
                    }
                }
                ToolResult(toolName = tool.name, success = true, result = StringResultData(result.toString()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ExperienceTools", "Theme tool failed", e)
                ToolResult(toolName = tool.name, success = false, result = StringResultData("主题操作失败"), error = e.message)
            }
        })
    }
}
