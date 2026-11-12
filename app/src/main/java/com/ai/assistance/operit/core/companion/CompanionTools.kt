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
    val names = com.ai.assistance.operit.core.experience.ExperienceTools.names + RoomTools.names + setOf("companion_software", "companion_internal_state", "companion_state", "companion_library", "companion_todo", "companion_schedule", "companion_explore", "companion_capabilities", "phone_awareness", "software_awareness")

    fun prompts(): List<ToolPrompt> = com.ai.assistance.operit.core.experience.ExperienceTools.prompts() + RoomTools.prompts() + listOf(
        ToolPrompt(name = "companion_software",
            description = "在本助手私聊中使用现有计算器的持久表达式能力。build 用 id/source 保存纯四则算术模板，变量写成 ${'$'}{x}，previous_version 可关联上次产物进行调整；不执行源码，只校验并返回不可变版本。run 用 version 和 inputs JSON 数字对象真正执行；get 查原产物。只支持数字、括号及 + - * / %，不支持文件、网络、任意JS或自动状态修改。已有脚本和工具包仍用其原工具。技术运行结果与主体对结果的理解分开，回执用 internal_state section=action 查。可带 __operit_action 关联明确打算。",
            parametersStructured = listOf(param("action", "build/run/get", true), param("id", "能力名称"),
                param("source", "算术模板"), param("previous_version", "调整的原版本"), param("version", "返回的产物版本"),
                param("inputs", "变量数字JSON对象"), param("__operit_action", "可选决定及来源JSON"))),
        ToolPrompt(name = "companion_internal_state",
            description = "Operit 自有内部状态的查看和定义维护。action=get|create|revise|merge|deactivate|budget。budget 用 tokens 设置本主体材料预算2048至16384（默认2048，宿主估算，非完整模型窗口）。get 可用 section=inner|inner_pending|episode|focus|experience_link|union|category_versions|source|action|decision|feedback|activity|activity_part|waking_note；id 查具体记录，版本用 episode:ID、union:ID、decision:ID、feedback:ID 或 focus:类别ID；offset 真正分页。action 是实际工具请求与回执，decision/feedback 是私聊中已采用的决定/理解，技术完成不等于主观满足。省略 section 返回原状态与本页事件/重点。get 返回当前定义、宿主真实事件、版本和最近选材记录，offset翻页；inner 包含内在结构与候选（每页8项），inner_history 是增量历史，按各自 next_offset 继续。" +
                "inner_family 用 id 指定 family 分页；长候选可用 inner_chunk +候选id+字符offset 分段读完整原文，长的已采用内容用 inner_record_chunk +记录id+字符offset；next_offset继续，未完整提供不赋予采用资格。当前主观值可通过主生成中的 operit_state_report 回报；updates更新已有状态，报告create可原子新建定义并写首次体验，报告revise可在同一次生成中改名、改简介、改类别或时间规则，未报告的维度保留。" +
                "本工具的定义操作仍不接受value，不能伪造宿主事件。定义操作必须有id、expected_version（新建0）、reason、evidence（已提供的真实事件ID的JSON数组）。" +
                "create/revise传完整definition JSON：id,name,description,kind（只能drive驱力/sentiment情感/emotion情绪/psychology心理）,baseline,min,max,scope=global|session,private,share（AI愿向本机公开）,participates,effects=[{action,weight,cue,target可选}],time_rules，可带object_ref绑定明确对象。" +
                "新建必须明确kind和完整time_rules；旧定义缺kind时保留unclassified，新时间规则显示undefined，原规则继续到明确修订，不能替它预设类别、数值或速度。不同对象不要无声共用同一状态ID。" +
                "time_rules={increase:{condition,reason,interval_minutes,amount},decrease:{condition,reason,interval_minutes,amount},floor}，condition只能always（任何）/not_contacted（未联系）/custom（自定义）；reason可自由填写具体原因。任一方向为not_contacted时另须contact_window_minutes，由实际用户消息时间判断，重试和唤醒不刷新联系。" +
                "数字选项0至30或自定义；自定义仅限有限数字，不限于0至30且可为小数。interval_minutes<=0暂停该方向，amount=0不变化，负amount反向变化；contact_window_minutes<=0表示无等待窗口。floor由模型自行确定，30不是默认值，且须在状态总范围内；它只限时间下降，不限制有来源的主观反馈。" +
                "custom由主生成报告updates.time_conditions={increase:true/false,decrease:true/false}确认成立或结束，只写自定义方向；允许只报条件与reason而不填写value。未确认条件不计时，已确认条件保留至明确变更。" +
                "明确time_rules后日常事件值由模型回报，用户也可在本机状态详情手动修改并留下user_edit来源；旧maxDelta/halfLifeHours/triggers不再参与计算，新定义不需要这些旧字段。未迁移旧定义仍按原半衰期/时间漂移和客观事件规则计算，主观消息与唤醒不按旧关键词增减。" +
                "effects action=contact|explore|todo|tool|reply|defer|drop|rest，weight为-10..10的相对基准倾向，cue说明影响，target可为待办/阅读来源ID或工具名；选中或高分都不强制执行。" +
                "merge用target指定同作用域现有目标，宿主平均归一化值并停用来源，旧历史保留；revise不重置当前值。" +
                "本工具的定义操作会改变语义版本，同轮旧frame状态报告会被拒绝；同轮改名、简介、分类、时间规则应使用最终报告revise:[{id,expected_version,changes:{要改的name/description/kind/time_rules},reason,evidence可选}]，可与updates原子保存。稳定ID不变，规则修改不重算过去。状态不授予工具、探索或联系权限。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "维度稳定ID"),
                param("definition", "完整维度定义JSON；新建须有四类之一的kind及完整time_rules，不夹带value"), param("expected_version", "get读取的定义版本，新建0"),
                param("reason", "演化经过与理由"), param("evidence", "真实事件ID的JSON数组"),
                param("target", "merge目标维度ID"), param("tokens", "budget 本主体材料预算2048至16384"), param("offset", "历史分页起点"), param("section", "get 可选 episode/focus/experience_link/union/category_versions/source/action/decision/feedback/rest/dream/dream_understanding（私域记录仅本人私聊）"))),
        ToolPrompt(name = "companion_explore",
            description = "查询或安排你自己的自主探索。默认关闭，只有用户能在设置里开启。action=status|schedule|pause；schedule 用 minutes（15–10080）和 purpose 安排一次继续，可在自己的私聊中使用，执行时跟随最近活跃私聊；pause 明说要歇、暂停后续预约；启用连续性内部状态的助手没有自动续约，只有明确安排的事项才继续；其他助手保留既有三小时判断规则。独立于主动联系、感知事件和已提交待办。开启不代表每次必须取材、继续旧方向或联系用户，不得通过其他调度工具绕过关闭状态或上限。资料与随记用 companion_library 分别保存，私人念头用 companion_todo 记录。",
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
        ToolPrompt(name = "companion_library",
            description = "角色资料库。action=categories|category|list|search|get|save|delete|themes|theme|delete_theme。category 创建分类；save 保存知识、能力笔记或文件副本，可提供 id 更新或移动到另一分类，tags 逗号分隔给资料打主题标签（不传保留原标签）；get/delete 必须提供 id。search 提供 query。list/search 每页20项，可传 offset。旧日回声：theme 用 query=主题名、tags=挂在下面的词（逗号分隔）新建或覆盖一个主题，例如 query=性 tags=做爱,dirty talk,高潮管理；聊天碰到主题名或这些词，资料库、学习角已读、日记里相关的旧东西会列出标题，打不打开你定。themes 查看全部主题，delete_theme 用 query 删除。分类由你决定，也可按用户要求整理。文件保留在本机，不自动执行。",
            parametersStructured = listOf(param("action", "操作", true), param("id", "资料ID"),
                param("title", "标题"), param("category", "自定义分类，可用/分级"), param("kind", "knowledge、skill 或 file"),
                param("content", "知识或能力笔记的正文"), param("source_path", "需复制保存的可读取本地文件路径或content URI"),
                param("query", "检索词；theme/delete_theme 时为主题名"), param("offset", "分页起点，非负整数"),
                param("tags", "逗号分隔的主题标签，或 theme 时挂在主题下的词"))),
        ToolPrompt(name = "companion_todo",
            description = "独立助手自己的待办与私人打算。你可以自己创建心愿、批注和提交处理，不必等用户布置任务。action=list|get|save|comment|submit|delete|distill|thought|thoughts|feel。feel 用 content 记下我此刻的真实感受或心情（一两句原话），压缩时原样放进这段对话的“我的感受”，不交给模型代写；没记的段就空着。list 返回简表；get 用 id 读取详情，offset 为历史分页起点。comment 用 content 添加署名批注；submit 指定 id 和 minutes（0为尽快），空闲时由你按自己当前能力继续处理。保存和批注不会强迫立即执行。save 新建或用 id 更新，判断紧急性、时间线与完成状态；distill 将已完成事项的实际收获 content 存到资料库并移除待办。不要把计划当作已完成。念头池：thought 用 content 存一个念头，choice 自己选 now（现在讲，记一笔然后直接说）、sink（先沉着，minutes 可选，默认两天后浮上来）或 drop（扔掉）；带 id 改已有念头的去留。thoughts 查看浮上来的念头。",
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
        com.ai.assistance.operit.core.experience.ExperienceTools.register(handler, context)
        names.filterNot { it in RoomTools.names || it in com.ai.assistance.operit.core.experience.ExperienceTools.names }.forEach { name ->
            handler.registerTool(name = name, descriptionGenerator = { name }, executor = { tool ->
                try {
                    val chatId = required(tool, "__operit_package_chat_id")
                    val roleId = value(tool, "__operit_package_caller_card_id")
                    val store = CompanionStore(context)
                    val scope = store.scope(chatId, roleId)
                    val action = value(tool, "action")
                    val scheduleActivity = if (name in setOf("companion_schedule", "companion_explore")) ActivityNotes.current(chatId) else null
                    var privateStateRead = false
                    val result = when (name) {
                        "companion_software" -> kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO +
                            com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                            val capability = SoftwareCapabilityArtifacts(context, requireNotNull(roleId), chatId)
                            when (action) {
                                "build" -> capability.build(required(tool, "id"), required(tool, "source"), value(tool, "previous_version"))
                                "run" -> capability.run(required(tool, "version"), JSONObject(required(tool, "inputs")))
                                "get" -> capability.get(required(tool, "version"))
                                else -> error("不支持的软件能力操作")
                            }
                        }
                        "companion_internal_state" -> {
                            val actor = requireNotNull(roleId) { "请先选择独立助手" }
                            val internal = InternalStateStore(context)
                            check(internal.enabled(actor)) { "内部状态未开启" }
                            if (action != "get" && action != "budget") {
                                val evidence = JSONArray(required(tool, "evidence"))
                                internal.evolve(actor, chatId, requireNotNull(action), required(tool, "id"),
                                    required(tool, "expected_version").toInt(), value(tool, "definition")?.let { JSONObject(it) },
                                    value(tool, "target"), required(tool, "reason"), (0 until evidence.length()).map { evidence.getString(it) })
                            }
                            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                                privateStateRead = !internal.requireRecipient(actor, chatId)
                                val section = value(tool, "section")
                                check(privateStateRead || (section != "union" &&
                                    !(section == "category_versions" && value(tool, "id").orEmpty().startsWith("union:")))) {
                                    "私人联合须在该助手自己的私聊读取"
                                }
                                if (action == "budget") internal.materialBudget(actor, chatId, required(tool, "tokens").toInt())
                                else if (action == "get" && section != null) internal.inspectCategory(actor, chatId, section, value(tool, "id"), value(tool, "offset")?.toInt() ?: 0)
                                else internal.inspect(actor, chatId, value(tool, "offset")?.toInt() ?: 0)
                            }
                        }
                        "companion_explore" -> kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.inheritedRuntimeContext()) {
                            val exploration = ExplorationStore(context)
                            val actor = requireNotNull(roleId) { "请先选择独立助手" }
                            exploration.authorize(actor, chatId)
                            val saved = when (action) {
                                "status" -> exploration.read(actor)
                                "schedule" -> exploration.schedule(actor, chatId, required(tool, "minutes").toInt(), required(tool, "purpose"))
                                "pause" -> exploration.pause(actor)
                                else -> error("action 须为 status、schedule 或 pause；AI不能开启开关")
                            }
                            if (action != "status") recordSavedSchedule(context, scheduleActivity, saved, "exploration")
                            exploration.status(actor, saved)
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
                        "companion_state" -> error("XC 专用连接已移除。旧回执保留为历史，不再连接、同步或写入。")
                        "companion_todo" -> if (action == "feel") {
                            // 我此刻的感受，原样存着，压缩时归进对应段落
                            com.ai.assistance.operit.core.chat.FeelingLog.add(context, chatId, required(tool, "content"))
                        } else if (action == "thought" || action == "thoughts") {
                            // 念头池
                            val actor = requireNotNull(roleId) { "请先选择独立助手" }
                            val result = JSONObject()
                            if (action == "thought") result.put("saved", ThoughtPool.save(context, actor, value(tool, "id"), value(tool, "content"),
                                required(tool, "choice"), value(tool, "minutes")?.let { it.toIntOrNull() ?: error("minutes 须为整数") }))
                            val now = System.currentTimeMillis()
                            val page = ThoughtPool.page(context, actor, value(tool, "offset")?.toInt() ?: 0)
                            val items = page.getJSONArray("items").objects()
                            result.put("surfaced", JSONArray(ThoughtPool.surfaced(items, now)))
                                .put("page", page).put("notice", "分页列出全部念头；未显示不表示消失，用 offset 续读")
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
                            val saved = when (action) {
                                "get" -> store.read(scheduleKey)
                                "set" -> {
                                    val minutes = required(tool, "minutes").toIntOrNull() ?: error("minutes 须为整数")
                                    val purpose = required(tool, "purpose")
                                    require(purpose.length <= 500) { "目的最多500字。" }
                                    store.update(scheduleKey) { CompanionSchedule.supersede(it); it.put("at", CompanionPolicy.wakeAt(System.currentTimeMillis(), minutes))
                                        .put("purpose", purpose).put("owner", "ai").put("paused", false) }.also {
                                        logSavedSchedule(store, chatId, "${minutes}分钟后：$purpose")
                                    }
                                }
                                "cancel" -> {
                                    store.update(scheduleKey) { CompanionSchedule.cancel(it) }.also {
                                        logSavedSchedule(store, chatId, "AI 暂停了下一次唤醒")
                                    }
                                }
                                else -> error("未知唤醒操作")
                            }
                            if (action != "get") {
                                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                    recordSavedSchedule(context, scheduleActivity, saved, "contact")
                                }
                            }
                            CompanionSchedule.status(saved)
                        }
                        "companion_capabilities" -> {
                            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                com.ai.assistance.operit.core.awareness.SoftwareAwareness.authorize(context, chatId, roleId)
                            }
                            CompanionContext.permissions(context)
                                .put("internal_continuity", InternalStateStore(context).enabled(roleId)).put("proactive_contact", store.proactiveEnabled)
                                .put("call_silence_callback", store.silenceEnabled)
                                .put("last_request", store.read("capabilities:$chatId").optJSONObject("snapshot"))
                        }
                        else -> library(context, roleId, store, scope, tool, required(tool, "action"))
                    }
                    val payload = if (name == "companion_internal_state" && action == "get")
                        com.ai.assistance.operit.core.tools.InternalStateReadResultData(
                            result.toString(), requireNotNull(roleId), chatId, value(tool, "section"), privateStateRead)
                    else StringResultData(result.toString())
                    ToolResult(toolName = tool.name, success = true, result = payload)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogger.e("CompanionTools", "Companion operation failed: $name", error)
                    ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = error.message.orEmpty())
                }
            })
        }
    }

    private suspend fun recordSavedSchedule(context: Context, ref: ActivityNotes.Ref?, saved: JSONObject, kind: String) {
        if (ref == null) return
        try {
            ActivityNotes.schedule(context, ref, JSONObject(saved.toString()).put("schedule_kind", kind))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Scheduling has already committed. A failed activity index is not a failed
            // schedule and must not encourage the model to repeat the successful write.
            AppLogger.e("CompanionTools", "预约已保存，活动展示索引未写入：$kind", failure)
        }
    }

    private fun logSavedSchedule(store: CompanionStore, chat: String, detail: String) {
        try { store.log(chat, "安排", detail) }
        catch (failure: Exception) { AppLogger.e("CompanionTools", "预约已保存，安排日志未写入", failure) }
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
