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
        ToolPrompt(name = "group_chat", description = "群聊与连续记忆。history 分页读取自己的私聊或参加过的群原文，不能读取别人的私聊。status 查看本群成员与讨论状态；respond 提议其他助手接话（targets 为助手ID的JSON数组，只在持续讨论开启时生效）；quiet 表示本轮无新内容，不必重复上句话；end 在目的达成时结束讨论并给 reason；schedule 自定下一次在本群醒来的分钟数和目的；cancel_schedule 取消自己的唤醒。groups 列出你已加入的群；send 可从私聊向已有群发送公开消息，指定 chat_id 和 text，targets 可选。无需用户先在群内开口。mute 仅主助手可用，targets 可为多人，all=true 为全员。tasks 返回群待办简表；get_task 用 task_id 读取详情，offset 为历史分页起点；save_task 创建或更新共同计划及自己想做的事。comment_task 用 task_id/text 写署名批注；submit_task 用 task_id/assignee/minutes 自主安排接手助手继续，不必等用户审批；只有确需用户决定时填 needs_decision。wait_user 把话交给用户并停止排队，可保留本轮给用户的正文。quiet 会丢弃本轮全部公开正文，禁止把‘我只需要等待’当聊天发出。respond 必须明确接话对象；没有接话指令就不会自动轮转。不要复述来源标签。", parametersStructured = listOf(
            param("action", "history/groups/send/status/respond/quiet/wait_user/end/schedule/cancel_schedule/mute/tasks/get_task/save_task/comment_task/submit_task", true),
            param("chat_id", "已有目标群会话ID，私聊向群发送时必填；history 可指自己的私聊"), param("before", "history 下一页游标"),
            param("targets", "respond 的助手ID JSON数组"), param("reason", "安静或结束的原因"),
            param("minutes", "schedule 为1–43200分钟；submit_task 可为0，表示尽快"), param("purpose", "下次活动目的"),
            param("text", "send 的公开消息，仅发送这段文字，不转发私聊历史"),
            param("all", "mute 是否全员，true/false"), param("muted", "mute 禁言true，解除false"),
            param("task_id", "save_task 修改时填写已有ID，新建省略"), param("title", "待办标题"),
            param("assignee", "接手助手ID、user 或空"), param("status", "open/checking/waiting_evidence/ready/doing/review/waiting_effect/paused/done/archived"),
            param("completed", "已完成的工作"), param("next", "接下来要做什么"),
            param("details", "save_task JSON对象：priority=P0..P3、detail、conclusion、needs_decision、category、batch、evidence、linked_ids数组、expected_version"),
            param("offset", "tasks 分页起点，每页20项"))),
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
                        result = GroupContext.history(context, actor, arg("chat_id") ?: chat, arg("before")?.toLong())
                    } else if (name == "group_chat" && action == "groups") {
                        result = GroupDelivery.groups(context, actor)
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
                                })).put("continuous", store.read(targetChat).optBoolean("continuous")).put("purpose", store.read(targetChat).optString("purpose"))
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
                                        val targets = store.strings(JSONArray(arg("targets") ?: error("缺少 targets")))
                                        require(targets.isNotEmpty() && targets.all { it in ids && it != actor }) { "接话对象必须是本群其他成员" }
                                        check(store.read(chat).optBoolean("continuous")) { "本群尚未开启持续讨论" }
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
