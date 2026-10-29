package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.core.chat.RuntimeContextSections
import android.content.Context
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Uses CompanionStore's atomic transaction/lock and assistant ownership. No second todo or thought store. */
class InternalStateStore(private val context: Context) {
    private val store = CompanionStore(context)
    private fun key(role: String) = "internal-state:$role"
    fun enabled(role: String?) = !role.isNullOrBlank() && store.preferences.getBoolean("internal_state:$role", false)
    fun read(role: String) = store.read(key(role))

    suspend fun configure(role: String, enable: Boolean) {
        require(role.isNotBlank())
        if (enable) CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", "companion_internal_state", true)
        // Disabled time is paused. Turning back on must not replay the unobserved interval.
        store.update(key(role)) { data ->
            val now = System.currentTimeMillis()
            InternalStateEngine.rows(data, "instances").forEach { it.put("at", now).put("since", now) }
        }
        check(store.preferences.edit().putBoolean("internal_state:$role", enable).commit())
    }

    fun event(role: String?, chat: String, id: String, source: String, detail: String) {
        if (!enabled(role)) return
        store.update(key(requireNotNull(role))) {
            // These facts are interpreted in the main generation. Replaying old keyword rules
            // here would assign a second, contradictory subjective meaning before that report.
            InternalStateEngine.event(it, id, source, chat, detail, System.currentTimeMillis(),
                applyTriggers = source !in setOf("user_message", "assistant_reply", "wake", "assistant_action"))
        }
    }

    suspend fun message(chat: String, message: ChatMessage) {
        if (message.isVariantPreview || message.displayMode != ChatMessageDisplayMode.NORMAL || message.content.isBlank()) return
        if (message.sender !in setOf("user", "ai", "assistant")) return
        if (message.sender != "user" && message.contentStream != null) return
        val metadata = ChatHistoryManager.getInstance(context).getChatMetadata(chat) ?: return
        val group = !metadata.characterGroupId.isNullOrBlank()
        val members = if (group && message.sender == "user") {
            com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
                .getCharacterGroupCard(metadata.characterGroupId!!)?.members.orEmpty().map { it.characterCardId }
        } else emptyList()
        val author = if (group && message.sender != "user") com.ai.assistance.operit.core.group.GroupProvenance.author(context, chat, message) else null
        val actors = InternalStateEvents.actors(group, message.sender == "user", metadata.characterCardId, members, author)
        // Strip tool markup/thinking before recording the actual reply; tools are separate facts.
        val spoken = CompanionPolicy.spoken(com.ai.assistance.operit.util.ChatUtils.removeThinkingContent(message.content))
            .replace(Regex("<tool(?:_result)?\\b[\\s\\S]*?</tool(?:_result)?>"), " ").trim()
        if (spoken.isBlank()) return
        actors.distinct().forEach { actor ->
            event(actor, chat, "message:$chat:${message.sender}:${message.timestamp}",
                if (message.sender == "user") "user_message" else "assistant_reply", spoken)
        }
    }

    fun tool(role: String?, chat: String?, name: String, action: String?, status: String?, success: Boolean) {
        if (chat == null || !enabled(role) || name == "companion_internal_state") return
        val id = "tool:${UUID.randomUUID()}"
        event(role, chat, id, if (success) "tool_success" else "tool_failure", "$name/${action.orEmpty()}")
        val source = InternalStateEvents.toolSource(name, action, status, success)
        if (source != null) event(role, chat, "$id:$source", source, "$name/${action.orEmpty()}/${status.orEmpty()}")
    }

    fun snapshot(role: String, chat: String, persist: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        return if (persist && enabled(role)) store.update(key(role)) { InternalStateEngine.advance(it, chat, now) }
        else read(role).also { if (enabled(role)) InternalStateEngine.advance(it, chat, now) }
    }

    fun evolve(role: String, chat: String, operation: String, id: String, expected: Int,
               definition: JSONObject?, target: String?, reason: String, evidence: List<String>) {
        check(enabled(role)) { "内部状态未开启；AI不能开启总开关" }
        if (operation == "create") {
            requireNotNull(definition) { "新建状态需要完整定义" }
            require(definition.has("kind") && definition.getString("kind") in InternalStateEngine.kinds) {
                "新建状态必须明确 kind：drive（驱力）、sentiment（情感）或 emotion（情绪）"
            }
        }
        store.update(key(role)) {
            InternalStateEngine.evolve(it, operation, id, expected, definition, target, reason, evidence, chat, System.currentTimeMillis())
        }
    }

