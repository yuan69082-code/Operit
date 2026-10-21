package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.data.preferences.observedSharedPreferences

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object GroupTools {
    suspend fun ensureEnabledOnce(context: Context, actor: String) {
        val prefs = context.observedSharedPreferences("vio_group_capability_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean(actor, false)) return
        val cards = CharacterCardManager.getInstance(context)
        listOf("group_chat", "group_game", "assistant_relationship").forEach { cards.setToolSelected(actor, "builtin", it, true) }
        check(prefs.edit().putBoolean(actor, true).commit()) { "群聊能力初始化失败" }
    }
    private fun param(name: String, description: String, required: Boolean = false) =
        ToolParameterSchema(name = name, type = "string", description = description, required = required)
    fun prompts() = listOf(
        ToolPrompt(name = "group_chat", description = "群聊与连续记忆。events 搜索自己的全部私聊事件（query/from/end/status，chat_id可限定窗口），每页10件。history 用 after=from/end=to 分页查看事件原文，返回预览和has_more；history_message 用 message_at/part/offset 续读完整已存内容，直到complete=true；feelings 按窗口和时间范围续读自己保存的感受。不能读取别人的私聊。status 查看本群成员与讨论状态；所有成员自主决定接话，无需点名。respond 仅表达邀请，不限制其他人；quiet 表示本轮无新内容，不必重复上句话；end 表示自己聊完并给 reason；schedule 自定下一次在本群醒来的分钟数和目的；cancel_schedule 取消自己的唤醒。groups 列出你已加入的群；whisper 在群里用 text 向自己的活跃私聊单独联系用户，不公开私聊内容；send 可从私聊向已有群发送公开消息，指定 chat_id 和 text，targets 可选。无需用户先在群内开口。mute 仅主助手可用，targets 可为多人，all=true 为全员。tasks 返回群待办简表；get_task 用 task_id 读取详情，offset 为历史分页起点；save_task 创建或更新共同计划及自己想做的事。comment_task 用 task_id/text 写署名批注；submit_task 用 task_id/assignee/minutes 自主安排接手助手继续，不必等用户审批；只有确需用户决定时填 needs_decision。wait_user 表示自己等待用户，不停止其他成员，可保留本轮给用户的正文。quiet 会丢弃本轮全部公开正文，禁止把‘我只需要等待’当聊天发出。每条公开新消息都让其他成员自主决定潜水或冒泡；quiet、wait_user、end只管自己，不能替别人闭麦。不要复述来源标签。", parametersStructured = listOf(
            param("action", "windows/events/history/history_message/feelings/groups/send/whisper/status/respond/quiet/wait_user/end/schedule/cancel_schedule/mute/tasks/get_task/save_task/comment_task/submit_task", true),
            param("chat_id", "已有目标群会话ID，私聊向群发送时必填；history 可指自己的私聊"), param("before", "history 下一页游标"),
            param("after", "history 正序读：从这个时间戳开始往后读20条，用段落索引里的 from；继续读传返回的 after"),
            param("end", "history/events/feelings 的结束时间戳，包含该条；事件索引的 to"),
            param("from", "events/feelings 的起始时间戳；事件索引的 from"),
            param("query", "events 事件关键词，空格分隔多个条件；省略 chat_id 检索自己的所有私聊窗口。windows 按窗口标题查找，包括尚无摘要的窗口"),
            param("message_at", "history_message 必填，消息的 at 时间戳，也可用事件的 summary_at 读取窗口摘要"),
            param("part", "history_message: body正文/raw完整已存内容/tools工具参数和结果/attachments附件标签；群聊只允许body。附件本体按标签路径调用对应文件或媒体工具读取"),
            param("targets", "respond或send可选的邀请助手ID JSON数组，不是发言许可"), param("reason", "安静或结束的原因"),
            param("minutes", "schedule 为1–43200分钟；submit_task 可为0，表示尽快"), param("purpose", "下次活动目的"),
            param("text", "send公开消息或whisper私聊正文，仅发送主动选择的这段文字"),
            param("all", "mute 是否全员，true/false"), param("muted", "mute 禁言true，解除false"),
            param("task_id", "save_task 修改时填写已有ID，新建省略"), param("title", "待办标题"),
            param("assignee", "接手助手ID、user 或空"), param("status", "events用悬着/已结束；任务用open/checking/waiting_evidence/ready/doing/review/waiting_effect/paused/done/archived"),
            param("completed", "已完成的工作"), param("next", "接下来要做什么"),
            param("details", "save_task JSON对象：priority=P0..P3、detail、origin(原话原文带时间)、goal(做完要什么效果)、conclusion(现在进展)、completed、blocker(卡在哪)、next、plan(后面规划)、materials(每行一条：本地路径/记忆ID/链接+说明)、needs_decision、category、batch、evidence、linked_ids数组、expected_version"),
            param("offset", "events每页10项，windows/tasks每页20项；history_message和feelings按字符分页，传next_offset"))),
        ToolPrompt(name = "group_game", description = "群内游戏。status 查看公开局面；自己的手牌/身份/私密结果由软件在下一轮的私有上下文注入；act 提交合法行动；start 建立狼人杀(werewolf)、斗地主(landlord)、情景(story)或自定义(custom)游戏，须有用户开始游戏的意图。template 保存自制小游戏规则；end 结束本局。游戏身份只对本局有效，不要写成日常身份或把普通聊天说成角色扮演。不要向其他玩家披露私密状态。", parametersStructured = listOf(
            param("action", "status/act/start/template/end", true), param("kind", "werewolf/landlord/story/custom"),
            param("move", "JSON对象。斗地主 bid:0..3 或 cards:[牌ID] 或 pass:true；狼人杀 target:玩家ID 或 skip:true；剧情 text:公开行动, hidden:私密行动"),
            param("title", "自制游戏名称"), param("rules", "自制游戏或情景的规则和目标")))
    )
    fun register(handler: AIToolHandler, context: Context) {
        listOf("group_chat", "group_game").forEach { name -> handler.registerTool(name = name, descriptionGenerator = { name }, executor = { tool ->
            runBlocking(Dispatchers.IO + ToolExecutionManager.inheritedRuntimeContext()) {
                try {
                    fun arg(key: String) = tool.parameters.firstOrNull { it.name == key }?.value?.trim()
                    val actor = requireNotNull(AssistantScope.callerId()) { "需要正在执行的助手身份" }
                    val chat = requireNotNull(arg("__operit_package_chat_id")) { "需要会话上下文" }
                    val action = requireNotNull(arg("action"))
                    val store = GroupStore(context)
                    val result: Any
                    if (name == "group_chat" && action == "history") {
                        result = GroupContext.history(context, actor, arg("chat_id") ?: chat, arg("before")?.toLong(), arg("after")?.toLong(), arg("end")?.toLong())
                    } else if (name == "group_chat" && action == "history_message") {
                        result = GroupContext.historyMessage(context, actor, arg("chat_id") ?: chat,
                            arg("message_at")?.toLong() ?: error("缺少 message_at"), arg("offset")?.toInt() ?: 0, arg("part") ?: "body")
                    } else if (name == "group_chat" && action == "events") {
                        result = com.ai.assistance.operit.core.chat.WindowEventLookup.search(context, actor,
                            arg("chat_id"), arg("query").orEmpty(), arg("from")?.toLong(), arg("end")?.toLong(), arg("status"), arg("offset")?.toInt() ?: 0)
                    } else if (name == "group_chat" && action == "windows") {
                        result = com.ai.assistance.operit.core.chat.WindowEventLookup.windows(context, actor, arg("query").orEmpty(), arg("offset")?.toInt() ?: 0)
                    } else if (name == "group_chat" && action == "feelings") {
                        result = com.ai.assistance.operit.core.chat.WindowEventLookup.feelings(context, actor,
                            arg("chat_id") ?: chat, arg("from")?.toLong() ?: error("缺少 from"),
                            arg("end")?.toLong() ?: error("缺少 end"), arg("offset")?.toInt() ?: 0)
                    } else if (name == "group_chat" && action == "groups") {
                        result = GroupDelivery.groups(context, actor)
                    } else if (name == "group_chat" && action == "whisper") {
                        result = GroupDelivery.whisper(context, actor, chat, arg("text") ?: error("缺少私聊 text"))
                    } else if (name == "group_chat" && action == "send") {
                        result = GroupDelivery.enqueue(context, actor, arg("chat_id") ?: error("缺少目标 chat_id"),
                            arg("text") ?: error("缺少公开 text"), store.strings(arg("targets")?.let { JSONArray(it) }))
                    } else {
                        val targetChat = arg("chat_id") ?: chat
                        val metadata = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(targetChat))
                        val group = requireNotNull(metadata.characterGroupId?.let { CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(it) }) { "此操作只能在群内进行" }
                        val ids = group.members.map { it.characterCardId }
                        val primary = CharacterCardManager.getInstance(context).isPrimaryAssistant(actor)
                        check(actor in ids || (primary && name == "group_chat" && action == "mute")) { "你不在本群内" }
                        check(targetChat == chat || name == "group_chat" && action in setOf("status", "tasks", "get_task", "save_task", "comment_task", "submit_task", "mute")) { "此控制动作只对当前群回合有效" }
                        if (name == "group_game") {
                            val games = GroupGames(context)
                            result = when (action) {
                                "status" -> games.view(chat, actor, privateView = false)
                                "start" -> games.start(chat, arg("kind") ?: error("缺少 kind"), listOf("user") + ids, arg("rules").orEmpty())
                                    .let { games.view(chat, actor, privateView = false) }
                                "act" -> games.act(chat, actor, JSONObject(arg("move") ?: error("缺少 move")))
                                "template" -> games.template(arg("title") ?: error("缺少 title"), arg("rules") ?: error("缺少 rules"))
                                "end" -> { games.end(chat); JSONObject().put("ended", true) }
                                else -> error("未知游戏操作")
                            }
                        } else {
                            result = when (action) {
                                "status" -> JSONObject().put("members", JSONArray(group.members.map {
                                    JSONObject().put("id", it.characterCardId).put("name", CharacterCardManager.getInstance(context).getCharacterCard(it.characterCardId).name)
                                })).put("continuous", true).put("participation", "autonomous").put("purpose", store.read(targetChat).optString("purpose"))
                                    .put("mute_all", store.read(targetChat).optBoolean("mute_all"))
                                    .put("muted", store.read(targetChat).optJSONArray("muted") ?: JSONArray())
                                "mute" -> {
                                    val mute = arg("muted")?.toBooleanStrictOrNull() ?: error("muted 须为 true/false")
                                    GroupModeration(context).change(targetChat, actor, ids.toSet(),
                                        store.strings(arg("targets")?.let { JSONArray(it) }), arg("all") == "true", mute)
                                    JSONObject().put("updated", true)
                                }
                                "tasks" -> {
                                    val rows = com.ai.assistance.operit.core.tasks.TaskBoard(context, targetChat, true).list()
                                    val offset = arg("offset")?.toInt() ?: 0
                                    require(offset >= 0)
                                    JSONObject().put("total", rows.size).put("tasks", JSONArray(rows.drop(offset).take(20).map { com.ai.assistance.operit.core.tasks.TaskRecords.brief(it) }))
                                        .put("next_offset", if (offset + 20 < rows.size) offset + 20 else JSONObject.NULL)
                                }
                                "get_task" -> com.ai.assistance.operit.core.tasks.TaskBoard(context, targetChat, true)
                                    .get(arg("task_id") ?: error("缺少 task_id"), arg("offset")?.toInt() ?: 0)
                                "comment_task" -> {
                                    com.ai.assistance.operit.core.tasks.TaskBoard(context, targetChat, true).comment(arg("task_id") ?: error("缺少 task_id"), actor, arg("text") ?: error("缺少 text"))
                                    JSONObject().put("saved", true)
                                }
                                "submit_task" -> {
                                    com.ai.assistance.operit.core.tasks.TaskBoard(context, targetChat, true).submit(arg("task_id") ?: error("缺少 task_id"), actor,
                                        arg("assignee") ?: actor, ids.toSet(), arg("minutes")?.toInt() ?: 0)
                                    JSONObject().put("queued", true)
                                }
                                "save_task" -> {
                                    val patch = arg("details")?.let { JSONObject(it) } ?: JSONObject()
                                    listOf("title", "assignee", "status", "completed", "next").forEach { key -> arg(key)?.let { patch.put(key, it) } }
                                    com.ai.assistance.operit.core.tasks.TaskRecords.brief(com.ai.assistance.operit.core.tasks.TaskBoard(context, targetChat, true).save(actor, arg("task_id"), patch, ids.toSet()))
                                }
                                "respond", "quiet", "wait_user", "end" -> {
                                    val decision = JSONObject().put("actor", actor).put("action", action).put("reason", arg("reason").orEmpty().take(500))
                                    if (action == "respond") {
                                        val targets = store.strings(arg("targets")?.let { JSONArray(it) })
                                        require(targets.all { it in ids && it != actor }) { "接话对象必须是本群其他成员" }
                                        decision.put("targets", JSONArray(targets))
                                    }
                                    store.update(chat) { it.put("decision", decision) }
                                    store.log(chat, "决定", "$action：${arg("reason").orEmpty()}", actor)
                                    JSONObject().put("recorded", action)
                                }
                                "schedule" -> { store.schedule(chat, actor, arg("minutes")?.toInt() ?: error("缺少 minutes"), arg("purpose") ?: error("缺少 purpose")); JSONObject().put("scheduled", true) }
                                "cancel_schedule" -> { store.cancelWake(chat, actor); JSONObject().put("paused", true) }
                                else -> error("未知群聊操作")
                            }
                        }
                    }
                    ToolResult(toolName = name, success = true, result = StringResultData(result.toString()))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    AppLogger.e("GroupTools", "Group tool failed", error)
                    ToolResult(toolName = name, success = false, result = StringResultData(""), error = error.message.orEmpty())
                }
            }
        }) }
    }
}
