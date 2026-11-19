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
    private fun key(role: String) = SubjectStateBoundary.scope(role, "internal-state")
    private fun failureKey(role: String) = "isr-failure:$role"

    /** Stores only safe failure metadata. Neither report bodies nor personal memories enter logs. */
    internal fun noteReportFailure(frame: InternalStateFrame, failure: Exception, report: JSONObject? = null): InternalStateReportIssue {
        val issue = InternalStateReportDiagnostics.issue(failure, "report_commit")
        store.update(failureKey(frame.roleId)) { state ->
            state.put("status", "unresolved").put("code", issue.code).put("stage", issue.stage)
                .put("path", issue.path).put("rule", issue.rule)
                .put("hint", issue.hint)
                .put("frame_id", frame.id).put("frame_revision", frame.revision)
                .put("state_version", frame.stateVersion).put("at", System.currentTimeMillis())
        }
        if (issue.rule == "UNKNOWN_CATEGORY" && report != null && !recipientScope(frame.roleId, frame.chatId)) {
            val index = issue.stage.substringAfter('[', "").substringBefore(']').toIntOrNull()
            val draft = index?.let { report.optJSONArray("episodes")?.optJSONObject(it) }
            if (draft?.optString("op") == "appraise") EpisodeRepair(context).save(frame, draft)
        }
        return issue
    }

    private fun failureNotice(role: String): JSONObject? {
        val saved = store.read(failureKey(role))
        if (saved.optString("status") != "unresolved") return null
        return JSONObject().put("code", saved.optString("code").take(36))
            .put("stage", saved.optString("stage").take(40))
            .put("path", saved.optString("path").take(75))
            .put("hint", saved.optString("hint"))
            .put("frame_revision", saved.optLong("frame_revision"))
            .put("at", saved.optLong("at"))
            .put("state", "previous_report_not_adopted")
    }

    private fun update(role: String, source: String, change: (JSONObject) -> Unit) =
        store.updateSubject(role, "internal-state", source, change)
    fun enabled(role: String?) = !role.isNullOrBlank() && store.preferences.getBoolean("internal_state:$role", false)
    fun read(role: String) = store.read(key(role))

    internal fun concernStatus(role: String, offset: Int = 0): JSONObject {
        val data = read(role)
        return SubjectConcerns.page(data, offset).put("configuration", SubjectConcerns.config(data))
    }

    /** User-initiated common setup has its own provenance, never a fabricated model report.
     * Later changes to these meanings go through the existing life report/version rules. */
    internal fun configureConcerns(role: String, settings: JSONObject, initial: JSONArray? = null): JSONObject {
        check(com.ai.assistance.operit.data.preferences.AssistantScope.callerId() == null) { "助手配置须走轮末attention_config" }
        require(enabled(role)) { "请先开启这位助手的内部状态" }
        update(role, "user_concern_setup") { data ->
            if (initial != null) check(!SubjectConcerns.config(data).getBoolean("initialized")) { "首次设置已经完成；后续由明确变更承接，不覆盖原文。" }
            SubjectConcerns.configure(data, settings)
            if (initial != null) {
                val now = System.currentTimeMillis()
                val frame = InternalStateFrame("concern-setup:" + UUID.randomUUID(), role,
                    AssistantWindowRouter.chat(context, role).orEmpty(), InternalStateEngine.semanticRevision(data), now,
                    emptySet(), emptyMap(), data.optLong("sequence"))
                for (i in 0 until initial.length()) {
                    val draft = initial.getJSONObject(i)
                    SubjectContracts.fields(draft, setOf("text", "attention"))
                    SubjectContracts.text(draft, "text")
                    val id = "concern-" + UUID.randomUUID()
                    val content = SubjectConcerns.prepare(null, JSONObject().put("kind", "direction").put("id", id)
                        .put("text", draft.getString("text")).put("attention", draft.getJSONObject("attention"))
                        .put("scope", "首次共同设置").put("uncertainty", "来自用户初始设置，后续由主体理解和发展")
                        .put("status", "active").put("evidence", JSONArray()), frame)
                        .put("bindings", JSONObject()).put("not_a_plan", true).put("not_a_new_occurrence", true)
                        .put("subject_private_material", true).put("reality", "USER_INITIAL_SETTING").put("original_source", "user_initial_setting")
                    val row = JSONObject().put("storage_kind", "direction").put("id", id).put("parent", "private")
                        .put("owner", role).put("source_chat", frame.chatId).put("version", 1).put("frame_id", frame.id)
                        .put("at", now).put("source", "user_initial_setting").put("content", content)
                    CategoryEvents.append(data, frame, row)
                    SubjectConcerns.index(data, frame, row)
                }
                data.getJSONObject("concern_settings").put("initialized", true)
            }
            SubjectConcerns.validateCapacity(data)
        }
        return concernStatus(role)
    }

    /** An idempotent handoff. Valid old rules keep their original scope and authority;
     * mapped categories are aliases, and no candidate is automatically adopted. */
    private fun ensureFoundation(role: String) {
        if (!enabled(role)) return
        if (read(role).optJSONObject("foundation_state")?.optInt("schema") == InnerFoundations.SCHEMA) return
        update(role, "foundation_handoff") { data ->
            InnerFoundations.install(data, System.currentTimeMillis())
        }
    }

    suspend fun openEstablishedWindow(role: String): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        check(com.ai.assistance.operit.data.preferences.AssistantScope.callerId() == null) { "承接窗口只能由用户主动开启" }
        require(enabled(role)) { "请先启用该助手的内部状态" }
        var result = JSONObject()
        update(role, "user_open_established_window") { result = EstablishedContent.open(it, role, System.currentTimeMillis()) }
        result
    }

    suspend fun establishedStatus(role: String): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        EstablishedContent.status(read(role), System.currentTimeMillis())
    }

    /** Direct entry and streamed import submit immutable candidates, never adopted values.
     * Stable submission IDs checkpoint each item; rerunning an interrupted import reuses the
     * same receipt, including after its candidate was adopted or discarded. */
    suspend fun submitEstablishedContent(role: String, chat: String, submissionId: String, content: JSONObject,
                                         original: String? = null, sourceLabel: String? = null,
                                         formedAt: Long? = null, replaces: String? = null,
                                         previousHash: String? = null, expectedTargetVersion: Int? = null): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        check(com.ai.assistance.operit.data.preferences.AssistantScope.callerId() == null) { "助手提交应走正常报告候选入口" }
        requireRecipient(role, chat)
        require(enabled(role) && !recipientScope(role, chat))
        require(submissionId.isNotBlank() && submissionId.length <= 180)
        val id = InnerStructure.hash("$role:$submissionId")
        val body = JSONObject().put("record", content).put("original", original ?: JSONObject.NULL)
            .put("source_label", sourceLabel ?: JSONObject.NULL).put("formed_at", formedAt ?: JSONObject.NULL)
            .put("replaces", replaces ?: JSONObject.NULL).put("previous_hash", previousHash ?: JSONObject.NULL)
            .apply { if (expectedTargetVersion != null) put("expected_target_version", expectedTargetVersion) }
        val digest = InnerStructure.hash(body)
        var result = JSONObject()
        val objects = CharacterCardManager.getInstance(context).getAllCharacterCards().map { it.id }.toSet() + "user"
        update(role, "user_established_submission") { data ->
            val saved = CategoryEvents.row(data, "inner_pending", id)?.getJSONObject("value")
            if (saved != null) {
                require(saved.optString("submission_digest") == digest) { "提交标识已存在且内容不同；改写请作为新候选版本提交" }
                result = JSONObject().put("candidate_id", id).put("saved", true).put("replayed", true)
            } else {
                val now = System.currentTimeMillis()
                val refs = JSONArray()
                if (original != null) {
                    require(original.isNotBlank() && !sourceLabel.isNullOrBlank()) { "原文路径需要提供原文和实际来源说明" }
                    val source = "import-source:$id"
                    // A supplied document is an attributed user submission, not a new lived
                    // event and not a trigger for the legacy numerical emotion rules.
                    InternalStateEngine.record(data, source, "user_edit", chat, original, JSONArray(), now)
                    InternalStateEngine.rows(data, "events").last { it.getString("id") == source }
                        .put("inner_source", original).put("provided_source", sourceLabel)
                        .put("original_evidence_verified", false).put("formed_at", formedAt ?: JSONObject.NULL)
                    refs.put(source)
                }
                val target = content.getString("id")
                val previous = replaces?.let { requireNotNull(InnerStructure.candidate(data, it)) { "候选已改变或撤回" } }
                require(previous == null || InnerStructure.hash(previous) == previousHash) { "候选已被修改，请重新打开全文" }
                val version = InnerStructure.record(data, target)?.getInt("version") ?: 0
                require(expectedTargetVersion == null || version == expectedTargetVersion) { "已采用内容已变化，请重新读取当前全文后提出修订" }
                val frame = InternalStateFrame(id = "submission:$id", roleId = role, chatId = chat,
                    revision = InternalStateEngine.semanticRevision(data), at = now,
                    eventIds = (0 until refs.length()).map { refs.getString(it) }.toSet(), definitionVersions = emptyMap(),
                    eventSequence = data.optLong("sequence"), innerVersions = mapOf(target to version), innerObjects = objects,
                    innerPending = if (previous == null) emptyMap() else mapOf(requireNotNull(replaces) to requireNotNull(previousHash)))
                val action = JSONObject().put("op", "propose").put("id", id).put("record", content)
                    .put("expected_version", version).put("origin", if (original == null) EstablishedContent.ORIGIN else "user_proposal")
                    .put("evidence", refs).put("reason", if (original == null) "恢复声明为已有确定的内容；提交时间不代表形成时间" else "用户提供原文和来源，请按实际材料理解")
                if (original == null) action.put("formed_at", formedAt ?: JSONObject.NULL)
                if (replaces != null) action.put("replaces", replaces)
                InnerStructure.apply(data, frame, JSONArray().put(action), now)
                val pending = InnerRows(data, true, frame)
                val candidate = pending.getJSONObject(id).put("submission_digest", digest)
                pending.put(id, candidate)
                data.put("semantic_revision", Math.addExact(InternalStateEngine.semanticRevision(data), 1L))
                result = JSONObject().put("candidate_id", id).put("saved", true).put("replayed", false)
                    .put("notice", "已保存候选；后续正常请求实际读取后才能采用")
            }
        }
        result
    }

    suspend fun withdrawCandidate(role: String, chat: String, id: String, expectedHash: String): JSONObject =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(com.ai.assistance.operit.data.preferences.AssistantScope.callerId() == null)
            check(enabled(role) && !requireRecipient(role, chat))
            update(role, "user_candidate_withdrawal") { data ->
                val prior = requireNotNull(InnerStructure.candidate(data, id)) { "候选不存在或已不再待处理" }
                require(InnerStructure.hash(prior) == expectedHash) { "候选已变化，请重新打开全文" }
                val now = System.currentTimeMillis()
                val frame = InternalStateFrame(id = "user-withdraw:" + UUID.randomUUID(), roleId = role, chatId = chat,
                    revision = InternalStateEngine.semanticRevision(data), at = now, eventIds = emptySet(),
                    definitionVersions = emptyMap(), eventSequence = data.optLong("sequence"),
                    innerPending = mapOf(id to expectedHash))
                InnerStructure.apply(data, frame, JSONArray().put(JSONObject().put("op", "discard").put("id", id)
                    .put("reason", "用户撤回未采用候选；完整旧版与来源保留")), now)
                data.put("semantic_revision", Math.addExact(InternalStateEngine.semanticRevision(data), 1L))
            }
            JSONObject().put("withdrawn", id)
        }

    suspend fun materialBudget(role: String, chat: String, tokens: Int): JSONObject =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            requireRecipient(role, chat)
            require(tokens in SubjectMaterialBudget.DEFAULT_TOKENS..SubjectMaterialBudget.MAX_TOKENS)
            update(role, "material_budget_setting") { it.put("material_budget_tokens", tokens) }
            JSONObject().put("limit_tokens", tokens).put("exact", false)
        }

    /** Existing relationship editors submit attributed input; they do not directly adopt a view. */
    suspend fun relationshipProposal(role: String, caller: String?, value: JSONObject, chat: String): JSONObject =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val executing = com.ai.assistance.operit.data.preferences.AssistantScope.callerId()
            check(executing == null || caller == executing) { "助手不能冒用用户或另一助手的编辑来源" }
            SubjectStateBoundary.authorize(role, caller)
            val invocation = com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.currentToolRuntimeContext()?.hostInvocationId
            val id = "relation-proposal:" + (invocation?.let { "$it:${value.getString("to")}:${value.optString("operation", "save")}" }
                ?: UUID.randomUUID().toString())
            var savedValue = JSONObject(value.toString())
            var replayed = false
            update(role, if (caller == null) "user_edit" else "relationship_proposal") { data ->
                val prior = InternalStateEngine.rows(data, "events").firstOrNull { it.getString("id") == id }
                    ?: store.archivedStateRow(key(role), "events", id)
                if (prior != null) {
                    val original = JSONObject(prior.getString("inner_source"))
                    fun comparable(input: JSONObject) = JSONObject(input.toString()).apply { remove("updated_at") }
                    require(InnerStructure.hash(comparable(original)) == InnerStructure.hash(comparable(value))) { "同一关系操作重试内容不一致" }
                    savedValue = original
                    replayed = true
                    return@update
                }
                InternalStateEngine.record(data, id, if (caller == null) "user_relation_proposal" else "assistant_relation_proposal",
                    chat, "关系编辑建议；尚未采用：" + value.getString("relation"), JSONArray(), System.currentTimeMillis())
                InternalStateEngine.rows(data, "events").last { it.getString("id") == id }
                    .put("inner_source", JSONObject(value.toString()).toString()).put("recipient", role)
                    .put("author", caller ?: "user").put("source_chat", chat)
                data.put("semantic_revision", Math.addExact(InternalStateEngine.semanticRevision(data), 1))
            }
            savedValue.put("status", "proposal_recorded").put("event_id", id).put("replayed", replayed)
                .put("notice", "原文已保存为关系建议，尚未改变主体已采用的认识；通过正常内在候选流程采用")
        }

    /** Validate against host chat metadata, never the visible/selected assistant or model. */
    suspend fun requireRecipient(role: String, chat: String): Boolean {
        SubjectStateBoundary.authorize(role, com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        val metadata = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chat)) { "状态来源会话不存在" }
        val groupId = metadata.characterGroupId
        if (groupId.isNullOrBlank()) require(metadata.characterCardId == role) { "状态来源会话不属于当前助手" }
        else require(com.ai.assistance.operit.data.preferences.CharacterGroupCardManager.getInstance(context)
            .getCharacterGroupCard(groupId)?.members.orEmpty().any { it.characterCardId == role }) { "状态接收主体不是群成员" }
        return !groupId.isNullOrBlank()
    }

    /** Synchronous storage/tool adapters retain the executing assistant across the IO bridge.
     * Production callers already run these adapters on IO; never perform metadata IO in a state lock. */
    internal fun recipientScope(role: String, chat: String): Boolean = kotlinx.coroutines.runBlocking(
        kotlinx.coroutines.Dispatchers.IO + com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(
            com.ai.assistance.operit.data.preferences.AssistantScope.callerId())) { requireRecipient(role, chat) }

    suspend fun configure(role: String, enable: Boolean) {
        require(role.isNotBlank())
        if (enable) CharacterCardManager.getInstance(context).setToolSelected(role, "builtin", "companion_internal_state", true)
        // Disabled time is paused. Turning back on must not replay the unobserved interval.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (enabled(role) == enable) return@withContext
            update(role, "user_configuration") { data ->
                val now = System.currentTimeMillis()
                if (enable) InnerFoundations.install(data, now)
                data.optJSONObject("foundation_state")?.let { foundation ->
                    if (!enable) {
                        // Settle each actual old instance once, including session-local ones.
                        // Merely restoring the service is not a psychological wake or rest.
                        val definitions = InternalStateEngine.definitions(data).associateBy { it.getString("id") }
                        InternalStateEngine.rows(data, "instances").toList().forEach { instance ->
                            val definition = definitions[instance.getString("dimension")]
                            if (definition?.optBoolean("active") == true)
                                InternalStateEngine.advanceDimension(data, definition, instance.getString("scope"), now)
                        }
                        InnerFoundations.advance(data, now)
                        foundation.put("paused_at", now)
                    } else if (foundation.has("paused_at")) {
                        val paused = (now - foundation.getLong("paused_at")).coerceAtLeast(0)
                        foundation.put("paused_elapsed", Math.addExact(foundation.optLong("paused_elapsed"), paused))
                        val values = foundation.getJSONObject("drives")
                        for (kind in InnerFoundations.drives)
                            if (!values.getJSONObject(kind.id).has("adapter_id")) values.getJSONObject(kind.id).put("at", now)
                        InternalStateEngine.rows(data, "instances").forEach {
                            it.put("at", now).put("since", Math.addExact(it.optLong("since", now - paused), paused))
                        }
                        foundation.remove("paused_at")
                    }
                }
                if (!enable && data.optJSONObject("foundation_state") == null) {
                    InternalStateEngine.rows(data, "instances").forEach { it.put("at", now).put("since", now) }
                }
                // A frame prepared before disabling/re-enabling cannot later become current.
                data.put("semantic_revision", Math.addExact(InternalStateEngine.semanticRevision(data), 1L))
            }
            check(store.preferences.edit().putBoolean("internal_state:$role", enable).commit())
        }
    }

    fun event(role: String?, chat: String, id: String, source: String, detail: String, contactAt: Long? = null,
              author: String? = null) {
        if (!enabled(role)) return
        update(requireNotNull(role), source) {
            // Archived messages remain idempotent after compaction and process restart.
            if (InternalStateEngine.rows(it, "events").any { row -> row.getString("id") == id } ||
                store.archivedStateRow(key(role), "events", id) != null) return@update
            // These facts are interpreted in the main generation. Replaying old keyword rules
            // here would assign a second, contradictory subjective meaning before that report.
            InternalStateEngine.event(it, id, source, chat, detail, System.currentTimeMillis(),
                applyTriggers = source !in setOf("user_message", "assistant_reply", "wake", "assistant_action"), contactAt = contactAt)
            InternalStateEngine.rows(it, "events").last { row -> row.getString("id") == id }
                .put("recipient", role).put("author", author ?: JSONObject.NULL)
                .put("source_chat", chat)
        }
    }

    // Persistence runs on the IO pool: these were called on the main looper and caused the ANRs
    // in anr_reports (CompanionStore.read under InternalStateStore.message).
    suspend fun message(chat: String, message: ChatMessage) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        messageNow(chat, message)
    }
    private suspend fun messageNow(chat: String, message: ChatMessage) {
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
                if (message.sender == "user") "user_message" else "assistant_reply", spoken,
                contactAt = if (message.sender == "user") message.timestamp else null,
                author = if (message.sender == "user") "user" else actor)
        }
    }

    suspend fun tool(role: String?, chat: String?, name: String, action: String?, status: String?, success: Boolean,
                     invocationId: String) {
        if (chat == null || !enabled(role) || name == "companion_internal_state") return
        requireRecipient(requireNotNull(role), chat)
        require(invocationId.isNotBlank())
        val id = "tool:$invocationId"
        // Await persistence on IO: the next model round must see the completed tool's event.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            toolNow(role, chat, id, name, action, status, success)
        }
    }
    private fun toolNow(role: String?, chat: String, id: String, name: String, action: String?, status: String?, success: Boolean) {
        if (!enabled(role)) return
        // Both facts belong to one completed tool: one read/compact/write, preserving event order.
        update(requireNotNull(role), "tool_result") { data ->
            val previous = InternalStateEngine.rows(data, "events").firstOrNull { it.getString("id") == id }
                ?: store.archivedStateRow(key(role), "events", id)
            if (previous != null) {
                require(previous.getString("source") == if (success) "tool_success" else "tool_failure") { "同一工具结果标识发生冲突" }
                require(previous.getString("chat") == chat && previous.getString("detail") == "$name/${action.orEmpty()}".take(240)) { "工具结果来源发生冲突" }
                require(previous.opt("tool_status") == (status ?: JSONObject.NULL)) { "工具结果状态发生冲突" }
                return@update
            }
            InternalStateEngine.event(data, id, if (success) "tool_success" else "tool_failure",
                chat, "$name/${action.orEmpty()}", System.currentTimeMillis(), applyTriggers = false)
            InternalStateEngine.rows(data, "events").last { it.getString("id") == id }
                .put("recipient", role).put("author", role).put("source_chat", chat)
                .put("tool_status", status ?: JSONObject.NULL)
            val source = InternalStateEvents.toolSource(name, action, status, success)
            if (source != null) {
                InternalStateEngine.event(data, "$id:$source", source,
                    chat, "$name/${action.orEmpty()}/${status.orEmpty()}", System.currentTimeMillis(),
                    applyTriggers = false)
                InternalStateEngine.rows(data, "events").last { it.getString("id") == "$id:$source" }
                    .put("recipient", role).put("author", role).put("source_chat", chat).put("tool_event", id)
            }
        }
    }

    fun snapshot(role: String, chat: String, persist: Boolean): JSONObject {
        ensureFoundation(role)
        val now = System.currentTimeMillis()
        return if (persist && enabled(role)) update(role, "time") {
            InternalStateEngine.advance(it, chat, now)
            InnerFoundations.advance(it, now)
        } else read(role).also { if (enabled(role)) {
            InternalStateEngine.advance(it, chat, now)
            InnerFoundations.advance(it, now)
        } }
    }

    fun evolve(role: String, chat: String, operation: String, id: String, expected: Int,
               definition: JSONObject?, target: String?, reason: String, evidence: List<String>) {
        check(enabled(role)) { "内部状态未开启；AI不能开启总开关" }
        ensureFoundation(role)
        if (operation == "create") {
            requireNotNull(definition) { "新建状态需要完整定义" }
            require(definition.has("kind") && definition.getString("kind") in InternalStateEngine.kinds) {
                "新建状态必须明确 kind：drive（驱力）、sentiment（情感）、emotion（情绪）或 psychology（心理）"
            }
            InternalStateTimeRules.requireDefined(definition)
        }
        update(role, "definition_tool") {
            if (operation == "create") InnerFoundations.validateNewDefinition(it, requireNotNull(definition))
            if (operation == "revise") InnerFoundations.validateLegacyRevision(it, requireNotNull(definition))
            val now = System.currentTimeMillis()
            val prior = InnerFoundations.alias(it, id)?.let { key -> InnerFoundations.member(it, key, now).getDouble("current_value") }
            restoreEvidence(it, role, evidence)
            InternalStateEngine.evolve(it, operation, id, expected, definition, target, reason, evidence, chat, System.currentTimeMillis())
            InnerFoundations.reconcileAdapter(it, id, prior, now)
        }
    }

    /** Local state-detail editor; deliberately absent from the assistant's tool contract. */
    fun updateFromUser(role: String, chat: String, id: String, expectedVersion: Int, expectedRevision: Long,
                       changes: JSONObject, reason: String, reveal: Boolean): JSONObject {
        require(role.isNotBlank() && chat.isNotBlank())
        lateinit var observation: JSONObject
        check(com.ai.assistance.operit.data.preferences.AssistantScope.callerId() == null) { "用户编辑入口不能由助手调用" }
        ensureFoundation(role)
        update(role, "user_edit") { data ->
            observation = InternalStateUserEdits.apply(data, chat, id, expectedVersion, expectedRevision,
                changes, reason, reveal, System.currentTimeMillis(), advanceTime = enabled(role))
        }
        return observation
    }

    /** The frame comes from host request metadata, never from role/chat fields in model output. */
    fun submit(frame: InternalStateFrame, report: JSONObject, currentInnerSources: Map<String, String>? = null): JSONObject {
        require(frame.roleId.isNotBlank() && frame.chatId.isNotBlank())
        requireStateReport(enabled(frame.roleId), InternalStateReportFailure.DISABLED)
        if ((report.optJSONArray("decisions")?.length() ?: 0) > 0 ||
            (report.optJSONArray("action_feedback")?.length() ?: 0) > 0 || report.has("life") || report.has("attention_config") || SubjectRestDreams.requested(report)) {
            require(!recipientScope(frame.roleId, frame.chatId)) { "私人决定和理解仅在本助手私聊采用" }
        }
        lateinit var receipt: JSONObject
        val episodes = report.optJSONArray("episodes") ?: JSONArray()
        val episodeVersions = (0 until episodes.length()).mapNotNull { index ->
            episodes.optJSONObject(index)?.takeIf { it.optString("op") == "appraise" }
                ?.let { it.optString("id") to it.optInt("expected_version", -1) }
        }.toMap()
        val episodeIds = episodeVersions.keys.toList()
        val repairs = if (episodeIds.isEmpty() || recipientScope(frame.roleId, frame.chatId)) emptyMap()
            else EpisodeRepair(context).sources(frame.roleId, frame.chatId, episodeIds)
        val saved = update(frame.roleId, "state_report") { data ->
            requireStateReport(enabled(frame.roleId), InternalStateReportFailure.DISABLED)
            store.archivedStateRow(key(frame.roleId), "semantic_reports", frame.id)?.let {
                val replayData = InternalStateDocument.copy(data)
                replayData.put("semantic_reports", JSONArray().put(it))
                receipt = InternalStateReports.apply(replayData, frame, report, System.currentTimeMillis())
                return@update
            }
            // Old receipts still use the original digest validation. A new report must not
            // bypass conflict checks if intervening objective changes have already been archived.
            val replay = InternalStateEngine.rows(data, "semantic_reports").any { it.getString("frame_id") == frame.id }
            if (!replay && report.has("inner") && report.getJSONArray("inner").length() > 0) {
                requireNotNull(currentInnerSources) { "内在候选须从宿主来源核验入口提交" }
                require(frame.innerSources == currentInnerSources) { "人格配置或关系来源已改变；本次候选报告不采用" }
            }
            requireStateReport(replay || frame.eventSequence >= data.optLong("archived_event_sequence"), InternalStateReportFailure.CONFLICT)
            restoreEvidence(data, frame.roleId, frame.eventIds.toList())
            restoreEvidence(data, frame.roleId, InnerStructure.evidenceToRestore(data, report.optJSONArray("inner") ?: JSONArray()))
            restoreEvidence(data, frame.roleId, CategoryEvents.evidence(if (report.has("episodes")) report.getJSONArray("episodes") else JSONArray()))
            receipt = InternalStateReports.apply(data, frame, report, System.currentTimeMillis(), repairs)
        }
        // A successful report resolves prior failure metadata without replaying old actions.
        try {
            EpisodeRepair(context).complete(frame.roleId, frame.chatId, episodeVersions)
            store.update(failureKey(frame.roleId)) { it.put("status", "resolved").put("resolved_at", System.currentTimeMillis()) }
        }
        catch (failure: Exception) {
            AppLogger.e("InternalState", "报告已提交，但诊断状态未更新", failure)
        }
        AppLogger.d("InternalState", "Accepted subject=${frame.roleId} chat=${frame.chatId} frame=${frame.id} read=${frame.stateVersion} saved=${SubjectStateBoundary.revision(saved)} semantic=${InternalStateEngine.semanticRevision(saved)}")
        return receipt
    }

    private fun restoreEvidence(data: JSONObject, role: String, ids: List<String>) {
        val rows = InternalStateEngine.rows(data, "events")
        val known = rows.map { it.getString("id") }.toSet()
        val restored = ids.distinct().filter { it !in known }.mapNotNull {
            store.archivedStateRow(key(role), "events", it)
        }
        if (restored.isNotEmpty()) data.put("events", JSONArray((rows + restored).sortedBy { it.getLong("sequence") }))
    }

    fun inspect(role: String, chat: String, offset: Int): JSONObject {
        val result = inspectLocal(role, chat, offset)
        return if (recipientScope(role, chat)) SubjectRestDreams.publicMaterial(result) else result
    }

    private fun inspectLocal(role: String, chat: String, offset: Int): JSONObject {
        require(offset >= 0)
        val data = snapshot(role, chat, true)
        val events = InternalStateEngine.rows(data, "events").asReversed()
        val versions = InternalStateEngine.rows(data, "versions").asReversed()
        val page = events.drop(offset).take(21).toMutableList()
        if (page.size < 21) page.addAll(store.archivedStateEvents(key(role),
            (offset - events.size).coerceAtLeast(0), 21 - page.size, events.map { it.getString("id") }.toSet()))
        val versionPage = versions.drop(offset).take(21).toMutableList()
        if (versionPage.size < 21) versionPage.addAll(store.archivedStateVersions(key(role),
            (offset - versions.size).coerceAtLeast(0), 21 - versionPage.size,
            versions.map { InternalStateArchive.lookup("versions", it) }.toSet()))
        val innerHistory = InternalStateEngine.rows(data, "inner_history").asReversed()
        val historyPage = innerHistory.drop(offset).take(9).toMutableList()
        if (historyPage.size < 9) historyPage.addAll(store.archivedInnerHistory(key(role),
            (offset - innerHistory.size).coerceAtLeast(0), innerHistory.map { it.getString("id") }.toSet()).take(9 - historyPage.size))
        return JSONObject().put("definitions", JSONArray(InternalStateEngine.definitions(data)))
            .put("inner", InnerStructure.page(data, offset))
            .put("psychological_events", CategoryEvents.page(data, "episode", offset))
            .put("category_focus", CategoryEvents.page(data, "focus", offset))
            .put("inner_history", JSONArray(historyPage.take(8)))
            .put("inner_history_next_offset", if (historyPage.size > 8) offset + 8 else JSONObject.NULL)
            .put("states", JSONArray(InternalStateEngine.snapshot(data, chat, System.currentTimeMillis())))
            .put("events", JSONArray(page.take(20))).put("versions", JSONArray(versionPage.take(20)))
            .put("last_selection", data.optJSONObject("last_selection") ?: JSONObject.NULL)
            .put("next_offset", if (page.size > 20 || versionPage.size > 20) offset + 20 else JSONObject.NULL)
    }

    fun inspectCategory(role: String, chat: String, kind: String, id: String?, offset: Int): JSONObject {
        val result = inspectCategoryLocal(role, chat, kind, id, offset)
        return if (recipientScope(role, chat)) SubjectRestDreams.publicMaterial(result) else result
    }

    private fun inspectCategoryLocal(role: String, chat: String, kind: String, id: String?, offset: Int): JSONObject {
        SubjectContracts.expect(offset >= 0, "offset", "PAGE_OFFSET", "分页起点须为非负整数。")
        if (kind == "contract") return SubjectContracts.query(id)
        if (kind == "episode_repair") {
            require(!recipientScope(role, chat)) { "待修正事件仅供本助手原私聊读取" }
            return EpisodeRepair(context).page(role, chat, id, offset)
        }
        if (kind in setOf("concerns", "concern_config")) {
            require(!recipientScope(role, chat)) { "便签仅在本助手私域读取" }
            val data = read(role)
            if (kind == "concern_config") return SubjectConcerns.config(data)
            if (id == null) return SubjectConcerns.page(data, offset).put("index_only", true)
            val parts = id.split(':', limit = 2)
            require(parts.size == 2 && parts[0] in LifeUnderstandings.kinds) { "详情id须为direction:ID或experience:ID" }
            val row = CategoryEvents.row(data, parts[0], parts[1])
            require(row == null || row.getJSONObject("content").has("attention")) { "该记录没有便签" }
            return JSONObject().put("item", row ?: JSONObject.NULL)
        }
        SubjectContracts.queryId(kind, id)
        if (kind in setOf("legacy_handoff", "foundation_history"))
            require(!recipientScope(role, chat)) { "旧状态承接与完整基础变化来源仅在本助手私域读取" }
        require(offset >= 0 && kind in setOf("foundation", "foundation_history", "legacy_handoff", "inner", "inner_family", "inner_pending", "inner_chunk", "inner_record_chunk", "state", "episode", "episode_category", "thought", "focus", "experience_link", "union", "category_versions", "source", "action", "action_versions", "decision", "feedback", "life_event", "life_activity", "dream_process", "dream_step") + SubjectRestDreams.kinds + ActivityNotes.kinds + LifeUnderstandings.kinds)
        if (kind in setOf("life_event", "life_activity", "dream_process", "dream_step")) {
            require(!recipientScope(role, chat)) { "生活与梦的过程只在本人私域读取" }
            return SubjectLife(context).page(role, kind, offset, id)
        }
        if (kind == "thought") {
            require(!recipientScope(role, chat)) { "念头仅在本助手私域读取" }
            return ThoughtPool.page(context, role, offset)
        }
        if (kind in ActivityNotes.kinds) {
            require(!recipientScope(role, chat)) { "活动和梦醒随记仅在本助手私域读取" }
            return ActivityNotes.page(store.context, role, kind, id, offset)
        }
        if (kind == "action") return SubjectActions.read(store.context, role, chat, id, offset)
        if (kind == "action_versions") {
            requireNotNull(id)
            // Authorize the current row before exposing any archived versions of this operation.
            SubjectActions.read(store.context, role, chat, id, 0)
            val rows = store.actionVersions(role, id, offset)
            return JSONObject().put("items", JSONArray(rows.take(8)))
                .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
        }
        if (kind in setOf("decision", "feedback") + SubjectRestDreams.kinds + LifeUnderstandings.kinds ||
            (kind == "category_versions" && (setOf("decision", "feedback", "private_time_config", "private_time_plan", "private_time_sleep", "private_time_essence") + SubjectRestDreams.privateKinds + LifeUnderstandings.kinds).any { id.orEmpty().startsWith("$it:") }))
              require(!recipientScope(role, chat)) { "私人决定和理解不可向群聊提供" }
        // Rest and dream archives have no implicit time evolution. Browsing them must
        // not project every drive and load all retained chat/state events first.
        val data = if (kind in SubjectRestDreams.kinds) store.readPrivateCategories(role) else snapshot(role, chat, false)
        if (kind == "foundation") {
            val familyOrId = requireNotNull(id) { "基础目录查询需要 family 或类别ID" }
            val now = System.currentTimeMillis()
            return if (familyOrId.startsWith("foundation:")) JSONObject()
                .put("item", InnerFoundations.member(data, familyOrId, now))
            else InnerFoundations.page(data, familyOrId, offset, now)
        }
        if (kind == "legacy_handoff") return if (id == null) InnerFoundations.legacyPage(data, offset, chat)
            else InnerFoundations.legacyDetail(data, id, chat)
        if (kind == "foundation_history") {
            requireNotNull(id) { "基础变化历史须指定类别ID" }
            val rows = store.categoryVersions(key(role), "foundation_change:$id", offset)
            return JSONObject().put("items", JSONArray(rows.take(8)))
                .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
        }
        if (kind == "episode_category") return CategoryEvents.page(data, "episode", offset, requireNotNull(id))
        if (kind == "inner_family") {
            val family = requireNotNull(id)
            require(family in InnerStructure.families)
            val rows = InnerRows(data, false)
            val count = rows.familyCount(family)
            return JSONObject().put("records", JSONArray(rows.familyPage(family, offset)))
                .put("count", count).put("next_offset", if (offset + 8 < count) offset + 8 else JSONObject.NULL)
        }
        if (kind == "inner_record_chunk") {
            require(!recipientScope(role, chat)) { "内在全文只在本助手私域读取" }
            return JSONObject().put("fragment", InnerReadProgress.recordFragment(data,
                requireNotNull(InnerStructure.record(data, requireNotNull(id))) { "已采用记录不存在" }, offset))
        }
        if (kind == "inner_chunk") {
            require(!recipientScope(role, chat)) { "候选全文只在本助手私域读取" }
            return JSONObject().put("fragment", InnerReadProgress.fragment(data,
                requireNotNull(InnerStructure.candidate(data, requireNotNull(id))) { "候选已采用或撤回" }, offset))
        }
        if (kind == "inner_pending") {
            require(!recipientScope(role, chat)) { "候选承接只在本人私域和私聊提供" }
            if (id != null) return JSONObject().put("item", InnerStructure.candidate(data, id) ?: JSONObject.NULL)
            val rows = InnerRows(data, true)
            return JSONObject().put("items", JSONArray(rows.page(offset)))
                .put("next_offset", if (rows.count() > offset + 8) offset + 8 else JSONObject.NULL)
        }
        if (kind == "inner") {
            if (id == null) return InnerStructure.page(data, offset)
            return JSONObject().put("item", InnerStructure.record(data, id) ?: JSONObject.NULL)
        }
        if (kind == "state") {
            if (id != null) {
                val exists = InternalStateEngine.definitions(data).any { it.getString("id") == id }
                return JSONObject().put("item", if (exists) InnerInteractions.stateMember(data, id, chat, System.currentTimeMillis()) else JSONObject.NULL)
            }
            val states = InternalStateEngine.snapshot(data, chat, System.currentTimeMillis())
            return JSONObject().put("items", JSONArray(states.drop(offset).take(8)))
                .put("next_offset", if (offset + 8 < states.size) offset + 8 else JSONObject.NULL)
        }
        if (kind == "source") {
            require(!id.isNullOrBlank())
            val found = InternalStateEngine.rows(data, "events").firstOrNull { it.getString("id") == id }
                ?: store.archivedStateRow(key(role), "events", id)
            return JSONObject().put("item", found ?: JSONObject.NULL)
        }
        if (kind == "category_versions") {
            require(!id.isNullOrBlank()) { "版本查询须给出 episode:ID 或 focus:类别ID" }
            if (id.startsWith("inner_pending:")) require(!recipientScope(role, chat)) { "候选历史仅在本人私域读取" }
            if (id.startsWith("union:")) {
                val union = CategoryEvents.row(data, "union", id.removePrefix("union:"))
                require(union?.optString("local_chat").orEmpty() in setOf("", chat)) { "该联合属于另一个窗口" }
            }
            val rows = store.categoryVersions(key(role), id, offset)
            return JSONObject().put("items", JSONArray(rows.take(8))).put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
        }
        if (kind == "experience_link") return CategoryEvents.page(data, kind, offset, id)
        if (kind == "union") {
            if (id != null) {
                val row = CategoryEvents.row(data, kind, id)
                require(row?.optString("local_chat").orEmpty() in setOf("", chat)) { "该联合属于另一个窗口" }
                return JSONObject().put("item", row ?: JSONObject.NULL)
            }
            val page = CategoryEvents.page(data, kind, offset)
            val rows = page.getJSONArray("items")
            for (i in rows.length() - 1 downTo 0) {
                if (rows.getJSONObject(i).optString("local_chat") !in setOf("", chat)) rows.remove(i)
            }
            return page
        }
        if (id == null) return CategoryEvents.page(data, kind, offset).apply {
            // Keep the published static-dream page intact and expose the other archive
            // alongside it, rather than copying process text into a second authority.
            if (kind == "dream") {
                put("items", JSONArray(getJSONArray("items").objects().map { row -> JSONObject(row.toString()).apply {
                    put("content", JSONObject(row.getJSONObject("content").toString()).apply { remove("content"); remove("source_bindings") })
                    put("index_only", true)
                } }))
                put("index_only", true)
                put("participatory_processes", SubjectLife(context).page(role, "dream_process", offset))
                put("process_retrieval", "get section=dream_process id=过程ID；get section=dream_step id=过程ID offset=0。两种梦均已留存，分别分页，拒答原文不是梦中经历。")
            }
        }
        val row = CategoryEvents.row(data, kind, id)
        return JSONObject().put("item", if (kind == "episode" && row != null)
            CategoryEvents.timeView(data, row, System.currentTimeMillis()) else row ?: JSONObject.NULL)
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
                capabilities: JSONObject = JSONObject(), currentInput: String = "", groupInput: Boolean = false,
                innerSources: Map<String, String> = emptyMap(), innerObjects: Set<String> = emptySet(),
                hostMaterials: List<JSONObject> = emptyList(), retrievedMaterials: List<JSONObject> = emptyList(),
                selectionInput: String = currentInput): RuntimeContextSections {
        require(actor.isNotBlank() && chat.isNotBlank())
        check(enabled(actor)) { "内部状态未开启" }
        ensureFoundation(actor)
        val now = System.currentTimeMillis()
        fun project(data: JSONObject): RuntimeContextSections {
            val innerRoot = InnerStructure.root(data)
            if (persist) InnerFoundations.advance(data, now)
            val innerCursor = innerRoot.optLong("selection_cursor")
            val requestId = if (persist) UUID.randomUUID().toString() else null
            val inputEventId = requestId?.let { "input:$it" }
            val source = if (wake || currentInput.isBlank()) "wake" else if (groupInput) "group_context" else "user_message"
            if (inputEventId != null) {
                // Record the received input even when it has no numeric changes. Its complete
                // original text is already in this request; the event supplies a real evidence ID.
                InternalStateEngine.event(data, inputEventId, source, chat,
                    currentInput.ifBlank { "本次自主继续判断，没有用户新输入" }, now, applyTriggers = false)
                InternalStateEngine.rows(data, "events").last { it.getString("id") == inputEventId }
                    .put("recipient", actor).put("author", JSONObject.NULL).put("source_chat", chat)
            } else {
                InternalStateEngine.advance(data, chat, now)
            }
            val selected = InternalStateSelection.select(data, chat, now, selectionInput,
                candidates(data, actor, chat, wake, capabilities, selectionInput), ThoughtPool.selected(context, actor, data.optLong("material_query_cursor")), inputEventId)
            var frame = requestId?.let {
                InternalStateFrame(id = "state-frame:$it", roleId = actor, chatId = chat,
                    revision = InternalStateEngine.semanticRevision(data), at = now,
                    eventIds = selected.eventIds, definitionVersions = InternalStateEngine.definitions(data).associate {
                        definition -> definition.getString("id") to definition.getInt("version")
                    }, eventSequence = data.getLong("sequence"), stateVersion = SubjectStateBoundary.revision(data),
                    innerVersions = emptyMap(), innerPending = emptyMap(),
                    foundationVersions = InnerFoundations.currentVersions(data),
                    innerSources = innerSources, innerObjects = innerObjects, innerGuard = InnerStructure.guard(data))
            }
            val concerns = if (groupInput) emptyList() else SubjectConcerns.selected(data)
            var payload = selected.payload
            // Only a short machine-readable failure notice from a prior turn can reappear.
            // It contains no rejected report text, no identity statements, and no retry command.
            if (!groupInput) {
                failureNotice(actor)?.let { payload.put("last_report_failure", it) }
                val repairs = EpisodeRepair(context).page(actor, chat, null, 0).getJSONArray("items")
                if (repairs.length() > 0) payload.put("pending_episode_repair", repairs.getJSONObject(0).getString("id"))
            }
            payload.put("material_selection_cursor", innerCursor)
            payload.put("foundation", InnerFoundations.material(data, selectionInput, innerCursor, now))
            payload.put("inner_structure", InnerStructure.material(data, selectionInput, innerCursor))
                .put("category_events", CategoryEvents.material(data, persist, now, selectionInput, chat))
                .put("unions", if (groupInput) JSONObject().put("restricted", true) else InnerInteractions.material(data, persist, chat, now, selectionInput))
                .put("inner_source_ids", JSONArray(innerSources.keys.take(16))).put("inner_source_count", innerSources.size)
                .put("inner_object_ids", JSONArray(innerObjects.sorted().take(32))).put("inner_object_count", innerObjects.size)
                .put("host_materials", JSONArray(hostMaterials))
                .put("retrieved_materials", JSONArray(if (groupInput) retrievedMaterials.filter {
                    // Only this group's own actual operations; never private cross-window material.
                    it.optString("type") == "actual_operation" && it.optString("owner") == actor &&
                        it.optJSONObject("locator")?.optString("parent") == "group:$chat"
                } else (ActionUnderstanding.materials(data, selectionInput, chat) +
                    concerns.map { SubjectConcerns.material(it).put("selection_reason", "STANDING_CONCERN") } +
                    LifeUnderstandings.materials(data, selectionInput, chat) + retrievedMaterials)
                    .distinctBy { it.getString("id") }))
            if (frame != null) payload.put("frame_id", frame.id).put("semantic_revision", frame.revision)
                .put("subject_id", actor).put("state_version", frame.stateVersion).put("at", frame.at)
                .put("available_event_ids", JSONArray(listOfNotNull(inputEventId)))
                .put("current_input_event", JSONObject().put("id", inputEventId).put("source", source)
                    .put("at", now).put("text_location", "current_request_input"))
            if (groupInput) {
                payload.getJSONObject("foundation").put("legacy_pending", JSONArray())
                // Candidate drafts and read-progress are private even when a future adopted
                // belief can be expressed in a group. Serialized fragments must not evade scrub.
                val inner = payload.getJSONObject("inner_structure")
                for (name in listOf("pending", "pending_fragments", "record_fragments", "pending_read", "pending_ids")) inner.put(name, JSONArray())
                inner.remove("pending_count")
                payload = SubjectRestDreams.publicMaterial(payload)
            }
            if (!groupInput) {
                SubjectConcerns.connect(data, payload, concerns, chat, now)
                if (concerns.isNotEmpty() || data.has("concern_settings")) payload.put("_concern_config", SubjectConcerns.config(data))
            }
            payload = SubjectMaterialBudget.select(payload,
                capabilities.optInt("subject_material_tokens", data.optInt("material_budget_tokens", SubjectMaterialBudget.DEFAULT_TOKENS))
                    .coerceIn(SubjectMaterialBudget.DEFAULT_TOKENS, SubjectMaterialBudget.MAX_TOKENS))
            if (!groupInput && frame != null) {
                val members = mutableMapOf<String, Int>()
                val inner = payload.getJSONObject("inner_structure")
                for (field in listOf("identity", "selected")) {
                    val rows = inner.getJSONArray(field)
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        members["inner:" + row.getJSONObject("content").getString("id")] = row.getInt("version")
                    }
                }
                // Only foundations actually included in this request qualify as union members.
                val availableFoundations = payload.getJSONObject("foundation").getJSONArray("available")
                for (i in 0 until availableFoundations.length()) {
                    val id = availableFoundations.getJSONObject(i).getString("id")
                    if (InnerFoundations.find(id)?.family != "drive" && id != "foundation:need:survival")
                        members["foundation:$id"] = InnerFoundations.member(data, id, now).getInt("version")
                }
                val projectedDrives = payload.getJSONObject("foundation").getJSONArray("drive_values")
                for (i in 0 until projectedDrives.length()) {
                    val item = projectedDrives.getJSONObject(i)
                    members["foundation:" + item.getString("id")] = item.getInt("version")
                }
                val episodes = payload.getJSONObject("category_events").getJSONArray("episodes")
                for (i in 0 until episodes.length()) {
                    val row = episodes.getJSONObject(i)
                    members["episode:" + row.getString("id")] = row.getInt("version")
                }
                val numeric = payload.getJSONArray("states")
                for (i in 0 until numeric.length()) {
                    val row = numeric.getJSONObject(i)
                    members["state:" + row.getString("id")] = row.getInt("version")
                }
                val survival = payload.getJSONObject("foundation").getJSONObject("survival")
                members["foundation:foundation:need:survival"] = survival.getInt("version")
                frame = frame.copy(interactionMembers = members)
            }
            if (frame != null) {
                val visible = payload.getJSONObject("foundation")
                val drives = visible.getJSONArray("drive_values")
                frame = frame.copy(foundationVersions = (0 until drives.length()).map { drives.getJSONObject(it) }
                    .filter { it.has("value") && !it.optBoolean("private_material_omitted") }.associate { row ->
                    row.getString("id") to row.getInt("version")
                } + ("foundation:need:survival" to visible.getJSONObject("survival").getInt("version")))
                val materials = payload.getJSONArray("retrieved_materials")
                val inner = payload.getJSONObject("inner_structure")
                val versions = mutableMapOf<String, Int>()
                for (field in listOf("identity", "selected")) {
                    val rows = inner.getJSONArray(field)
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        if (!row.optBoolean("private_material_omitted"))
                            versions[row.getJSONObject("content").getString("id")] = row.getInt("version")
                    }
                }
                val proposals = inner.getJSONArray("pending")
                val pendingBindings = (0 until proposals.length()).map { proposals.getJSONObject(it) }
                    .filter { !it.optBoolean("private_material_omitted") }
                    .associate { it.getString("id") to InnerStructure.hash(it) }.toMutableMap()
                val readParts = mutableMapOf<String, String>()
                if (!groupInput) {
                    val recordParts = inner.optJSONArray("record_fragments") ?: JSONArray()
                    for (i in 0 until recordParts.length()) {
                        val part = recordParts.getJSONObject(i)
                        readParts["record:" + part.getString("record_id")] = part.toString()
                    }
                    val fragments = inner.getJSONArray("pending_fragments")
                    for (i in 0 until fragments.length()) {
                        val part = fragments.getJSONObject(i)
                        readParts[part.getString("candidate_id")] = part.toString()
                    }
                    val previouslyRead = inner.getJSONArray("pending_read")
                    for (i in 0 until previouslyRead.length()) {
                        val row = previouslyRead.getJSONObject(i)
                        pendingBindings[row.getString("id")] = row.getString("hash")
                    }
                }
                frame = frame.copy(innerVersions = versions, innerPending = pendingBindings, innerReadParts = readParts,
                    materialSources = (0 until materials.length())
                    .map { materials.getJSONObject(it) }.filter { it.optString("status") == "retrieved" }
                    .associate { it.getString("id") to it.toString() })
            }
            if (frame != null) {
                val changes = payload.getJSONArray("important_changes")
                frame = frame.copy(materialTokenLimit = payload.getJSONObject("material_budget").getInt("limit_tokens"),
                    eventIds = setOfNotNull(inputEventId) +
                    (0 until changes.length()).map { changes.getJSONObject(it).getString("id") })
                data.put("selection_cursor", selected.nextCursor)
                // The old selector's cursor wraps at its own item count; sharing that cursor
                // would starve inner records beyond that count. This is selection, not growth.
                innerRoot.put("selection_cursor", if (innerCursor == Long.MAX_VALUE) 0L else innerCursor + 1)
                val materialCursor = data.optLong("material_query_cursor")
                data.put("material_query_cursor", if (materialCursor == Long.MAX_VALUE) 0L else materialCursor + 1)
                data.put("last_selection", JSONObject().put("frame_id", frame.id).put("at", now)
                    .put("chat", chat).put("selected_ids", JSONArray(selected.selectedIds))
                    .put("decisions", selected.decisions).put("material_budget", payload.getJSONObject("material_budget"))
                    .put("status", "PREPARED_FOR_SEND")
                    .put("foundation_ids", JSONArray((0 until payload.getJSONObject("foundation")
                        .getJSONArray("available").length()).map { index ->
                        payload.getJSONObject("foundation").getJSONArray("available").getJSONObject(index).getString("id")
                    })))
                // Sizes only: do not log private state, input text, or candidate descriptions.
                AppLogger.d("InternalState", "Prepared subject=$actor chat=$chat frame=${frame.id} version=${frame.stateVersion} semantic=${frame.revision}; chars=${payload.toString().length}, " +
                    "catalogue=${payload.getJSONArray("state_catalog").length()}, selected=${selected.selectedIds.size}")
            } else payload.put("preview", true)
            return RuntimeContextSections(
                instructions = SubjectReportInstructions.render(
                    hasFoundation = data.optJSONObject("foundation_state") != null,
                    identityGuard = InnerStructure.guard(data),
                ),
                state = "\n[Internal State 本轮名录与选择]\n${payload}\n",
                internalStateFrame = frame,
            )
        }
        if (!persist) return project(read(actor))
        lateinit var sections: RuntimeContextSections
        update(actor, "request_input") { data -> sections = project(data) }
        return sections
    }

    private fun candidates(data: JSONObject, actor: String, chat: String, wake: Boolean, capabilities: JSONObject, input: String): List<JSONObject> {
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
        store.withResearch(actor) { it.researchMaterials("task", data.optLong("material_query_cursor"), input) }
            .forEach {
                candidates.add(com.ai.assistance.operit.core.tasks.TaskRecords.material(it).put("action", "todo")
                    .put("available", true).put("subject_private_material", true))
            }
        store.withResearch(actor) { it.researchMaterials("source", data.optLong("material_query_cursor"), input) }.forEach {
            candidates.add(JSONObject(it.toString()).put("action", "explore").put("available", exploration).put("subject_private_material", true)
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
        /** Observational write failure must be logged, not turn a successfully persisted message/tool into a failure. */
        suspend fun noteEvent(context: Context, role: String, chat: String, id: String, source: String, detail: String) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val state = InternalStateStore(context)
                    if (state.enabled(role)) {
                        state.requireRecipient(role, chat)
                        state.event(role, chat, id, source, detail)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("InternalState", "后台事实状态记录失败；不重跑已完成活动", failure) }
        }
        suspend fun noteMessage(context: Context, chat: String, message: ChatMessage) {
            try { InternalStateStore(context).message(chat, message) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("InternalState", "消息状态记录失败", failure) }
        }
        suspend fun noteTool(context: Context, role: String?, chat: String?, tool: com.ai.assistance.operit.data.model.AITool, success: Boolean,
                             invocationId: String) {
            try {
                fun param(key: String) = tool.parameters.firstOrNull { it.name == key }?.value
                val status = param("status") ?: param("details")?.let { JSONObject(it).optString("status") }
                InternalStateStore(context).tool(role, chat, tool.name, param("action"), status, success, invocationId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("InternalState", "工具状态记录失败", failure) }
        }
    }
}

