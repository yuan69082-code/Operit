package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object RoomTools {
    val names = setOf("companion_room", "companion_diary", "companion_reading")
    private fun p(name: String, description: String, required: Boolean = false) = ToolParameterSchema(name, "string", description, required)
    fun prompts() = listOf(
        ToolPrompt(name = "companion_room", description = "自己的房间。action=get|decorate|aliases|alias。decorate 需要用户开启布置权限；只能改自己且遵守锁定。get 返回当前外观、组件、锁定和可调整字段。alias 用 alias/name 保存明确的称呼对应关系，仅用于自己的记忆检索，不推测同名者是同一人。不能开启权限或替用户解锁。",
            parametersStructured = listOf(p("action", "操作", true), p("details", "decorate 的JSON对象：title/subtitle/cover/widgets/dates/theme；先get读取可用格式"), p("alias", "已确认的别名"), p("name", "对应完整称呼"))),
        ToolPrompt(name = "companion_diary", description = "自己的日记。action=list|get|save；list 可按 date=YYYY-MM-DD 筛选；get 用id，可加version查看旧稿。save 需用户开启自主日记，使用date/title/text；更新还须id和expected_version。按真实记录写下经历和想法，也可以不写；转述保留他人姓名与来源，不能编造未发生的事情。用户编辑有独立署名，原稿保留。",
            parametersStructured = listOf(p("action", "操作", true), p("id", "日记ID"), p("date", "日期"), p("title", "标题"), p("text", "正文，最多12000字"), p("expected_version", "更新前读取到的版本"), p("version", "查看某个旧版本"), p("offset", "列表分页起点，每页10条"))),
        ToolPrompt(name = "companion_reading", description = "自己的阅读角。action=sources|save_source|refresh|checked|queue|history|add|get|fetch|note|skip。普通新内容去重入队，不等于已读或必须联系用户。sources 标记到期来源；RSS用refresh，topic用已授权搜索工具探索后checked。save_source用details设置title/kind=rss或topic/address/hours=1..168/enabled，修改附id；公开搜索词不含私聊原文。add用title/url/text保存来源摘要；get用id并可指定part=summary或body；fetch取网页可读正文，保留部分正文标记；note用id/text写已读材料的随记，和来源分开。自主探索未允许上网时不可联网。",
            parametersStructured = listOf(p("action", "操作", true), p("id", "条目或来源ID"), p("details", "来源设置JSON"), p("title", "标题"), p("url", "原始来源链接"), p("text", "来源摘要或自己的随记"), p("part", "summary/body"), p("offset", "分页起点，每页10项")))
    )
    fun register(handler: AIToolHandler, context: Context) {
        names.forEach { name -> handler.registerTool(name, descriptionGenerator = { name }, executor = { tool ->
            try {
                val result = runBlocking(Dispatchers.IO + ToolExecutionManager.inheritedRuntimeContext()) {
                    fun arg(key: String) = tool.parameters.firstOrNull { it.name == key }?.value?.takeIf { it.isNotBlank() }
                    fun required(key: String) = arg(key) ?: error("缺少 $key")
                    val actor = required("__operit_package_caller_card_id")
                    val chat = required("__operit_package_chat_id")
                    ExplorationStore(context).authorize(actor, chat)
                    val room = AssistantRoom(context, actor)
                    val action = required("action")
                    val offset = arg("offset")?.toInt() ?: 0
                    require(offset >= 0)
                    when (name) {
                        "companion_room" -> when (action) {
                            "get" -> room.read().put("widgets", RoomPolicy.layout(room.read())).put("theme_values", RoomTheme.encode(com.ai.assistance.operit.data.preferences.UserPreferencesManager.getInstance(context).resolveThemePreferenceSnapshot(characterCardId = actor).values))
                                .put("editable", "title/subtitle/cover；widgets数组各项id,visible,size(1..3)，须包含diary,todo,reading,collection,dates各一次；dates数组各项title,date(YYYY-MM-DD)；theme可含bubble_ai_bubble_color/bubble_user_bubble_color/bubble_ai_text_color/bubble_user_text_color(#RRGGBB)，font_scale(0.8..1.5)，bubble_ai_system_font_name/bubble_user_system_font_name(default/serif/sans-serif/monospace/cursive)，background_image_uri/use_background_image/background_image_opacity(0..1)，bubble_ai_rounded_corners_enabled/bubble_rounded_corners_enabled")
                            "decorate" -> room.change(JSONObject(required("details")), true)
                            "aliases" -> room.aliases()
                            "alias" -> { room.saveAlias(required("alias"), required("name")); room.aliases() }
                            else -> error("未知房间操作")
                        }
                        "companion_diary" -> when (action) {
                            "list" -> {
                                val entries = room.diaries().filter { arg("date") == null || it.getString("date") == arg("date") }
                                page("entries", entries, offset)
                            }
                            "get" -> {
                                val current = room.diary(required("id"))
                                val version = arg("version")?.toLong()
                                if (version == null || version == current.getLong("version")) current
                                else {
                                    require(version in 1 until current.getLong("version"))
                                    CompanionStore(context).read("diary:$actor:${required("id")}:v$version").also { check(it.has("id")) { "旧稿不存在" } }
                                }
                            }
                            "save" -> room.saveDiary(arg("id"), required("date"), required("title"), required("text"), chat, true, arg("expected_version")?.toLong())
                            else -> error("未知日记操作")
                        }
                        else -> {
                            val reading = ReadingRoom(context, actor)
                            when (action) {
                                "sources" -> page("sources", reading.sources().map { JSONObject(it.toString()).put("due", reading.due().any { due -> due.getString("id") == it.getString("id") }) }, offset)
                                "save_source" -> {
                                    val data = JSONObject(required("details"))
                                    reading.saveSource(arg("id"), data.getString("title"), data.getString("kind"), data.getString("address"), data.getInt("hours"), data.getBoolean("enabled"))
                                }
                                "refresh" -> reading.refresh(required("id"), true, chat)
                                "checked" -> { reading.checked(required("id")); JSONObject().put("recorded", true) }
                                "queue", "history" -> page("entries", reading.entries(action == "history"), offset)
                                "add" -> reading.add(required("title"), required("url"), required("text"))
                                "get" -> reading.open(required("id"), arg("part") ?: "summary", true)
                                "fetch" -> reading.fetch(required("id"), true, chat)
                                "note" -> reading.note(required("id"), required("text"), true)
                                "skip" -> { reading.skip(required("id")); JSONObject().put("skipped", true) }
                                else -> error("未知阅读操作")
                            }
                        }
                    }
                }
                ToolResult(toolName = name, success = true, result = StringResultData(result.toString()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLogger.e("RoomTools", "Room operation failed: $name", failure)
                ToolResult(toolName = name, success = false, result = StringResultData(""), error = failure.message.orEmpty())
            }
        }) }
    }
    private fun page(key: String, data: List<JSONObject>, offset: Int) = JSONObject().put("total", data.size)
        .put(key, JSONArray(data.drop(offset).take(10))).put("next_offset", if (offset + 10 < data.size) offset + 10 else JSONObject.NULL)
}
