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
        store.update(key(requireNotNull(role))) { InternalStateEngine.event(it, id, source, chat, detail, System.currentTimeMillis()) }
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
        // Strip tool markup/thinking before matching reply text; those are separate host events.
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
        store.update(key(role)) {
            InternalStateEngine.evolve(it, operation, id, expected, definition, target, reason, evidence, chat, System.currentTimeMillis())
        }
    }

    fun inspect(role: String, chat: String, offset: Int): JSONObject {
        require(offset >= 0)
        val data = snapshot(role, chat, true)
        val events = InternalStateEngine.rows(data, "events").asReversed()
        val versions = InternalStateEngine.rows(data, "versions").asReversed()
        return JSONObject().put("definitions", JSONArray(InternalStateEngine.definitions(data)))
            .put("states", JSONArray(InternalStateEngine.snapshot(data, chat, System.currentTimeMillis())))
            .put("events", JSONArray(events.drop(offset).take(20))).put("versions", JSONArray(versions.drop(offset).take(20)))
            .put("next_offset", if (offset + 20 < maxOf(events.size, versions.size)) offset + 20 else JSONObject.NULL)
    }

    fun prompt(role: String?, chat: String, persist: Boolean, wake: Boolean = false, capabilities: JSONObject = JSONObject()): String =
        promptSections(role, chat, persist, wake, capabilities).asText()

    fun promptSections(role: String?, chat: String, persist: Boolean, wake: Boolean = false, capabilities: JSONObject = JSONObject()): RuntimeContextSections {
        if (!enabled(role)) return RuntimeContextSections()
        val actor = requireNotNull(role)
        val data = snapshot(actor, chat, persist)
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
            .sortedByDescending { it.optLong("updated") }.take(32).forEach {
                candidates.add(candidate("todo", it.getString("id"), it.optString("title"), it.optString("next"))
                    .put("status", it.optString("status")).put("priority", it.optString("priority")))
            }
        ReadingRoom(context, actor).sources().filter { it.optBoolean("enabled") }.take(20).forEach {
            candidates.add(candidate("explore", it.getString("id"), it.optString("title"),
                if (wake && !CompanionRuntime.isExplorationWake(chat)) "预约独立探索后继续：${it.optString("progress")}" else it.optString("progress"), exploration))
        }
        val visibility = capabilities.optJSONObject("tool_visibility")
        InternalStateEngine.definitions(data).filter { it.getBoolean("active") && it.getBoolean("participates") }
            .flatMap { InternalStateEngine.rows(it, "effects") }
            .filter { it.getString("action") == "tool" && it.optString("target").isNotBlank() }
            .map { it.getString("target") }.distinct().forEach { name ->
                candidates.add(candidate("tool", name, name, "按需选择此工具；实际调用仍须通过正常权限检查",
                    capabilities.optBoolean("tools_enabled") && visibility?.optBoolean(name) == true))
            }
        val now = System.currentTimeMillis()
        val decision = InternalStateEngine.decision(data, chat, now, candidates)
            .put("thoughts", JSONArray(ThoughtPool.surfaced(ThoughtPool.all(context, actor), now)))
        if (persist) {
            val compact = InternalStateEngine.promptPayload(decision)
            // Sizes only: do not log private state, thought text or candidate descriptions.
            AppLogger.d("InternalState", "Request state JSON chars: full=${decision.toString().length}, compact=${compact.toString().length}, states=${compact.getJSONArray("states").length()}, candidates=${compact.getJSONArray("candidates").length()}, thoughts=${compact.getJSONArray("thoughts").length()}")
        }
        val sections = InternalStateEngine.promptSections(decision)
        return sections.copy(instructions = sections.instructions +
            "thoughts 是到期浮现的念头；追、讲还是扔由你决定。用 companion_todo action=thought id=… choice=now/sink/drop 处理；没想好可再 sink 一阵。\n" +
            "维度不是预设枚举。需要时用 companion_internal_state get 查看完整状态定义、触发规则、计算参数、真实事件和版本，再 create/revise/merge/deactivate；说明经过并引用事件ID。" +
            "定义中 effects 用 action/weight/cue 和可选 target 指定影响方向；不参与决策的维度仅记录。不能直接改当前值或伪造触发事件。\n" +
            "内部状态开启时，它是唯一自动注入的状态源；XC 保留连接和记录，不叠加其状态提示。\n")
    }

    companion object {
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