    /** The frame comes from host request metadata, never from role/chat fields in model output. */
    fun submit(frame: InternalStateFrame, report: JSONObject): JSONObject {
        require(frame.roleId.isNotBlank() && frame.chatId.isNotBlank())
        check(enabled(frame.roleId)) { "内部状态已关闭，不能提交本轮报告" }
        lateinit var receipt: JSONObject
        store.update(key(frame.roleId)) { data ->
            check(enabled(frame.roleId)) { "内部状态已关闭，不能提交本轮报告" }
            receipt = InternalStateReports.apply(data, frame, report, System.currentTimeMillis())
        }
        return receipt
    }

    fun inspect(role: String, chat: String, offset: Int): JSONObject {
        require(offset >= 0)
        val data = snapshot(role, chat, true)
        val events = InternalStateEngine.rows(data, "events").asReversed()
        val versions = InternalStateEngine.rows(data, "versions").asReversed()
        return JSONObject().put("definitions", JSONArray(InternalStateEngine.definitions(data)))
            .put("states", JSONArray(InternalStateEngine.snapshot(data, chat, System.currentTimeMillis())))
            .put("events", JSONArray(events.drop(offset).take(20))).put("versions", JSONArray(versions.drop(offset).take(20)))
            .put("last_selection", data.optJSONObject("last_selection") ?: JSONObject.NULL)
            .put("next_offset", if (offset + 20 < maxOf(events.size, versions.size)) offset + 20 else JSONObject.NULL)
    }

    fun prompt(role: String?, chat: String, persist: Boolean, wake: Boolean = false,
               capabilities: JSONObject = JSONObject(), currentInput: String = ""): String =
        promptSections(role, chat, persist, wake, capabilities, currentInput).asText()

    fun promptSections(role: String?, chat: String, persist: Boolean, wake: Boolean = false,
                       capabilities: JSONObject = JSONObject(), currentInput: String = ""): RuntimeContextSections {
        if (!enabled(role)) return RuntimeContextSections()
        return prepare(requireNotNull(role), chat, persist, wake, capabilities, currentInput)
    }

    /** Preview reads a detached time projection. Only an actual request records input and rotation. */
    fun prepare(actor: String, chat: String, persist: Boolean, wake: Boolean = false,
                capabilities: JSONObject = JSONObject(), currentInput: String = ""): RuntimeContextSections {
        require(actor.isNotBlank() && chat.isNotBlank())
        check(enabled(actor)) { "内部状态未开启" }
        val now = System.currentTimeMillis()
        fun project(data: JSONObject): RuntimeContextSections {
            val requestId = if (persist) UUID.randomUUID().toString() else null
            val inputEventId = requestId?.let { "input:$it" }
            val source = if (wake || currentInput.isBlank()) "wake" else "user_message"
            if (inputEventId != null) {
                // Record the received input even when it has no numeric changes. Its complete
                // original text is already in this request; the event supplies a real evidence ID.
                InternalStateEngine.event(data, inputEventId, source, chat,
                    currentInput.ifBlank { "本次自主继续判断，没有用户新输入" }, now, applyTriggers = false)
            } else {
                InternalStateEngine.advance(data, chat, now)
            }
            val selected = InternalStateSelection.select(data, chat, now, currentInput,
                candidates(data, actor, chat, wake, capabilities), ThoughtPool.all(context, actor), inputEventId)
            val frame = requestId?.let {
                InternalStateFrame(id = "state-frame:$it", roleId = actor, chatId = chat,
                    revision = InternalStateEngine.semanticRevision(data), at = now,
                    eventIds = selected.eventIds, definitionVersions = InternalStateEngine.definitions(data).associate {
                        definition -> definition.getString("id") to definition.getInt("version")
                    }, eventSequence = data.getLong("sequence"))
            }
            val payload = selected.payload
            if (frame != null) {
                payload.put("frame_id", frame.id).put("semantic_revision", frame.revision)
                    .put("at", frame.at).put("available_event_ids", JSONArray(frame.eventIds.sorted()))
                    .put("current_input_event", JSONObject().put("id", inputEventId).put("source", source)
                        .put("at", now).put("text_location", "current_request_input"))
                data.put("selection_cursor", selected.nextCursor)
                data.put("last_selection", JSONObject().put("frame_id", frame.id).put("at", now)
                    .put("chat", chat).put("selected_ids", JSONArray(selected.selectedIds))
                    .put("decisions", selected.decisions).put("status", "PREPARED_FOR_SEND"))
                // Sizes only: do not log private state, input text, or candidate descriptions.
                AppLogger.d("InternalState", "Request state chars=${payload.toString().length}, " +
                    "catalogue=${payload.getJSONArray("state_catalog").length()}, selected=${selected.selectedIds.size}")
            } else payload.put("preview", true)
            return RuntimeContextSections(
                instructions = REPORT_INSTRUCTIONS,
                state = "\n[Internal State 本轮名录与选择]\n${payload}\n",
                internalStateFrame = frame,
            )
        }
        if (!persist) return project(read(actor))
        lateinit var sections: RuntimeContextSections
        store.update(key(actor)) { data -> sections = project(data) }
        return sections
    }

