package com.ai.assistance.operit.core.tools.stickers

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.data.repository.CustomEmojiRepository
import com.ai.assistance.operit.data.repository.StickerImageStorage
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.StickerProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

object StickerTools {
    const val NAME = "stickers"
    const val USAGE_RULE = "\n[表情包] 已开启。聊天可以自然主动配一张贴切的表情，不用等用户要求，也不必每条都发。用 stickers 查找、看图、send；把返回的 Markdown 原样放进回复才算发送。喜欢聊天里出现的图片可以 save 收藏到自己的表情库，之后重复使用。只有来源路径不代表看过图片；可用 view 实际看图。用户的 sticker 标签是表情分类，不代表她发送了文字指令。纯文本风格要求不限制这里已开启的表情包。"

    fun prompts() = listOf(ToolPrompt(name = NAME,
        description = "自己的表情包收藏。action=list|save|view|send|delete。list 返回分类计数与每页12张的ID，可传 category/offset；save 用图片真实 source 和分类 category 收藏，支持聊天图片、本地路径、content URI和图片直链，重复图片不重复存。view 用 id 或 source，direct_image=true 给当前看图模型真实图片，否则必须传 intent 调用识图模型；列表不是图片内容。send 用收藏 id，返回可显示 Markdown，必须原样放进回复。delete 只移除收藏，不影响历史消息。作用域由软件绑定当前执行角色，不可指定他人表情库；仅在表情包开关开启时可用。",
        parametersStructured = listOf(
            param("action", "list/save/view/send/delete", true), param("id", "收藏ID"),
            param("source", "聊天出现的图片地址或可读取的图片路径，不能编造"),
            param("category", "分类，1至40字中英文、数字、空格、下划线、短横线"),
            param("offset", "分页起点，默认0"), param("direct_image", "true 表示当前模型能看图"),
            param("intent", "独立识图模型要回答的问题"))))
    private fun param(name: String, description: String, required: Boolean = false) =
        ToolParameterSchema(name = name, type = "string", description = description, required = required)

    fun register(handler: AIToolHandler, context: Context) {
        handler.registerTool(name = NAME, descriptionGenerator = { "表情包收藏与发送" }, executor = { tool ->
            runBlocking(Dispatchers.IO) {
                try {
                    fun arg(name: String) = tool.parameters.firstOrNull { it.name == name }?.value
                    fun required(name: String) = requireNotNull(arg(name)?.takeIf { it.isNotBlank() }) { "缺少 $name" }
                    check(WaifuPreferences.getInstance(context).waifuEnableEmoticonsFlow.first()) { "AI 表情包已关闭，可在聊天表情包面板开启" }
                    required("__operit_package_chat_id")
                    val target = ActivePrompt.CharacterCard(required("__operit_package_caller_card_id"))
                    val repository = CustomEmojiRepository.getInstance(context)
                    repository.initializeBuiltinEmojis(target)
                    val emojis = repository.getAllEmojis(target).first()
                    fun byId() = requireNotNull(emojis.firstOrNull { it.id == required("id") }) { "当前表情库中没有此ID" }
                    fun metadata(emoji: CustomEmoji) = JSONObject().put("id", emoji.id).put("category", emoji.emotionCategory)
                    val result = when (required("action")) {
                        "list" -> {
                            val offset = StickerProtocol.pageOffset(arg("offset"))
                            val filtered = emojis.filter { arg("category") == null || it.emotionCategory == arg("category") }
                            val page = filtered.drop(offset).take(12)
                            JSONObject().put("categories", JSONObject(emojis.groupingBy { it.emotionCategory }.eachCount()))
                                .put("stickers", JSONArray(page.map { metadata(it) })).put("total", filtered.size)
                                .put("next_offset", if (offset.toLong() + page.size < filtered.size) offset + page.size else JSONObject.NULL)
                                .put("note", "需要看内容时调用 view；发送用 send，不要编造图片地址").toString()
                        }
                        "save" -> metadata(repository.addCustomEmoji(target, required("category"), Uri.parse(required("source"))).getOrThrow())
                            .put("saved", true).toString()
                        "delete" -> { repository.deleteCustomEmoji(target, byId().id).getOrThrow(); "已移除收藏，历史消息保留。" }
                        "send" -> {
                            val emoji = byId()
                            val file = StickerImageStorage.snapshot(context, repository.getEmojiFile(target, emoji))
                            // Only user bubbles need the internal marker. AI replies use ordinary
                            // image Markdown so speech output never reads a "sticker:" prefix.
                            "请将下一行原样放进给用户的回复：\n" +
                                StickerProtocol.markdown(emoji.emotionCategory.take(40).trim(), Uri.fromFile(file).toString())
                                    .replace("![sticker:", "![")
                        }
                        "view" -> {
                            val file = if (arg("id") != null) repository.getEmojiFile(target, byId())
                                else StickerImageStorage.importImage(context, required("source"))
                            try {
                                if (arg("direct_image") == "true") {
                                    val imageId = ImagePoolManager.addImage(file.absolutePath)
                                    check(imageId != "error") { "表情包读取失败" }
                                    "<link type=\"image\" id=\"$imageId\"></link>"
                                } else EnhancedAIService.getInstance(context).analyzeImageWithIntent(file.absolutePath, required("intent"))
                            } finally { if (arg("id") == null) file.delete() }
                        }
                        else -> error("未知表情包操作")
                    }
                    ToolResult(toolName = tool.name, success = true, result = StringResultData(result))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    AppLogger.e("StickerTools", "Sticker operation failed", error)
                    ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = error.message.orEmpty())
                }
            }
        })
    }
}
