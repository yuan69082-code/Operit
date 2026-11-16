package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.util.ChatUtils
import kotlinx.coroutines.*
import org.json.JSONObject

/** Only tool-free function-model calls. Even model-emitted tool syntax is inert dream text. */
internal class ParticipatoryDreams(private val context: Context) {
    fun interface Model {
        suspend fun generate(system: String, input: String, savePartial: suspend (String) -> Unit): String
    }
    private val store = CompanionStore(context)

    suspend fun run(work: SubjectLife.Work, model: Model? = null) {
        val role = work.role
        val rest = requireNotNull(SubjectLife(context).rest(role))
        check(rest.getJSONObject("content").getString("state") == "resting")
        val plan = JSONObject(work.text)
        val steps = plan.getInt("steps")
        require(steps in 1..6)
        val data = InternalStateStore(context).read(role)
        val budget = data.optInt("material_budget_tokens", SubjectMaterialBudget.DEFAULT_TOKENS)
        val sections = InnerStructureContext.prepare(context, role, work.chat, false, true,
            JSONObject().put("subject_material_tokens", (budget / 2).coerceAtLeast(256)), input = plan.toString())
        val seed = JSONObject(sections.state.substringAfter("\n[Internal State 本轮名录与选择]\n"))
        val card = CharacterCardManager.getInstance(context).getCharacterCard(role)
        val mapping = if (card.chatModelBindingMode == CharacterCardChatModelBindingMode.FIXED_CONFIG)
            requireNotNull(card.chatModelConfigId) to card.chatModelIndex
        else FunctionalConfigManager(context).getConfigMappingForFunction(FunctionType.CHAT).let { it.configId to it.modelIndex }
        val config = if (model == null) requireNotNull(ModelConfigManager(context).getModelConfig(mapping.first)) { "助手模型配置不存在" } else null
        val key = "dream:${role}:${work.id}"
        val service = if (model == null) EnhancedAIService.getChatInstance(context, key) else null
        val actual = model ?: Model { system, input, partial ->
            val selected = requireNotNull(config)
            check(SubjectMaterialBudget.estimate(system + input) + selected.maxTokens <= (selected.contextLength * 1024).toLong()) {
                "梦境请求超出当前模型窗口，未发送；已有片段仍保留"
            }
            requireNotNull(service).callFunctionModel(FunctionType.CHAT,
                listOf(PromptTurn(PromptTurnKind.SYSTEM, system), PromptTurn(PromptTurnKind.USER, input)),
                chatModelConfigIdOverride = mapping.first, chatModelIndexOverride = mapping.second,
                stream = true, maximumOutputCharacters = 12000, onOutput = partial)
        }
        val previous = store.activityRead(role, "dream_process", work.id)
        check(previous == null || previous.optString("status") == "resume_authorized") {
            "此梦已开始；仅明确恢复可以续接，不自动重复调用"
        }
        fun saveProcess(status: String, next: Int) = store.activityWrite(role, "dream_process", work.id, "pending") { old ->
            (old ?: JSONObject().put("id", work.id).put("parent", "pending").put("owner", role)
                .put("source_chat", work.chat).put("at", System.currentTimeMillis()).put("plan", plan)
                .put("rest_version", rest.getInt("version")).put("reality", "DREAM_NOT_REALITY"))
                .put("status", status).put("next_step", next)
                .put("parent", "pending")
        }
        val next = previous?.optInt("next_step") ?: 0
        saveProcess("running", next)
        SubjectLife.started(role, "梦境活动")
        var cursor = next
        var last = if (cursor > 0) store.activityRead(role, "dream_step", "${work.id}:${cursor - 1}")?.optString("text").orEmpty() else ""
        try {
            val scheduledEnd = rest.getJSONObject("content").optLong("until")
            val remaining = if (scheduledEnd > 0) (scheduledEnd - System.currentTimeMillis()).coerceAtLeast(1) else 600_000L
            withTimeout(minOf(600_000L, remaining)) {
                while (cursor < steps * 2) {
                    ensureActive()
                    if (SubjectLife(context).shouldPauseDream(role, System.currentTimeMillis())) {
                        throw CancellationException("已到休息结束条件或有约定的打扰；在梦境步骤边界保存并暂停")
                    }
                    val environment = cursor % 2 == 0
                    val instruction = if (environment)
                        "你只生成梦境环境当前一小段场景或对刚才选择的后果，不扮演主体作决定，不预写后面剧情或结局。允许愉快、恐惧、复杂混合、荒诞、嵌套或日常；不按心理分数固定安排结局。不发消息、不调用现实工具。只输出当前场景；需要自然结束时可用<dream_end/>。"
                    else "你是${card.name}，正在梦中经历刚呈现的场景。带着已提供的自身状态，自由回应、感觉或选择，不要求积极结论。只写当下可留存的经历与选择，不输出隐藏推理，不生成未来场景或现实工具命令。这里的行为完全是梦中模拟；不是新的现实证据。可用<dream_end/>选择结束。"
                    val input = JSONObject().put("plan", plan).put("previous_fragment", last)
                    // Keep the last scene/choice from this same participant as well as the
                    // other side's reply. Otherwise "I go through the door" loses which
                    // scene supplied the door when the environment produces consequences.
                    // Two bounded step reads, never the complete dream history.
                    if (cursor >= 2) input.put("previous_own_fragment",
                        requireNotNull(store.activityRead(role, "dream_step", "${work.id}:${cursor - 2}")).getString("text"))
                    if (!environment || cursor == 0) {
                        val available = budget - SubjectMaterialBudget.estimate(input.toString()).toInt() - 128
                        require(available > 0) { "上一段梦境与安排已占满材料预算，保留片段等待续接" }
                        // Reuse the shared complete-object selector inside this request's ONE
                        // budget. Do not add another full subject allowance to the dream text.
                        input.put(if (environment) "initial_background" else "subject_material", SubjectMaterialBudget.select(seed, available))
                    }
                    check(SubjectMaterialBudget.estimate(input.toString()) <= budget) {
                        "梦境当前片段超出共享材料预算，保存后暂停；可明确恢复或醒后理解，不截断原文"
                    }
                    val stepId = "${work.id}:$cursor"
                    val oldStep = store.activityRead(role, "dream_step", stepId)
                    check(oldStep == null || oldStep.optString("status") == "retry_authorized") {
                        "此步骤结果未确认；不能盲重放，先明确恢复选择"
                    }
                    fun saveStep(text: String, status: String) = store.activityWrite(role, "dream_step", stepId, work.id) { old ->
                        (old ?: JSONObject().put("id", stepId).put("parent", work.id).put("owner", role)
                            .put("at", System.currentTimeMillis()).put("actor", if (environment) "environment" else "subject")
                            .put("reality", "DREAM_NOT_REALITY").put("input_hash", InnerStructure.hash(input))
                            .put("input", input).put("step", cursor))
                            .put("text", text).put("status", status)
                    }
                    saveStep("", "requested")
                    var latestPartial = ""
                    var savedCharacters = 0
                    val output = try {
                        actual.generate(instruction, input.toString()) { partial ->
                            latestPartial = ChatUtils.removeThinkingContent(partial)
                            // Bounded paragraph checkpoints, not a full archive version per token.
                            val paragraphEnd = latestPartial.lastIndexOf('\n') + 1
                            if (paragraphEnd - savedCharacters >= 512) withContext(NonCancellable + Dispatchers.IO) {
                                saveStep(latestPartial.substring(0, paragraphEnd), "partial")
                                savedCharacters = paragraphEnd
                            }
                        }
                    } catch (failure: Throwable) {
                        if (latestPartial.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) { saveStep(latestPartial, "partial") }
                        throw failure
                    }
                    val visible = ChatUtils.removeThinkingContent(output).trim()
                    require(visible.isNotBlank()) { "梦境步骤未返回可保存内容" }
                    saveStep(visible, "completed")
                    cursor++
                    saveProcess("running", cursor)
                    last = visible
                    if (visible.contains("<dream_end/>")) break
                }
            }
            saveProcess("completed", cursor)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { saveProcess(if (failure is CancellationException) "interrupted" else "failed", cursor) }
            throw failure
        } finally {
            SubjectLife.stopped(role)
            if (service != null) EnhancedAIService.releaseChatInstance(key)
        }
    }

