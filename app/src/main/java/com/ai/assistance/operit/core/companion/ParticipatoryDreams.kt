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

    suspend fun run(work: SubjectLife.Work, model: Model? = null, environmentModel: Model? = null,
                    random: kotlin.random.Random = kotlin.random.Random.Default) {
        val role = work.role
        val rest = requireNotNull(SubjectLife(context).rest(role))
        check(rest.getJSONObject("content").getString("state") == "resting")
        check(!InternalStateStore(context).requireRecipient(role, work.chat)) { "梦境只能在本助手私域运行" }
        val previous = store.activityRead(role, "dream_process", work.id)
        check(previous == null || previous.optString("status") == "resume_authorized") {
            "此梦已开始；仅明确恢复可以续接，不自动重复调用"
        }
        val plan = previous?.getJSONObject("plan") ?: JSONObject(work.text)
        val steps = plan.getInt("steps")
        require(steps in 1..6)
        val budget = InternalStateStore(context).read(role).optInt("material_budget_tokens", SubjectMaterialBudget.DEFAULT_TOKENS)
        fun saveProcess(status: String, next: Int, reason: String? = null) = store.activityWrite(role, "dream_process", work.id, "pending") { old ->
            (old ?: JSONObject().put("id", work.id).put("owner", role).put("source_chat", work.chat)
                .put("at", System.currentTimeMillis()).put("plan", plan).put("rest_version", rest.getInt("version"))
                .put("rest_session", rest.getJSONObject("content").optString("session_id"))
                .put("reality", "DREAM_NOT_REALITY")).put("status", status).put("next_step", next)
                .put("parent", "pending").apply { if (reason != null) put("end_reason", reason) }
        }
        var cursor = previous?.optInt("next_step") ?: 0
        saveProcess("running", cursor)
        SubjectLife.started(role, "梦境活动")
        val keys = mutableSetOf<String>()
        var failureStatus = "failed"
        try {
            val card = CharacterCardManager.getInstance(context).getCharacterCard(role)
            val subjectBinding = if (card.chatModelBindingMode == CharacterCardChatModelBindingMode.FIXED_CONFIG)
                DreamSettings.Binding(requireNotNull(card.chatModelConfigId), card.chatModelIndex)
            else FunctionalConfigManager(context).getConfigMappingForFunction(FunctionType.CHAT).let { DreamSettings.Binding(it.configId, it.modelIndex) }
            val selectedEnvironment = DreamSettings(context).read(role)
            val environmentBinding = if (selectedEnvironment == null) subjectBinding else selectedEnvironment
            suspend fun channel(binding: DreamSettings.Binding, actor: String): Model {
                val selected = requireNotNull(ModelConfigManager(context).getModelConfig(binding.configId)) { "梦境所选模型配置不存在，请重新选择" }
                require(binding.index in com.ai.assistance.operit.data.model.getModelList(selected.modelName).indices) { "梦境所选模型编号已失效，请重新选择" }
                val key = "dream:$role:${work.id}:$actor"
                keys += key
                val service = EnhancedAIService.getChatInstance(context, key)
                return Model { system, input, partial ->
                    check(SubjectMaterialBudget.estimate(system + input) + selected.maxTokens <= (selected.contextLength * 1024).toLong()) {
                        "梦境请求超出所选模型窗口，未发送；已有片段仍保留"
                    }
                    service.callFunctionModel(FunctionType.CHAT,
                        listOf(PromptTurn(PromptTurnKind.SYSTEM, system), PromptTurn(PromptTurnKind.USER, input)),
                        chatModelConfigIdOverride = binding.configId, chatModelIndexOverride = binding.index,
                        stream = true, maximumOutputCharacters = 12000, onOutput = partial)
                }
            }
            val participant = model ?: channel(subjectBinding, "subject")
            val environment = environmentModel ?: model ?: channel(environmentBinding, "environment")
            var session = requireNotNull(store.activityRead(role, "dream_process", work.id))
            // Persist each draw before gathering material. A crash cannot secretly reroll it.
            if (!session.has("design")) {
                val design = if (previous == null) DreamDesign.draw(plan, random) else JSONObject()
                    .put("version", 0).put("legacy_continuation", true).put("basis", "inner_state")
                    .put("themes", org.json.JSONArray(listOf(plan.optString("theme"), plan.optString("tone")).filter { it.isNotBlank() }))
                    .put("may_change_during_dream", true).put("not_a_sequence", true)
                session = store.activityWrite(role, "dream_process", work.id, "pending") { requireNotNull(it).put("design", design) }
            }
            val design = session.getJSONObject("design")
            val interactions = design.optInt("interactions", steps)
            require(interactions in 1..steps)
            if (!session.has("ingredients")) {
                val sections = InnerStructureContext.prepare(context, role, work.chat, false, true, input = "梦境当前状态")
                val seed = JSONObject(sections.state.substringAfter("\n[Internal State 本轮名录与选择]\n"))
                val materials = DreamMaterials.sample(context, role, work.chat, random)
                val restContent = rest.getJSONObject("content")
                val actions = SubjectActions.materials(context, role, work.chat).filter { it.optLong("at") <= restContent.optLong("started_at", Long.MAX_VALUE) }
                    .take(2).map { row ->
                        DreamDesign.excerpt(row.getString("id"), "prior_action", row.getString("text"), row.getJSONObject("locator"), 280)
                    }
                val ingredients = DreamDesign.select(design, seed, materials + actions, (budget - 512).coerceAtLeast(256),
                    random, restContent.optJSONObject("dream_stimulus"))
                session = store.activityWrite(role, "dream_process", work.id, "pending") {
                    requireNotNull(it).put("ingredients", ingredients).put("materials_selected_at", System.currentTimeMillis())
                }
            }
            val ingredients = session.getJSONObject("ingredients")
            var last = if (cursor > 0) requireNotNull(store.activityRead(role, "dream_step", "${work.id}:${cursor - 1}")).getString("text") else ""
            val scheduledEnd = rest.getJSONObject("content").optLong("until")
            val remaining = if (scheduledEnd > 0) (scheduledEnd - System.currentTimeMillis()).coerceAtLeast(1) else 600_000L
            var endReason = if (design.has("interactions")) "drawn_rhythm_reached" else "planned_steps_reached"
            withTimeout(minOf(600_000L, remaining)) {
                while (cursor < interactions * 2) {
                    ensureActive()
                    if (SubjectLife(context).shouldPauseDream(role, System.currentTimeMillis()))
                        throw CancellationException("已到休息结束条件或有约定的打扰；在梦境步骤边界保存并暂停")
                    val isEnvironment = cursor % 2 == 0
                    val actor = if (isEnvironment) "environment" else "subject"
                    val input = JSONObject().put("dream_design", design).put("previous_fragment", last)
                    design.optJSONArray("pace")?.let { input.put("pace", it.getString(cursor / 2)) }
                    if (cursor >= 2) input.put("previous_own_fragment",
                        requireNotNull(store.activityRead(role, "dream_step", "${work.id}:${cursor - 2}")).getString("text"))
                    // Shared budget: previous fragments and both material lanes count together.
                    val available = budget - SubjectMaterialBudget.estimate(input.toString()).toInt() - 96
                    require(available > 0) { "梦境片段已占满材料预算，保留原文等待明确续接" }
                    val selected = DreamDesign.fit(ingredients, available)
                    input.put(if (isEnvironment) "dream_ingredients" else "subject_material", selected)
                    if (!isEnvironment) input.put("subject_name", card.name)
                    check(SubjectMaterialBudget.estimate(input.toString()) <= budget) { "梦境材料超出共享预算，原文保留，未发送" }
                    val stepId = "${work.id}:$cursor"
                    val oldStep = store.activityRead(role, "dream_step", stepId)
                    check(oldStep == null || oldStep.optString("status") == "retry_authorized") { "此步骤结果未确认，须先明确续接选择" }
                    fun saveStep(text: String, status: String, raw: String? = null) = store.activityWrite(role, "dream_step", stepId, work.id) { old ->
                        (old ?: JSONObject().put("id", stepId).put("parent", work.id).put("owner", role)
                            .put("at", System.currentTimeMillis()).put("actor", actor).put("step", cursor)
                            .put("reality", "DREAM_NOT_REALITY")).put("input_hash", InnerStructure.hash(input)).put("input", input)
                            .put("protocol_version", 1).put("model_binding", (if (isEnvironment) environmentBinding else subjectBinding).json())
                            .put("text", text).put("status", status).apply {
                                if (raw != null) put("raw_response", raw) else if (status == "requested") remove("raw_response")
                            }
                    }
                    saveStep("", "requested")
                    var latest = ""
                    var checkpoint = 0
                    val output = try {
                        (if (isEnvironment) environment else participant).generate(DreamResponse.instruction(isEnvironment, card.name), input.toString()) { partial ->
                            latest = ChatUtils.removeThinkingContent(partial)
                            if (latest.length - checkpoint >= 512) withContext(NonCancellable + Dispatchers.IO) {
                                saveStep("", "partial", latest); checkpoint = latest.length
                            }
                        }
                    } catch (failure: Throwable) {
                        if (latest.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) { saveStep("", "partial", latest) }
                        throw failure
                    }
                    val visible = ChatUtils.removeThinkingContent(output).trim()
                    val response = try { DreamResponse.parse(visible, actor) } catch (failure: DreamResponse.Invalid) {
                        failureStatus = "invalid_response"; saveStep("", failureStatus, visible); throw failure
                    }
                    if (response.outcome == "unavailable") {
                        failureStatus = "refused"; saveStep("", failureStatus, visible)
                        throw IllegalStateException("梦境模型未参与本步；原始说明已保存，未计作梦境完成")
                    }
                    saveStep(response.text, "completed", visible)
                    store.activityWrite(role, "dream_process", work.id, "pending") {
                        requireNotNull(it).put("index_text", response.text.take(120))
                    }
                    cursor++
                    saveProcess("running", cursor)
                    last = response.text
                    if (response.outcome == "end") { endReason = "${actor}_ended"; break }
                }
            }
            saveProcess("completed", cursor, endReason)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                saveProcess(if (failure is CancellationException) "interrupted" else failureStatus, cursor, "not_completed")
            }
            throw failure
        } finally {
            SubjectLife.stopped(role)
            keys.forEach { EnhancedAIService.releaseChatInstance(it) }
        }
    }

    companion object {
        fun materials(context: Context, role: String, input: String = "", chat: String = ""): List<JSONObject> {
            val data = InternalStateStore(context).read(role)
            return SubjectRecall.selected(data, "dream_process", input, chat, data.optLong("material_query_cursor") * 2)
                .take(2).map { DreamShelf.index(it) }
        }
    }
}
