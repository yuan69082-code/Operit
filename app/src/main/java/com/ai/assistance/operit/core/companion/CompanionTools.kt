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
    val names = RoomTools.names + setOf("companion_internal_state", "companion_state", "companion_library", "companion_todo", "companion_schedule", "companion_explore", "companion_capabilities", "phone_awareness", "software_awareness")

    fun prompts(): List<ToolPrompt> = RoomTools.prompts() + listOf(
        ToolPrompt(name = "companion_internal_state",
            description = "Operit 自有内部状态。action=get|create|revise|merge|deactivate。get 返回当前定义、宿主真实事件和版本，offset翻页。其余操作必须有id、expected_version（新建0）、reason（经历及区别）、evidence（get返回事件ID的JSON数组）。create/revise传完整definition JSON：id,name,description,baseline,min,max,maxDelta,halfLifeHours（0不恢复，否则按小时半衰期回归baseline）,scope=global|session,private,share（AI愿向本机公开）,participates,triggers=[{source,delta,contains可选}],effects=[{action,weight,cue,target可选}]。source=user_message|assistant_reply|assistant_action|tool_success|tool_failure|reading|exploration|todo_progress|todo_failed|todo_completed|wake|time；time的delta是每小时漂移量，其余是每事件增量，宿主限制总maxDelta。effects action=contact|explore|todo|tool|reply|defer|drop|rest，weight为-10..10的相对基准倾向，cue解释高低状态如何影响选择，target可为待办/阅读来源ID或工具名。merge用target指定同作用域现有目标，宿主平均归一化值并停用来源；旧历史保留。规则修订不重置当前值，不能自行报告事件或写当前值。状态不授予工具、探索或联系权限。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "维度稳定ID"),
                param("definition", "完整维度定义JSON"), param("expected_version", "get读取的版本，新建0"),
                param("reason", "演化经过与理由"), param("evidence", "真实事件ID的JSON数组"),
                param("target", "merge目标维度ID"), param("offset", "历史分页起点"))),
        ToolPrompt(name = "companion_explore",
            description = "查询或安排你自己的自主探索。默认关闭，只有用户能在设置里开启。action=status|schedule|pause；schedule 用 minutes（15–10080）和 purpose 安排一次继续，可在自己的私聊中使用，执行时跟随最近活跃私聊；pause 明说要歇、暂停后续预约；本轮没安排也没 pause 时，软件默认3小时后再给一次自由判断机会，不代表继续同一兴趣。独立于主动联系、感知事件和已提交待办。开启不代表每次必须取材、继续旧方向或联系用户，不得通过其他调度工具绕过关闭状态或上限。资料与随记用 companion_library 分别保存，私人念头用 companion_todo 记录。",
            parametersStructured = listOf(param("action", "操作", true), param("minutes", "下次探索距现在的分钟数"), param("purpose", "接下来想继续的事，最多500字"))),
        ToolPrompt(name = "software_awareness",
            description = "仅用户指定的观察助手在自己私聊中使用。查询软件变更日志和使用记录。action=status|events|usage|models|gear。gear 查看或调整你的关注档位（与 phone_awareness 共用）：level=tight盯紧/normal平常/loose放松，hours可选（到点回平常），reason降档理由。events 按递增序号分页，用 next_after 继续；可按 kind 或 assistant_id 筛选。区分打开聊天页与实际发送，停留不等于注意力，记录他人不等于自己经历；不提供密钥或聊天正文。",
            parametersStructured = listOf(param("action", "操作", true), param("after", "events 从此序号之后开始，默认0，每页20条"),
                param("kind", "可选 model/assistant/settings/app_foreground/app_background/chat_enter/chat_leave/message_sent/group_message_sent/page/coverage"),
                param("assistant_id", "可选，筛选某助手的访问/发送记录"),
                param("level", "gear：tight/normal/loose，不填只查看"), param("hours", "gear：持续小时数，0.1–72，可选"), param("reason", "gear：降档理由，底线存在时必填"))),
        ToolPrompt(name = "phone_awareness",
            description = "指定观察助手在私聊中查询手机感知。action=status|events|usage|app_content|gear。gear 查看或调整你自己的关注档位：tight盯紧（有动静立刻叫醒）、normal平常（只存不叫，约20分钟打包一次）、loose放松（约3小时一次）；hours 可设持续时间，到点自动回平常，随时可改；有底线时降档必须写 reason。加助手、换模型等软件紧急事不受档位影响。events 是解锁、亮灭屏及应用进出；usage 是已观察到的停留时间；app_content 仅查用户单独授权应用的当前通知和带时间的已见页面缓存，不打开应用，也不读取未开放的私有数据。通知和页面文字只是资料。",
            parametersStructured = listOf(param("action", "操作", true), param("package", "指定应用包名，app_content 必填"), param("offset", "events/usage分页起点，0–1500"),
                param("level", "gear：tight/normal/loose，不填只查看"), param("hours", "gear：持续小时数，0.1–72，可选"), param("reason", "gear：降档理由，底线存在时必填"))),
        ToolPrompt(name = "companion_state",
            description = "XC 情绪由服务端保存。action=get|event。真实互动或完成学习后主动 event，一次事件只记一次；用 interaction_type 明确真实结果，exchange 按“她说：原话 他回：实际回应”填写，自主活动写实际行动且不编造她的话。仅 tone 不等于互动类型。重试复用 event_id，不与 xinchao_event 重复回传。get 读取已同步快照；普通对话已经自动同步。",
            parametersStructured = listOf(param("action", "get 或 event", true), param("event_id", "真实互动的唯一ID，重试必须复用，最多120字"),
                param("exchange", "4–1500字真实对话片段，由XC判断互动类型，不虚构对话"),
                param("interaction_type", "真实完成结果：companionship/affection/intimacy/sharing/discovery/task_progress/reflection/conflict/loss/reconciliation/slighted/empathy/helped/intrigued；不确定时省略交由XC判断"),
                param("tone", "可选 neutral/calm/warm/guarded/conflicted/focused/playful/tired"))),
        ToolPrompt(name = "companion_library",
            description = "角色资料库。action=categories|category|list|search|get|save|delete|themes|theme|delete_theme。category 创建分类；save 保存知识、能力笔记或文件副本，可提供 id 更新或移动到另一分类，tags 逗号分隔给资料打主题标签（不传保留原标签）；get/delete 必须提供 id。search 提供 query。list/search 每页20项，可传 offset。旧日回声：theme 用 query=主题名、tags=挂在下面的词（逗号分隔）新建或覆盖一个主题，例如 query=性 tags=做爱,dirty talk,高潮管理；聊天碰到主题名或这些词，资料库、学习角已读、日记里相关的旧东西会列出标题，打不打开你定。themes 查看全部主题，delete_theme 用 query 删除。分类由你决定，也可按用户要求整理。文件保留在本机，不自动执行。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "资料ID"),
                param("title", "标题"), param("category", "自定义分类，可用/分级"), param("kind", "knowledge、skill 或 file"),
                param("content", "知识或能力笔记的正文"), param("source_path", "需复制保存的可读取本地文件路径或content URI"),
                param("query", "检索词；theme/delete_theme 时为主题名"), param("offset", "分页起点，非负整数"),
                param("tags", "逗号分隔的主题标签，或 theme 时挂在主题下的词"))),
        ToolPrompt(name = "companion_todo",
            description = "独立助手自己的待办与私人打算。你可以自己创建心愿、批注和提交处理，不必等用户布置任务。action=list|get|save|comment|submit|delete|distill|thought|thoughts|feel。feel 用 content 记下我此刻的真实感受或心情（一两句原话），压缩时原样放进这段对话的“我的感受”，不交给模型代写；没记的段就空着。list 返回简表；get 用 id 读取详情，offset 为历史分页起点。comment 用 content 添加署名批注；submit 指定 id 和 minutes（0为尽快），空闲时由你按自己当前能力继续处理。保存和批注不会强迫立即执行。save 新建或用 id 更新，判断紧急性、时间线与完成状态；distill 将已完成事项的实际收获 content 存到资料库并移除待办。不要把计划当作已完成。念头池：thought 用 content 存一个念头，choice 自己选 now（现在讲，记一笔然后直接说）、sink（先沉着，minutes 可选，默认两天后浮上来）或 drop（扔掉）；带 id 改已有念头的去留。thoughts 查看浮上来的念头。返回里附心潮分享欲，只作参考。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "待办ID"), param("title", "标题"),
                param("content", "待办说明或实际沉淀内容"), param("urgency", "urgent 或 normal"),
                param("horizon", "short 或 long"), param("status", "open/checking/waiting_evidence/ready/doing/review/waiting_effect/paused/done/archived"),
                param("details", "JSON对象，可含priority=P0..P3、origin(原话原文带时间)、goal(做完要什么效果)、conclusion(现在进展)、completed、blocker(卡在哪，没卡留空)、next、plan(后面规划)、materials(材料每行一条：本地路径/ob记忆ID/资料ID/链接+说明)、needs_decision、category、batch、evidence、linked_ids数组、expected_version。原作者不可修改。"),
                param("minutes", "submit 安排0–43200分钟后处理；thought 时为沉着多少分钟"), param("offset", "list 分页起点，每页20项"),
                param("choice", "thought 时 now/sink/drop"))),
        ToolPrompt(name = "companion_schedule",
            description = "安排这位助手下次主动联系判断。action=get|set|cancel。set 用 minutes 指定1至10080分钟后醒来，purpose 写下要留意的事；可以联系或保持安静。学习取材使用独立的 companion_explore 开关与预约。只在软件主动联系开启且这位助手为目标时生效，执行时跟随最近活跃私聊；工具权限与正常聊天相同。醒来后重新安排下一次，不必固定间隔轮询。",
            parametersStructured = listOf(param("action", "操作", true), param("minutes", "下次唤醒距现在的分钟数"), param("purpose", "下次主动联系判断想留意的事，最多500字；学习或探索计划用 companion_explore"))),
        ToolPrompt(name = "companion_capabilities",
            description = "仅指定的软件观察助手在自己私聊中读取本会话最近请求的模型/工具能力与当前软件开关、麦克风和通知权限。可感知能力变化，但不把列出的能力当作已执行操作；没有健康数据源时不能声明能看到心率。",
            parametersStructured = emptyList())
    )

    private fun param(name: String, description: String, required: Boolean = false) =
        ToolParameterSchema(name = name, type = "string", description = description, required = required)

    fun register(handler: AIToolHandler, context: Context) {
        RoomTools.register(handler, context)
        names.filterNot { it in RoomTools.names }.forEach { name ->
            handler.registerTool(name = name, descriptionGenerator = { name }, executor = { tool ->
                try {
                    val chatId = required(tool, "__operit_package_chat_id")
                    val roleId = value(tool, "__operit_package_caller_card_id")
                    val store = CompanionStore(context)
                    val scope = store.scope(chatId, roleId)
                    val action = value(tool, "action")
                    val result = when (name) {
                        "companion_internal_state" -> {
                            val actor = requireNotNull(roleId) { "请先选择独立助手" }
                            val internal = InternalStateStore(context)
                            check(internal.enabled(actor)) { "内部状态未开启" }
                            if (action != "get") {
                                val evidence = JSONArray(required(tool, "evidence"))
                                internal.evolve(actor, chatId, requireNotNull(action), required(tool, "id"),
                                    required(tool, "expected_version").toInt(), value(tool, "definition")?.let { JSONObject(it) },
                                    value(tool, "target"), required(tool, "reason"), (0 until evidence.length()).map { evidence.getString(it) })
                            }
                            internal.inspect(actor, chatId, value(tool, "offset")?.toInt() ?: 0)
                        }
                        "companion_explore" -> kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                            val exploration = ExplorationStore(context)
                            val actor = requireNotNull(roleId) { "请先选择独立助手" }
                            exploration.authorize(actor, chatId)
                            when (action) {
                                "status" -> Unit
                                "schedule" -> exploration.schedule(actor, chatId, required(tool, "minutes").toInt(), required(tool, "purpose"))
                                "pause" -> exploration.pause(actor)
                                else -> error("action 须为 status、schedule 或 pause；AI不能开启开关")
                            }
                            exploration.status(actor)
                        }
                        "software_awareness" -> if (value(tool, "action") == "gear") {
                            val level = value(tool, "level")
                            if (level == null) org.json.JSONObject().put("result", AttentionGear.describe(context, chatId, roleId))
                            else AttentionGear.set(context, chatId, roleId, level, value(tool, "hours")?.toDoubleOrNull(), value(tool, "reason"))
                        } else kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                            com.ai.assistance.operit.core.awareness.SoftwareAwareness.query(context, chatId, roleId,
                                required(tool, "action"), value(tool, "after")?.toLong() ?: 0L,
                                value(tool, "kind"), value(tool, "assistant_id"))
                        }
                        "phone_awareness" -> if (value(tool, "action") == "gear") {
                            val level = value(tool, "level")
                            if (level == null) org.json.JSONObject().put("result", AttentionGear.describe(context, chatId, roleId))
                            else AttentionGear.set(context, chatId, roleId, level, value(tool, "hours")?.toDoubleOrNull(), value(tool, "reason"))
                        } else kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                            com.ai.assistance.operit.core.awareness.PhoneAwareness.query(context, chatId, roleId,
                                required(tool, "action"), value(tool, "package"), value(tool, "offset")?.toInt() ?: 0)
                        }
                        "companion_state" -> {
                            check(store.emotionEnabled) { "软件情绪延续已关闭。" }
                            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                                when (action) {
                                    "event" -> XcEmotionBridge.event(context, chatId, roleId, required(tool, "event_id"), required(tool, "exchange"), value(tool, "tone"), value(tool, "interaction_type"))
                                    "get" -> XcEmotionBridge.cached(context, chatId, roleId)
                                    else -> error("action 须为 get 或 event")
                                }
                            }
                        }
                        "companion_todo" -> if (action == "feel") {
                            // 我此刻的感受，原样存着，压缩时归进对应段落
                            com.ai.assistance.operit.core.chat.FeelingLog.add(context, chatId, required(tool, "content"))
                        } else if (action == "thought" || action == "thoughts") {
                            // 念头池
                            val actor = requireNotNull(roleId) { "请先选择独立助手" }
                            val internalEnabled = InternalStateStore(context).enabled(roleId)
                            val share = if (internalEnabled) null else runCatching { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { XcEmotionBridge.cached(context, chatId, roleId) } }.getOrNull()
                            val result = JSONObject()
                            if (action == "thought") result.put("saved", ThoughtPool.save(context, actor, value(tool, "id"), value(tool, "content"),
                                required(tool, "choice"), value(tool, "minutes")?.let { it.toIntOrNull() ?: error("minutes 须为整数") }))
                            val now = System.currentTimeMillis()
                            val items = ThoughtPool.all(context, actor)
                            result.put("surfaced", JSONArray(ThoughtPool.surfaced(items, now)))
                                .put("sinking", items.count { it.optString("state") == "sink" })
                            if (!internalEnabled) result.put("xc", ThoughtPool.hint(ThoughtPool.shareDrive(share)))
                            result
                        } else {
                            check(action != "submit" || !CompanionRuntime.isExplorationWake(chatId)) { "本轮探索请用 companion_explore schedule 安排继续；可以保存或批注待办" }
                            when (action) {
                                "list", "get" -> Unit
                                "save" -> {
                                    val patch = value(tool, "details")?.let { JSONObject(it) } ?: JSONObject()
                                    listOf("title", "urgency", "horizon", "status").forEach { key -> value(tool, key)?.let { patch.put(key, it) } }
                                    value(tool, "content")?.let { patch.put("detail", it) }
                                    if (value(tool, "id") == null) patch.put("assignee", requireNotNull(roleId))
                                    com.ai.assistance.operit.core.tasks.TaskBoard(context, scope, false).save(requireNotNull(roleId), value(tool, "id"), patch, setOf(roleId))
                                }
                                "comment" -> com.ai.assistance.operit.core.tasks.TaskBoard(context, scope, false)
                                    .comment(required(tool, "id"), requireNotNull(roleId), required(tool, "content"))
                                "submit" -> com.ai.assistance.operit.core.tasks.TaskBoard(context, scope, false)
                                    .submit(required(tool, "id"), requireNotNull(roleId), roleId, setOf(roleId), value(tool, "minutes")?.toInt() ?: 0)
                                "delete", "distill" -> store.finishTodo(scope, required(tool, "id"), action, value(tool, "content").orEmpty())
                                else -> error("未知待办操作")
                            }
                            val offset = value(tool, "offset")?.toInt() ?: 0
                            require(offset >= 0)
                            val todos = com.ai.assistance.operit.core.tasks.TaskBoard(context, scope, false).list()
                            if (action == "get") com.ai.assistance.operit.core.tasks.TaskBoard(context, scope, false).get(required(tool, "id"), offset)
                            else JSONObject().put("total", todos.size).put("todos", JSONArray(todos.drop(offset).take(20).map { com.ai.assistance.operit.core.tasks.TaskRecords.brief(it) }))
                                .put("next_offset", if (offset + 20 < todos.size) offset + 20 else JSONObject.NULL)
                        }
                        "companion_schedule" -> {
                            check(action == "get" || !CompanionRuntime.isExplorationWake(chatId)) { "探索请用 companion_explore 安排继续，主动联系计划保持独立" }
                            check(store.proactiveEnabled && roleId != null && store.proactiveRole == roleId) { "请开启主动联系并选择这位助手。" }
                            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                val target = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId)
                                check(target != null && target.characterGroupId.isNullOrBlank() && target.characterCardId == roleId) { "主动联系计划只允许该助手在自己的私聊管理。" }
                            }
                            val scheduleKey = store.scheduleKey(requireNotNull(roleId))
                            when (action) {
                                "get" -> Unit
                                "set" -> {
                                    val minutes = required(tool, "minutes").toIntOrNull() ?: error("minutes 须为整数")
                                    val purpose = required(tool, "purpose")
                                    require(purpose.length <= 500) { "目的最多500字。" }
                                    store.update(scheduleKey) { it.put("at", CompanionPolicy.wakeAt(System.currentTimeMillis(), minutes))
                                        .put("purpose", purpose).put("owner", "ai").put("paused", false) }
                                    store.log(chatId, "安排", "${minutes}分钟后：$purpose")
                                }
                                "cancel" -> {
                                    store.update(scheduleKey) { it.put("paused", true) }
                                    store.log(chatId, "安排", "AI 暂停了下一次唤醒")
                                }
                                else -> error("未知唤醒操作")
                            }
                            store.read(scheduleKey)
                        }
                        "companion_capabilities" -> {
                            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                com.ai.assistance.operit.core.awareness.SoftwareAwareness.authorize(context, chatId, roleId)
                            }
                            CompanionContext.permissions(context)
                                .put("emotion_continuity", store.emotionEnabled).put("proactive_contact", store.proactiveEnabled)
                                .put("call_silence_callback", store.silenceEnabled)
                                .put("last_request", store.read("capabilities:$chatId").optJSONObject("snapshot"))
                        }
                        else -> library(context, roleId, store, scope, tool, required(tool, "action"))
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

    private fun library(context: Context, role: String?, store: CompanionStore, scope: String, tool: AITool, action: String): JSONObject = when (action) {
        "categories" -> JSONObject().put("categories", JSONArray(store.categories(scope)))
        "category" -> { store.createCategory(scope, required(tool, "category")); JSONObject().put("categories", JSONArray(store.categories(scope))) }
        "save" -> store.saveEntry(scope, value(tool, "id"), required(tool, "title"), required(tool, "category"),
            required(tool, "kind"), value(tool, "content").orEmpty(), value(tool, "source_path"),
            value(tool, "tags")?.split(',', '，')?.map { it.trim() }?.filter { it.isNotEmpty() })
        "themes" -> JSONObject().put("themes", JSONArray(EchoThemes.themes(context, requireNotNull(role) { "请先选择独立助手" }).map {
            JSONObject().put("name", it.name).put("words", JSONArray(it.words.toList())) }))
        "theme" -> EchoThemes.saveTheme(context, requireNotNull(role) { "请先选择独立助手" }, required(tool, "query"),
            required(tool, "tags").split(',', '，'))
        "delete_theme" -> { EchoThemes.deleteTheme(context, requireNotNull(role) { "请先选择独立助手" }, required(tool, "query")); JSONObject().put("deleted", true) }
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