    companion object {
        fun materials(context: Context, role: String): List<JSONObject> {
            val store = CompanionStore(context)
            return store.activityQueuePage(role, "dream_process", listOf("pending", "ready"),
                (InternalStateStore(context).read(role).optLong("material_query_cursor") %
                    ((store.activityCount(role, "dream_process", "pending") + store.activityCount(role, "dream_process", "ready") + 1) / 2).coerceAtLeast(1) * 2).toInt())
                .take(2).map { session ->
                val nextIndex = session.optInt("next_step")
                val partial = store.activityRead(role, "dream_step", "${session.getString("id")}:$nextIndex")
                    ?.takeIf { it.optString("text").isNotBlank() }
                val fragment = partial ?: if (nextIndex > 0) store.activityRead(role, "dream_step", "${session.getString("id")}:${nextIndex - 1}") else null
                JSONObject().put("id", "material:dream_process:${session.getString("id")}:${session.getInt("version")}")
                    .put("type", "dream_not_reality").put("owner", role).put("author", role).put("at", session.getLong("at"))
                    .put("text", JSONObject().put("process_status", session.getString("status"))
                        .put("fragment_status", fragment?.optString("status") ?: "not_recorded")
                        .put("fragment", fragment?.optString("text")?.take(800) ?: JSONObject.NULL)
                        .put("instruction", "这是实际生成并参与的梦中片段，不是现实经历。醒后可写<waking_note>自然随记</waking_note>，允许记不清或只有余波，不补造故事。完整过程按dream_step分页读取。").toString())
                    .put("status", "retrieved").put("not_new_occurrence", true).put("subject_private_material", true)
                    .put("locator", JSONObject().put("kind", "dream_process").put("id", session.getString("id")).put("version", session.getInt("version")))
            }
        }
    }
}