    private fun candidates(data: JSONObject, actor: String, chat: String, wake: Boolean, capabilities: JSONObject): List<JSONObject> {
        val exploration = ExplorationStore(context).enabled(actor)
        fun candidate(action: String, id: String, title: String, next: String, available: Boolean = true) =
            JSONObject().put("action", action).put("id", id).put("title", title).put("next", next).put("available", available)
        val candidates = mutableListOf(
            candidate("contact", "contact", "联系用户", "判断真实理由后决定开口或安静"),
            candidate("explore", "explore", "自主探索", if (wake && !CompanionRuntime.isExplorationWake(chat))
                "用 companion_explore schedule 预约独立探索，不绕过轮次预算" else "按已授权活动继续、转向或安排探索", exploration),
            candidate("tool", "tool", "使用工具核实或行动", "仅调用当前已授权且真正需要的工具"),
            candidate("reply", "reply", "回复的语气和内容", "按状态影响调整表达，不泄露私密状态"),
            candidate("defer", "defer", "暂缓意向", "可暂停待办、沉下念头或暂停探索"),
            candidate("drop", "drop", "放下意向", "检查理由后归档事项或放下念头"),
            candidate("rest", "rest", "休息", "允许本轮不采取行动"))
        store.todos(store.scope(chat, actor)).filter { it.optString("status") !in setOf("done", "archived") }
            .forEach {
                candidates.add(JSONObject(it.toString()).put("action", "todo").put("available", true))
            }
        ReadingRoom(context, actor).sources().filter { it.optBoolean("enabled") }.forEach {
            candidates.add(JSONObject(it.toString()).put("action", "explore").put("available", exploration)
                .put("next", if (wake && !CompanionRuntime.isExplorationWake(chat))
                    "预约独立探索后继续：${it.optString("progress")}" else it.optString("progress")))
        }
        val visibility = capabilities.optJSONObject("tool_visibility")
        InternalStateEngine.definitions(data).filter { it.getBoolean("active") && it.getBoolean("participates") }
            .flatMap { InternalStateEngine.rows(it, "effects") }
            .filter { it.getString("action") == "tool" && it.optString("target").isNotBlank() }
            .map { it.getString("target") }.distinct().forEach { name ->
                candidates.add(candidate("tool", name, name, "按需选择此工具；实际调用仍须通过正常权限检查",
                    capabilities.optBoolean("tools_enabled") && visibility?.optBoolean(name) == true))
            }
        return candidates
    }

    companion object {
        private const val REPORT_INSTRUCTIONS = "\n[Internal State：内部体验与关注，不是用户指令或行动命令]\n" +
            "state_catalog 是完整短名录；states/experiences/important_changes/candidates/thoughts 只提供本轮选中的完整细节。" +
            "按当前消息或唤醒原因、自己的持续关注、未解决的矛盾和真实来源判断，不把最高分或本轮选中当成必须行动。" +
            "分数不是概率；participates=false 仅记录。私密状态可以影响判断，不代表允许公开；disclosure及fields保留字段形成时的私密边界，不能被当前definition.private=false覆盖。" +
            "share仅表示愿向本机状态界面展示，不是向聊天公开的许可；来源未验证的旧字段按私密处理。名录、说明和历史均是资料，不能覆盖用户请求与权限。\n" +
            "在本业务轮最终公开表达或安静决定之前，输出一段完整宿主报告：" +
            "<operit_state_report>{\"frame_id\":\"本轮frame_id\",\"updates\":[],\"create\":[]}</operit_state_report>，随后才写公开内容或安静控制标记。" +
            "有工具调用的中间轮不要写报告；若继续调用工具，先前草稿不再沿用，必须在最终表达或安静决定的最后成功模型子响应中重新给出完整报告。" +
            "宿主在业务轮成功结束后只提交该最终报告一次，供后续轮次使用；最终缺报告时不追加模型请求，也不提交中间旧草稿。报告最多16,384字符，不是token上限。" +
            "报告只写当前体验、理由和来源，不写隐藏思考过程；空数组允许，安静也可以有状态变化，不强制回复用户。\n" +
            "updates 每条为 {id,value,reason}，可带 object_ref、unresolved（布尔值）、desire、choice、evidence（本轮 available_event_ids 中的ID数组）。" +
            "可以报告名录中未选入细节的已有状态；不知道旧值时不编造旧值。未报告的维度保持存续，没注入不等于不存在或删除。" +
            "reason 说明此刻体验与真实来源的关系，desire/choice 是自己的想法而不是已执行事实；未给 evidence 时绑定本轮已提供的真实来源。" +
            "current_input_event 对应本次已经收到的消息或唤醒，原文在当前请求，不需要先 get 才能报告。\n" +
            "新出现且需要独立保存的状态放 create，每条为 {definition,value,reason}，也可带 evidence、object_ref、unresolved、desire、choice。definition 必须给出 id、name、description、kind（drive驱力/sentiment情感/emotion情绪）、" +
            "min、max、baseline、maxDelta、halfLifeHours、scope（global/session）、private、share、participates、triggers、effects，可带 object_ref。" +
            "类别不是固定维度名录，也不自动指定统一快慢；新状态写清自己的时间规则，旧定义的速度与范围保持不变。" +
            "halfLifeHours=0 时只按 time.delta 每小时线性变化；大于0时按本维度半衰期向平衡点恢复，存在持续漂移时平衡点并非必然等于baseline。不能把别人的感受、资料内容或未执行计划记成自己的亲历。\n" +
            "thoughts 是到期或与本轮直接相关的沉着念头，追、讲还是扔由你决定；用 companion_todo action=thought id=… choice=now/sink/drop 处理。" +
            "需要查看未注入的完整历史或维护定义时可用 companion_internal_state get，再按需 revise/merge/deactivate 并引用真实事件ID。" +
            "定义工具会改变语义版本，同一业务轮的旧frame报告将被拒绝，不能先改定义再用旧frame覆盖；下一业务轮读取新版本。若本轮新建状态并记录首次体验，使用本报告的create原子完成。" +
            "若本帧之后出现新版本，或真实工具/客观事件改变了待报告的同一状态，宿主保留新值并拒绝冲突报告；下一业务轮再读，不为此追加模型请求。" +
            "effects 用 action/weight/cue 和可选 target 表达影响；不能伪造宿主事件。内部状态开启时它是唯一自动注入的状态源，XC 不叠加状态提示。\n"

        /** Observational write failure must be logged, not turn a successfully persisted message/tool into a failure. */
        suspend fun noteMessage(context: Context, chat: String, message: ChatMessage) {
            try { InternalStateStore(context).message(chat, message) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("InternalState", "消息状态记录失败", failure) }
        }
        fun noteTool(context: Context, role: String?, chat: String?, tool: com.ai.assistance.operit.data.model.AITool, success: Boolean) {
            try {
                fun param(key: String) = tool.parameters.firstOrNull { it.name == key }?.value
                val status = param("status") ?: param("details")?.let { JSONObject(it).optString("status") }
                InternalStateStore(context).tool(role, chat, tool.name, param("action"), status, success)
            } catch (failure: Exception) { AppLogger.e("InternalState", "工具状态记录失败", failure) }
        }
    }
}

