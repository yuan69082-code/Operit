package com.ai.assistance.operit.services.core

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ConversationSummaryConfig
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.CharacterCardMemoryProfileBindingMode
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.core.tools.ToolProgressBus
import com.ai.assistance.operit.ui.features.chat.viewmodel.UiStateDelegate
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.services.ChatServiceUiBridge
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.data.repository.MemoryAutoSaveCandidateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息协调委托类
 * 负责消息发送、自动总结、附件清理等核心协调逻辑
 */
class MessageCoordinationDelegate(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val chatHistoryDelegate: ChatHistoryDelegate,
    private val messageProcessingDelegate: MessageProcessingDelegate,
    private val tokenStatsDelegate: TokenStatisticsDelegate,
    private val apiConfigDelegate: ApiConfigDelegate,
    private val attachmentDelegate: AttachmentDelegate,
    private val uiStateDelegate: UiStateDelegate,
    private val getEnhancedAiService: () -> EnhancedAIService?,
    private var uiBridge: ChatServiceUiBridge
) {
    companion object {
        private const val TAG = "MessageCoordinationDelegate"
    }

    // 总结状态（使用 summarizeHistory 时）
    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _isUpdatingMemory = MutableStateFlow(false)

    private val _summarizingChatId = MutableStateFlow<String?>(null)
    val summarizingChatId: StateFlow<String?> = _summarizingChatId.asStateFlow()

    // 发送消息触发的异步总结状态（使用 queueSummaryAfterSend 时）
    private val _isSendTriggeredSummarizing = MutableStateFlow(false)
    val isSendTriggeredSummarizing: StateFlow<Boolean> = _isSendTriggeredSummarizing.asStateFlow()

    private val _sendTriggeredSummarizingChatId = MutableStateFlow<String?>(null)
    val sendTriggeredSummarizingChatId: StateFlow<String?> = _sendTriggeredSummarizingChatId.asStateFlow()

    // 保存总结任务的 Job 引用，用于取消
    private var summaryJob: Job? = null
    private var sendTriggeredSummaryJob: Job? = null
    private val summaryRetryAfter = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var summaryPreparationJob: Job? = null
    private var summaryPreparationChatId: String? = null
    private val preparationRetryAfter = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // 保存当前的 promptFunctionType，用于自动继续时保持提示词一致性
    private var currentPromptFunctionType: PromptFunctionType = PromptFunctionType.CHAT
    private var currentChatModelConfigIdOverride: String? = null
    private var currentChatModelIndexOverride: Int? = null
    private var currentMemorySpaceIdOverride: String? = null

    private var nonFatalErrorCollectorJob: Job? = null
    private val characterCardManager = CharacterCardManager.getInstance(context)
    private val characterGroupCardManager = CharacterGroupCardManager.getInstance(context)
    private val activePromptManager = ActivePromptManager.getInstance(context)
    private val displayPreferencesManager = DisplayPreferencesManager.getInstance(context)
    private val groupJobs = ConcurrentHashMap<String, Job>()
    private val groupStore = com.ai.assistance.operit.core.group.GroupStore(context)

    init {
        ensureNonFatalErrorCollectorStarted()
    }

    private fun ensureNonFatalErrorCollectorStarted() {
        if (nonFatalErrorCollectorJob?.isActive == true) return
        nonFatalErrorCollectorJob = coroutineScope.launch {
            messageProcessingDelegate.nonFatalErrorEvent.collect { errorMessage ->
                uiStateDelegate.showToast(errorMessage)
            }
        }
    }



    private fun fallbackConversationTitle(userText: String, attachments: List<com.ai.assistance.operit.data.model.AttachmentInfo>): String {
        return attachments.firstOrNull()?.fileName?.trim()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.new_conversation)
    }

    private fun launchConversationTitleGeneration(
        chatId: String,
        userText: String,
        attachments: List<com.ai.assistance.operit.data.model.AttachmentInfo>,
        fallbackTitle: String
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val generatedTitle = EnhancedAIService.getChatInstance(context, chatId)
                    .generateConversationTitle(
                        userText = userText,
                        attachmentFileNames = attachments.map { it.fileName }
                    )
                    .trim()
                val currentTitle = chatHistoryDelegate.chatHistories.value.firstOrNull { it.id == chatId }?.title
                if (generatedTitle.isNotBlank() && currentTitle == fallbackTitle) {
                    chatHistoryDelegate.updateChatTitle(chatId, generatedTitle)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "生成群组对话标题失败", e)
            }
        }
    }

    private suspend fun recalculateStableWindowSize(
        service: EnhancedAIService,
        chatId: String?,
        roleCardId: String?,
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        groupOrchestrationMode: Boolean = false,
        groupParticipantNamesText: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null
    ): Long {
        val currentChat = chatHistoryDelegate.chatHistories.value.firstOrNull { it.id == chatId }
        val currentRoleName =
            roleCardId?.let {
                runCatching { characterCardManager.getCharacterCardFlow(it).first().name }
                    .getOrNull()
            }
        return AIMessageManager.calculateStableContextWindow(
            enhancedAiService = service,
            chatId = chatId,
            chatHistory = chatId?.let { chatHistoryDelegate.getRuntimeChatHistory(it) }.orEmpty(),
            workspacePath = currentChat?.workspace,
            workspaceEnv = currentChat?.workspaceEnv,
            promptFunctionType = promptFunctionType,
            roleCardId = roleCardId,
            currentRoleName = currentRoleName,
            splitHistoryByRole = true,
            groupOrchestrationMode = groupOrchestrationMode,
            groupParticipantNamesText = groupParticipantNamesText,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride,
            memorySpaceIdOverride = memorySpaceIdOverride,
            publishEstimate = false
        )
    }

    private suspend fun resolveBoundRoleCardId(chatId: String?): String? {
        if (chatId.isNullOrBlank()) {
            return null
        }
        val chatMeta = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId) ?: return null
        if (!chatMeta.characterGroupId.isNullOrBlank()) {
            return null
        }
        return chatMeta.characterCardId?.takeIf { id -> characterCardManager.getAllCharacterCards().any { it.id == id } }
    }

    private suspend fun resolveRoleCardId(
        chatId: String?,
        roleCardId: String?,
        preferActiveRoleCard: Boolean = false,
    ): String {
        val selected = if (chatId == null) roleCardId ?: activePromptManager.resolveActiveCardIdForSend() else {
            val chat = requireNotNull(com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId)) { "会话不存在" }
            val members = chat.characterGroupId?.let { characterGroupCardManager.getCharacterGroupCard(it)?.members }
                .orEmpty().sortedBy { it.orderIndex }.map { it.characterCardId }
            com.ai.assistance.operit.data.model.ChatOwnership.speaker(chat, roleCardId, members)
        }
        characterCardManager.ensureIndependentSettings(selected)
        return selected
    }

    private suspend fun resolveRegenerationRoleCardId(
        chatId: String,
        message: ChatMessage,
    ): String {
        val chat = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId)
        val group = chat?.characterGroupId?.let { characterGroupCardManager.getCharacterGroupCard(it) }
        if (group != null) {
            val memberIds = group.members.map { it.characterCardId }.toSet()
            check(com.ai.assistance.operit.core.group.GroupGames(context).state(chatId)?.phase?.let { it != "ended" } != true) { "游戏进行中不能重新生成已执行的行动，请使用游戏页继续" }
            val author = com.ai.assistance.operit.core.group.GroupProvenance.author(context, chatId, message)
            require(author != null && author in memberIds) { "这条消息缺少可确认的现有成员身份，请新发一条消息并 @ 目标助手" }
            return resolveRoleCardId(chatId, author)
        }
        return resolveRoleCardId(chatId, null)
    }

    private fun isGroupChatSession(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return chatHistoryDelegate.chatHistories.value
            .firstOrNull { it.id == chatId }
            ?.characterGroupId
            ?.isNotBlank() == true
    }

    private fun resolveWindowEstimateService(chatId: String?): EnhancedAIService? {
        return if (chatId.isNullOrBlank()) {
            getEnhancedAiService()
        } else {
            EnhancedAIService.getChatInstance(context, chatId)
        }
    }

    suspend fun refreshStableContextWindow(
        chatId: String? = null,
        roleCardId: String? = null,
        promptFunctionType: PromptFunctionType? = null,
        groupOrchestrationMode: Boolean = false,
        groupParticipantNamesText: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null
    ): Long? {
        val targetChatId = chatId ?: chatHistoryDelegate.currentChatId.value ?: return null
        val service = resolveWindowEstimateService(targetChatId) ?: return null
        val effectiveRoleCardId = resolveRoleCardId(targetChatId, roleCardId)
        val effectivePromptFunctionType = promptFunctionType ?: currentPromptFunctionType
        val effectiveChatModelConfigIdOverride =
            chatModelConfigIdOverride ?: currentChatModelConfigIdOverride
        val effectiveChatModelIndexOverride =
            chatModelIndexOverride ?: currentChatModelIndexOverride
        val effectiveMemorySpaceIdOverride =
            memorySpaceIdOverride
                ?: currentMemorySpaceIdOverride
                ?: effectiveRoleCardId?.let { resolveRoleCardMemoryProfileOverride(it) }

        val newWindowSize =
            recalculateStableWindowSize(
                service = service,
                chatId = targetChatId,
                roleCardId = effectiveRoleCardId,
                promptFunctionType = effectivePromptFunctionType,
                groupOrchestrationMode = groupOrchestrationMode,
                groupParticipantNamesText = groupParticipantNamesText,
                chatModelConfigIdOverride = effectiveChatModelConfigIdOverride,
                chatModelIndexOverride = effectiveChatModelIndexOverride,
                memorySpaceIdOverride = effectiveMemorySpaceIdOverride
            )
        val (inputTokens, outputTokens) = tokenStatsDelegate.getCumulativeTokenCounts(targetChatId)
        chatHistoryDelegate.saveCurrentChat(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            actualContextWindowSize = newWindowSize,
            chatIdOverride = targetChatId
        )
        withContext(Dispatchers.Main) {
            tokenStatsDelegate.setTokenCounts(
                targetChatId,
                inputTokens,
                outputTokens,
                newWindowSize
            )
        }
        AppLogger.d(
            TAG,
            "上下文窗口已刷新: chatId=$targetChatId, window=$newWindowSize, " +
                "input=$inputTokens, output=$outputTokens, service=${service.javaClass.simpleName}, " +
                "promptType=$effectivePromptFunctionType"
        )
        return newWindowSize
    }

    /**
     * 发送用户消息
     * 检查是否有当前对话，如果没有则自动创建新对话
     */
    fun sendUserMessage(
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        roleCardIdOverride: String? = null,
        chatIdOverride: String? = null,
        messageTextOverride: String? = null,
        proxySenderNameOverride: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        preferActiveRoleCard: Boolean = false,
    ) {
        // 仅在没有指定 chatId 的情况下，才需要确保有当前对话
        val wakeCancellation = if (!turnOptions.proactiveWake)
            com.ai.assistance.operit.core.companion.CompanionRuntime.noteUserInteraction() else null
        if (wakeCancellation != null) {
            // Cancellation is asynchronous. Wait for our owned background turn to release the
            // normal chat runtime, otherwise the real message is silently rejected as "busy".
            coroutineScope.launch {
                wakeCancellation.join()
                sendUserMessage(promptFunctionType = promptFunctionType, roleCardIdOverride = roleCardIdOverride,
                    chatIdOverride = chatIdOverride, messageTextOverride = messageTextOverride,
                    proxySenderNameOverride = proxySenderNameOverride, chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride, turnOptions = turnOptions, preferActiveRoleCard = preferActiveRoleCard)
            }
            return
        }
        if (chatIdOverride.isNullOrBlank() && chatHistoryDelegate.currentChatId.value == null) {
            AppLogger.d(TAG, "当前没有活跃对话，自动创建新对话")

            // 使用 coroutineScope 启动协程
            coroutineScope.launch {
                // 使用现有的createNewChat方法创建新对话
                chatHistoryDelegate.createNewChat()

                // 等待对话ID更新
                var waitCount = 0
                while (chatHistoryDelegate.currentChatId.value == null && waitCount < 10) {
                    delay(100) // 短暂延迟等待对话创建完成
                    waitCount++
                }

                if (chatHistoryDelegate.currentChatId.value == null) {
                    AppLogger.e(TAG, "创建新对话超时，无法发送消息")
                    uiStateDelegate.showErrorMessage(context.getString(R.string.chat_cannot_create_new))
                    return@launch
                }

                AppLogger.d(
                    TAG,
                    "新对话创建完成，ID: ${chatHistoryDelegate.currentChatId.value}，现在发送消息"
                )

                // 对话创建完成后，发送消息
                sendMessageInternal(
                    promptFunctionType,
                    roleCardIdOverride = roleCardIdOverride,
                    preferActiveRoleCard = preferActiveRoleCard,
                    chatIdOverride = chatIdOverride,
                    messageTextOverride = messageTextOverride,
                    proxySenderNameOverride = proxySenderNameOverride,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride,
                    turnOptions = turnOptions
                )
            }
        } else {
            // 已有对话，直接发送消息
            sendMessageInternal(
                promptFunctionType,
                roleCardIdOverride = roleCardIdOverride,
                preferActiveRoleCard = preferActiveRoleCard,
                chatIdOverride = chatIdOverride,
                messageTextOverride = messageTextOverride,
                proxySenderNameOverride = proxySenderNameOverride,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride,
                turnOptions = turnOptions
            )
        }
    }

    suspend fun regenerateSingleAiMessage(index: Int) {
        val chatId =
            chatHistoryDelegate.currentChatId.value
                ?: throw IllegalStateException(context.getString(R.string.chat_no_active_conversation))
        if (messageProcessingDelegate.isChatLoading(chatId)) {
            throw IllegalStateException(context.getString(R.string.chat_regenerate_busy))
        }

        val currentHistory = chatHistoryDelegate.chatHistory.value
        val targetMessage =
            currentHistory.getOrNull(index)
                ?: throw IndexOutOfBoundsException(context.getString(R.string.chat_invalid_message_index))
        if (targetMessage.sender != "ai") {
            throw IllegalArgumentException(context.getString(R.string.chat_only_ai_message_allowed))
        }
        val runtimeHistory =
            chatHistoryDelegate.getRuntimeChatHistoryUpTo(
                chatId = chatId,
                upToTimestampInclusive = targetMessage.timestamp
            )
        val targetRuntimeIndex = runtimeHistory.indexOfFirst { it.timestamp == targetMessage.timestamp }
        if (targetRuntimeIndex < 0) {
            throw IndexOutOfBoundsException(context.getString(R.string.chat_invalid_message_index))
        }
        val requestHistory = runtimeHistory.take(targetRuntimeIndex)
        val requestMessageContent =
            requestHistory.lastOrNull()
                ?.takeIf { it.sender == "user" }
                ?.content
                .orEmpty()

        val currentChat = chatHistoryDelegate.chatHistories.value.firstOrNull { it.id == chatId }
        val groupParticipantNamesText = buildBoundGroupParticipantNamesText(chatId)
        val groupOrchestrationMode = groupParticipantNamesText != null
        val workspacePath = currentChat?.workspace
        val roleCardId = resolveRegenerationRoleCardId(chatId, targetMessage)
        check(!com.ai.assistance.operit.core.engine.EngineWindow.associated(context, roleCardId)) {
            "主体表达由引擎管理，软件不能用模型重新生成"
        }
        val currentRoleName =
            runCatching { characterCardManager.getCharacterCardFlow(roleCardId).first().name }
                .getOrDefault(targetMessage.roleName)
                .ifBlank { targetMessage.roleName }

        val (resolvedChatModelConfigIdOverride, resolvedChatModelIndexOverride) =
            resolveRoleCardChatModelOverrides(roleCardId)
        val resolvedMemorySpaceIdOverride =
            resolveRoleCardMemoryProfileOverride(roleCardId)
        val chatContextSettings =
            resolveChatContextSettingsForRequest(resolvedChatModelConfigIdOverride)

        try {
            messageProcessingDelegate.regenerateAiMessageVariant(
                chatId = chatId,
                targetMessageTimestamp = targetMessage.timestamp,
                requestMessageContent = requestMessageContent,
                requestHistory = requestHistory,
                workspacePath = workspacePath,
                promptFunctionType = currentPromptFunctionType,
                roleCardId = roleCardId,
                currentRoleName = currentRoleName,
                enableThinking = runBlocking(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(roleCardId)) {
                    com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context).enableThinkingModeFlow.first()
                },
                enableMemoryAutoUpdate = runBlocking(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(roleCardId)) {
                    com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()
                },
                maxTokens =
                    (chatContextSettings.effectiveContextLength * 1024)
                        .toLong()
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt(),
                tokenUsageThreshold = chatContextSettings.summaryTokenThreshold.toDouble(),
                chatModelConfigIdOverride = resolvedChatModelConfigIdOverride,
                chatModelIndexOverride = resolvedChatModelIndexOverride,
                memorySpaceIdOverride = resolvedMemorySpaceIdOverride,
                groupOrchestrationMode = groupOrchestrationMode,
                groupParticipantNamesText = groupParticipantNamesText,
                onVariantPreviewStarted = { previewMessage ->
                    chatHistoryDelegate.addMessageToChat(
                        previewMessage.copy(
                            content = "",
                            selectedVariantIndex = targetMessage.variantCount,
                            variantCount = targetMessage.variantCount + 1,
                            isVariantPreview = true,
                        ),
                        chatIdOverride = chatId,
                    )
                },
                onVariantReady = { variantMessage ->
                    chatHistoryDelegate.addMessageVariant(
                        timestamp = targetMessage.timestamp,
                        message = variantMessage,
                        chatIdOverride = chatId,
                    )
                    runCatching {
                        refreshStableContextWindow(
                            chatId = chatId,
                            roleCardId = roleCardId,
                            groupOrchestrationMode = groupOrchestrationMode,
                            groupParticipantNamesText = groupParticipantNamesText,
                            chatModelConfigIdOverride = resolvedChatModelConfigIdOverride,
                            chatModelIndexOverride = resolvedChatModelIndexOverride,
                            memorySpaceIdOverride = resolvedMemorySpaceIdOverride
                        )
                    }.onFailure {
                        AppLogger.w(TAG, "单条重新生成后刷新上下文窗口失败", it)
                    }
                },
            )
        } catch (e: Exception) {
            if (chatHistoryDelegate.currentChatId.value == chatId) {
                runCatching {
                    chatHistoryDelegate.addMessageToChat(
                        targetMessage.copy(
                            contentStream = null,
                            isVariantPreview = true,
                        ),
                        chatIdOverride = chatId,
                    )
                }.onFailure {
                    AppLogger.w(TAG, "单条重新生成失败后恢复聊天显示失败", it)
                }
            }
            throw e
        }
    }

    /**
     * 内部发送消息的逻辑
     */
    private fun sendMessageInternal(
        promptFunctionType: PromptFunctionType,
        isContinuation: Boolean = false,
        skipSummaryCheck: Boolean = false,
        isAutoContinuation: Boolean = false,
        roleCardIdOverride: String? = null,
        preferActiveRoleCard: Boolean = false,
        chatIdOverride: String? = null,
        messageTextOverride: String? = null,
        // 群组编排首成员专用：复用 orchestrateGroupConversation 已构建并落库的用户消息内容
        prebuiltMessageContent: String? = null,
        proxySenderNameOverride: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null,
        suppressUserMessageInHistory: Boolean = false,
        forceDisableSummary: Boolean = false,
        enableGroupOrchestration: Boolean = true,
        isGroupOrchestrationTurn: Boolean = false,
        groupParticipantNamesText: String? = null,
        turnOptions: ChatTurnOptions = ChatTurnOptions()
    ) {
        // 如果不是自动续写，更新当前的 promptFunctionType
        if (!isAutoContinuation) {
            currentPromptFunctionType = promptFunctionType
        }
        val isBackgroundSend =
            !chatIdOverride.isNullOrBlank() && chatIdOverride != chatHistoryDelegate.currentChatId.value
        // 自动续聊由总结消息中的续接指令驱动，不能消费用户尚未提交的编辑器状态。
        val shouldReadComposerState = turnOptions.voiceMessageTimestamp == null && !isGroupOrchestrationTurn && !isBackgroundSend && !isAutoContinuation && !turnOptions.voiceCall && !turnOptions.voiceCallEnded && !turnOptions.proactiveWake
        val effectiveMessageTextOverride = if (isAutoContinuation) "" else messageTextOverride
        // 获取当前聊天ID和工作区路径
        val chatId = chatIdOverride ?: chatHistoryDelegate.currentChatId.value
        if (chatId == null) {
            uiStateDelegate.showErrorMessage(context.getString(R.string.chat_no_active_conversation))
            return
        }
        if (shouldReadComposerState && messageTextOverride == null && roleCardIdOverride == null) {
            val ownerMatches = runBlocking {
                val bound = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context).getChatMetadata(chatId)
                bound != null && com.ai.assistance.operit.data.model.ChatOwnership.belongsTo(bound, activePromptManager.getActivePrompt())
            }
            if (!ownerMatches) {
                uiStateDelegate.showErrorMessage("助手正在切换，请等聊天加载完成后再发送。")
                return
            }
        }
        if (
            turnOptions.persistTurn &&
            enableGroupOrchestration &&
            shouldRunGroupOrchestration(
                promptFunctionType = promptFunctionType,
                isContinuation = isContinuation,
                isAutoContinuation = isAutoContinuation,
                skipSummaryCheck = skipSummaryCheck,
                roleCardIdOverride = roleCardIdOverride,
                proxySenderNameOverride = proxySenderNameOverride,
                messageTextOverride = messageTextOverride,
                chatIdOverride = chatIdOverride
            )
        ) {
            launchGroup(chatId) { orchestrateGroupConversation(chatId, promptFunctionType, turnOptions) }

            return
        }
        val currentChat = chatHistoryDelegate.chatHistories.value.find { it.id == chatId }
        val workspacePath = currentChat?.workspace
        val workspaceEnv = currentChat?.workspaceEnv

        if (!isBackgroundSend) {
            // 更新本地Web服务器的聊天ID
            uiBridge.updateWebServerForCurrentChat(chatId)
        }

        // 获取当前附件列表
        val currentAttachments =
            if (shouldReadComposerState) attachmentDelegate.attachments.value else emptyList()
        // Every request, including background wakes, is scoped to the persisted owner.
        val roleCardId = try { runBlocking {
            resolveRoleCardId(
                chatId = chatId,
                roleCardId = roleCardIdOverride,
                preferActiveRoleCard = preferActiveRoleCard,
            )
        } } catch (e: Exception) {
            AppLogger.e(TAG, "解析聊天所属助手失败", e)
            uiStateDelegate.showErrorMessage(e.message ?: "无法确定聊天所属助手")
            return
        }
        if (com.ai.assistance.operit.core.engine.EngineWindow.associated(context, roleCardId)) {
            if (isAutoContinuation) {
                uiStateDelegate.showErrorMessage("主体接续由引擎管理")
                return
            }
            messageProcessingDelegate.sendEngineMessage(
                chatId, roleCardId, effectiveMessageTextOverride, currentAttachments,
                isGroupOrchestrationTurn, suppressUserMessageInHistory, turnOptions,
                if (shouldReadComposerState) uiBridge.getReplyToMessage() else null
            )
            return
        }
        val resolvedOverrides = try {
            if (promptFunctionType == PromptFunctionType.CHAT) {
                val (resolvedChatModelConfigIdOverride, resolvedChatModelIndexOverride) =
                    when {
                        !chatModelConfigIdOverride.isNullOrBlank() -> {
                            Pair(chatModelConfigIdOverride, (chatModelIndexOverride ?: 0).coerceAtLeast(0))
                        }
                        isAutoContinuation -> {
                            Pair(currentChatModelConfigIdOverride, currentChatModelIndexOverride)
                        }
                        else -> {
                            resolveRoleCardChatModelOverrides(roleCardId)
                        }
                    }
                val resolvedMemorySpaceIdOverride =
                    when {
                        !memorySpaceIdOverride.isNullOrBlank() -> memorySpaceIdOverride
                        isAutoContinuation -> currentMemorySpaceIdOverride
                        else -> roleCardId?.let { resolveRoleCardMemoryProfileOverride(it) }
                    }
                Triple(
                    resolvedChatModelConfigIdOverride,
                    resolvedChatModelIndexOverride,
                    resolvedMemorySpaceIdOverride
                )
            } else {
                // Voice changes the prompt format, not the assistant that owns this chat.
                val (configId, modelIndex) = resolveRoleCardChatModelOverrides(roleCardId)
                Triple(configId, modelIndex, resolveRoleCardMemoryProfileOverride(roleCardId))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析角色卡对话模型绑定失败", e)
            uiStateDelegate.showErrorMessage(
                e.message ?: context.getString(R.string.role_card_chat_model_binding_parse_failed)
            )
            return
        }
        val resolvedChatModelConfigIdOverride = resolvedOverrides.first
        val resolvedChatModelIndexOverride = resolvedOverrides.second
        val resolvedMemorySpaceIdOverride = resolvedOverrides.third
        val chatContextSettings =
            runBlocking {
                resolveChatContextSettingsForRequest(resolvedChatModelConfigIdOverride)
            }

        if (!isAutoContinuation) {
            currentChatModelConfigIdOverride = resolvedChatModelConfigIdOverride
            currentChatModelIndexOverride = resolvedChatModelIndexOverride
            currentMemorySpaceIdOverride = resolvedMemorySpaceIdOverride
        }

        // 当前请求使用的Token使用率阈值，默认使用配置值
        val tokenUsageThresholdForSend = chatContextSettings.summaryTokenThreshold.toDouble()
        var summarizeAfterSend = false
        val maxTokensForSend =
            (chatContextSettings.effectiveContextLength * 1024)
                .toLong()
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()

        // 如果不是续写，检查是否需要总结
        if (turnOptions.persistTurn && !isBackgroundSend && !isContinuation && !skipSummaryCheck &&
            sendTriggeredSummaryJob?.isActive != true && summaryJob?.isActive != true &&
            android.os.SystemClock.elapsedRealtime() >= (summaryRetryAfter[chatId] ?: 0L)) {
            val currentMessages = runBlocking { chatHistoryDelegate.getCurrentRuntimeChatHistorySnapshot() }
            val currentTokens = tokenStatsDelegate.currentWindowSizeFlow.value

            val isShouldGenerateSummary = AIMessageManager.shouldGenerateSummary(
                messages = currentMessages,
                currentTokens = currentTokens,
                maxTokens = maxTokensForSend,
                tokenUsageThreshold = tokenUsageThresholdForSend,
                enableSummary = chatContextSettings.enableSummary,
                enableSummaryByMessageCount = chatContextSettings.enableSummaryByMessageCount,
                summaryMessageCountThreshold = chatContextSettings.summaryMessageCountThreshold
            )

            if (!isShouldGenerateSummary && chatContextSettings.enableSummary &&
                summaryPreparationJob?.isActive != true &&
                android.os.SystemClock.elapsedRealtime() >= (preparationRetryAfter[chatId] ?: 0L) &&
                (maxTokensForSend > 0 && currentTokens >= maxTokensForSend * tokenUsageThresholdForSend * 0.7 ||
                    chatContextSettings.enableSummaryByMessageCount &&
                    currentMessages.drop(currentMessages.indexOfLast { it.sender == "summary" } + 1)
                        .count { it.sender == "user" } >= chatContextSettings.summaryMessageCountThreshold * 0.7)) {
                val prefix = com.ai.assistance.operit.core.chat.ReviewedSummaryBatches.preparationPrefix(currentMessages)
                if (prefix.isNotEmpty()) {
                    summaryPreparationChatId = chatId
                    summaryPreparationJob = coroutineScope.launch(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(roleCardId)) {
                        val serviceKey = "summary-preparation:${java.util.UUID.randomUUID()}"
                        try {
                            // Separate service and no processing-state changes: preparing must not lock input.
                            AIMessageManager.summarizeMemory(
                                enhancedAiService = EnhancedAIService.getChatInstance(context, serviceKey),
                                messages = prefix.map { it.copy() }, summaryConfig = readSummaryConfig(),
                                chatId = chatId, roleCardId = roleCardId,
                                chatModelConfigIdOverride = resolvedChatModelConfigIdOverride,
                                chatModelIndexOverride = resolvedChatModelIndexOverride,
                                isGroupChat = isGroupChatSession(chatId), summaryProgress = false
                            )
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) {
                            preparationRetryAfter[chatId] = android.os.SystemClock.elapsedRealtime() + 300_000
                            AppLogger.e(TAG, "Background summary preparation failed", error)
                        } finally {
                            EnhancedAIService.releaseChatInstance(serviceKey)
                            summaryPreparationChatId = null
                        }
                    }
                }
            }

            summarizeAfterSend = isShouldGenerateSummary
        }

        val proxySenderName = proxySenderNameOverride?.takeIf { it.isNotBlank() }

        // 如果是proxy sender，视为关闭记忆自动更新
        val shouldEnableMemoryAutoUpdate = if (proxySenderName.isNullOrBlank()) {
            runBlocking(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(roleCardId)) {
                    com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()
                }
        } else {
            false
        }

        // 调用messageProcessingDelegate发送消息，并传递附件信息和工作区路径
        messageProcessingDelegate.sendUserMessage(
            attachments = currentAttachments,
            chatId = chatId,
            messageTextOverride = effectiveMessageTextOverride,
            prebuiltMessageContent = prebuiltMessageContent,
            proxySenderNameOverride = proxySenderName,
            workspacePath = workspacePath,
            workspaceEnv = workspaceEnv,
            promptFunctionType = promptFunctionType,
            roleCardId = roleCardId,
            enableThinking = runBlocking(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(roleCardId)) {
                    com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(context).enableThinkingModeFlow.first()
                },
            enableMemoryAutoUpdate = shouldEnableMemoryAutoUpdate,
            maxTokens = maxTokensForSend,
            tokenUsageThreshold = tokenUsageThresholdForSend,
            replyToMessage = if (shouldReadComposerState) uiBridge.getReplyToMessage() else null,
            isAutoContinuation = isAutoContinuation,
            enableSummary = !forceDisableSummary && (!isBackgroundSend || turnOptions.voiceCall) && chatContextSettings.enableSummary,
            chatModelConfigIdOverride = resolvedChatModelConfigIdOverride,
            chatModelIndexOverride = resolvedChatModelIndexOverride,
            memorySpaceIdOverride = resolvedMemorySpaceIdOverride,
            suppressUserMessageInHistory = suppressUserMessageInHistory,
            isGroupOrchestrationTurn = isGroupOrchestrationTurn,
            groupParticipantNamesText = groupParticipantNamesText,
            turnOptions = turnOptions
        )

        // Schedule only after sendUserMessage registered its job. Capture source after that
        // job completes so a tool result or final reply cannot land behind the summary marker.
        if (summarizeAfterSend) {
            queueSummaryAfterSend(
                originalChatId = chatId, roleCardId = roleCardId,
                chatModelConfigIdOverride = resolvedChatModelConfigIdOverride,
                chatModelIndexOverride = resolvedChatModelIndexOverride,
                memorySpaceIdOverride = resolvedMemorySpaceIdOverride
            )
        }

        // 只有在非续写（即用户主动发送）时才清空附件和UI状态
        if (turnOptions.voiceMessageTimestamp == null && !isBackgroundSend && !isContinuation && !turnOptions.voiceCall && !turnOptions.voiceCallEnded) {
            if (currentAttachments.isNotEmpty()) {
                attachmentDelegate.clearAttachments()
            }
            uiBridge.resetAttachmentPanelState()
            uiBridge.clearReplyToMessage()
        }
    }

    private fun shouldRunGroupOrchestration(
        promptFunctionType: PromptFunctionType,
        isContinuation: Boolean,
        isAutoContinuation: Boolean,
        skipSummaryCheck: Boolean,
        roleCardIdOverride: String?,
        proxySenderNameOverride: String?,
        messageTextOverride: String?,
        chatIdOverride: String?
    ): Boolean {
        if (promptFunctionType != PromptFunctionType.CHAT) return false
        if (isContinuation || isAutoContinuation || skipSummaryCheck) return false
        if (!roleCardIdOverride.isNullOrBlank()) return false
        if (!proxySenderNameOverride.isNullOrBlank()) return false
        if (!messageTextOverride.isNullOrBlank()) return false
        if (!chatIdOverride.isNullOrBlank()) return false
        val chatId = chatHistoryDelegate.currentChatId.value ?: return false
        return runBlocking {
            !com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
                .getChatMetadata(chatId)?.characterGroupId.isNullOrBlank()
        }
    }

    private suspend fun orchestrateGroupConversation(
        chatId: String,
        promptFunctionType: PromptFunctionType,
        turnOptions: ChatTurnOptions
    ): Boolean {
        val group = resolveTargetGroupForChat(chatId) ?: return false

        val orderedMembers = group.members
            .sortedBy { it.orderIndex }
            .filter { it.characterCardId.isNotBlank() }
        AppLogger.d(
            TAG,
            "回答规划: plan=${group.id}, members=${group.members.size}, activeMembers=${orderedMembers.size}"
        )
        if (orderedMembers.isEmpty()) {
            return false
        }
        com.ai.assistance.operit.core.group.GroupLifecycle.requireOpen(context, chatId)
        check(com.ai.assistance.operit.core.group.GroupModeration(context).mayPublish(chatId, "user")) { "你目前在本群被禁言，可以在禁言或游戏面板管理" }
        val availableCards = characterCardManager.getAllCharacterCards().map { it.id }.toSet()
        require(orderedMembers.all { it.characterCardId in availableCards }) { "群聊中有已删除的助手，请先调整群成员。" }

        val existingBinding = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
            .getChatMetadata(chatId)?.characterGroupId
        check(existingBinding == group.id) { "群聊归属不一致，请重新打开群聊" }

        val originalUserText = messageProcessingDelegate.userMessage.value.text.trim()
        val hasAttachments = attachmentDelegate.attachments.value.isNotEmpty()
        val hasEngineMember = orderedMembers.any {
            com.ai.assistance.operit.core.engine.EngineWindow.associated(context, it.characterCardId)
        }
        check(!hasEngineMember || !hasAttachments) { "含引擎主体的群聊暂未接通附件协议，请先发送文字；附件已保留" }
        AppLogger.d(
            TAG,
            "群组编排输入: chatId=$chatId, userTextLength=${originalUserText.length}, hasAttachments=$hasAttachments"
        )
        if (originalUserText.isBlank() && !hasAttachments) {
            AppLogger.d(TAG, "群组编排终止: 输入为空且无附件")
            return false
        }
        if (originalUserText.isNotBlank()) {
            messageProcessingDelegate.updateUserMessage("")
        }

        messageProcessingDelegate.setInputProcessingStateForChat(
            chatId,
            InputProcessingState.Processing(context.getString(R.string.role_response_planner_planning))
        )

        val timeline = mutableListOf<Pair<String, String>>()
        if (originalUserText.isNotBlank()) {
            timeline.add(context.getString(R.string.message_role_user) to originalUserText)
        }

        val currentChat = chatHistoryDelegate.chatHistories.value.firstOrNull { it.id == chatId }
        val workspacePath = currentChat?.workspace
        val workspaceEnv = currentChat?.workspaceEnv
        val attachments = attachmentDelegate.attachments.value
        val replyToMessage = uiBridge.getReplyToMessage()

        val isFirstMessage = !chatHistoryDelegate.hasUserMessage(chatId)
        val titleFallback = if (isFirstMessage) {
            fallbackConversationTitle(originalUserText, attachments).also { fallbackTitle ->
                chatHistoryDelegate.updateChatTitle(chatId, fallbackTitle)
            }
        } else {
            null
        }

        val finalUserMessageContent = if (hasEngineMember) originalUserText else
            messageProcessingDelegate.buildUserMessageContentForGroupOrchestration(
                messageText = originalUserText,
                attachments = attachments,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                replyToMessage = replyToMessage,
                chatId = chatId,
                memberId = orderedMembers.first().characterCardId
            )
        val userMessage = ChatMessage(
            sender = "user",
            content = finalUserMessageContent,
            timestamp = turnOptions.voiceMessageTimestamp ?: com.ai.assistance.operit.data.model.ChatMessageTimestampAllocator.next(),
            roleName = context.getString(R.string.message_role_user),
            displayMode =
                if (turnOptions.hideUserMessage) {
                    ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
                } else {
                    ChatMessageDisplayMode.NORMAL
                }
        )
        chatHistoryDelegate.addMessageToChat(userMessage, chatId)
        turnOptions.onUserMessagePersisted?.invoke()
        if (!turnOptions.hideUserMessage) com.ai.assistance.operit.core.awareness.SoftwareAwareness.userMessage(
            context, chatId, null, "group_message_sent")
        com.ai.assistance.operit.core.group.GroupContext.rememberMembers(context, chatId)
        titleFallback?.takeUnless { hasEngineMember }?.let { fallbackTitle ->
            launchConversationTitleGeneration(
                chatId = chatId,
                userText = originalUserText,
                attachments = attachments,
                fallbackTitle = fallbackTitle
            )
        }

        val addressed = groupStore.selected(chatId)
        val selected = com.ai.assistance.operit.core.group.GroupPolicy.targets(
            orderedMembers.map { it.characterCardId }, addressed)
        groupStore.update(chatId) { it.put("addressed", JSONArray(addressed)).remove("mentions") }
        attachmentDelegate.clearAttachments()
        uiBridge.resetAttachmentPanelState()
        uiBridge.clearReplyToMessage()
        runGroupQueue(chatId, selected, originalUserText, finalUserMessageContent, turnOptions)
        return true
    }

    private fun launchGroup(chatId: String, action: suspend () -> Unit) {
        val previous = groupJobs.remove(chatId)
        previous?.cancel()
        val barrier = if (previous != null) messageProcessingDelegate.cancelMessageAndWait(chatId) else null
        val job = coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                previous?.join()
                barrier?.join()
                groupStore.update(chatId) { it.put("status", "running").remove("decision") }
                action()
                if (groupStore.read(chatId).optString("status") == "running") {
                    messageProcessingDelegate.setInputProcessingStateForChat(chatId, InputProcessingState.Completed)
                }
                groupStore.update(chatId) {
                    if (it.optString("status") == "running") it.put("status", "idle").remove("pending")
                    it.remove("speaker")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                messageProcessingDelegate.cancelMessageAndWait(chatId)?.join()
                AppLogger.e(TAG, "群聊运行失败", failure)
                val detail = failure.message ?: failure.javaClass.simpleName
                groupStore.log(chatId, "失败", detail)
                groupStore.update(chatId) { it.put("status", "error").remove("speaker") }
                uiStateDelegate.showErrorMessage(detail)
                messageProcessingDelegate.setInputProcessingStateForChat(chatId, InputProcessingState.Error(detail))
            } finally {
                groupJobs.remove(chatId, coroutineContext[Job])
            }
        }
        groupJobs[chatId] = job
        job.start()
    }

    fun stopGroup(chatId: String, paused: Boolean = true) {
        if (!isGroupChatSession(chatId) && !groupJobs.containsKey(chatId)) return
        groupJobs.remove(chatId)?.cancel()
        groupStore.update(chatId) {
            it.put("status", if (paused) "paused" else "ended")
            it.remove("speaker")
            it.remove("speaker_id")
            it.remove("decision")
            if (!paused) it.remove("pending")
        }
        groupStore.log(chatId, if (paused) "暂停" else "结束", "用户操作，取消后续排队")
    }

    fun startGroupDiscussion(chatId: String, actor: String? = null, purpose: String? = null) {
        if (groupJobs[chatId]?.isActive == true) return
        launchGroup(chatId) {
            val group = requireNotNull(resolveTargetGroupForChat(chatId)) { "群聊不存在" }
            check(!group.ended) { "群已结束，请先重新开放" }
            val ids = group.members.sortedBy { it.orderIndex }.map { it.characterCardId }
            val pending = groupStore.strings(groupStore.read(chatId).optJSONArray("pending"))
            val queue = if (actor != null) listOf(actor) else if (pending.isNotEmpty()) pending else ids
            require(queue.isNotEmpty() && queue.all { it in ids }) { "待发言成员已经离群" }
            if (purpose != null) groupStore.update(chatId) { it.put("purpose", purpose.take(500)) }
            groupStore.log(chatId, if (actor == null) "继续" else "唤醒", purpose.orEmpty(), actor.orEmpty())
            runGroupQueue(chatId, queue, "", null, ChatTurnOptions())
        }
    }

    fun isGroupRunning(chatId: String) = groupJobs[chatId]?.isActive == true

    fun deliverGroupMessage(chatId: String, item: JSONObject) {
        if (isGroupRunning(chatId) || messageProcessingDelegate.isChatLoading(chatId)) return
        launchGroup(chatId) {
            val actor = item.getString("actor")
            val group = resolveTargetGroupForChat(chatId)
            if (group == null || group.ended) {
                com.ai.assistance.operit.core.group.GroupDelivery.remove(groupStore, chatId, item.getString("id"))
                groupStore.log(chatId, "投递取消", "目标群聊已删除", actor)
                return@launchGroup
            }
            val members = group.members.map { it.characterCardId }
            if (actor !in members || !com.ai.assistance.operit.core.group.GroupModeration(context).mayPublish(chatId, actor)) {
                com.ai.assistance.operit.core.group.GroupDelivery.remove(groupStore, chatId, item.getString("id"))
                groupStore.log(chatId, "投递取消", "发信助手已离群或被禁言", actor)
                return@launchGroup
            }
            val card = characterCardManager.getCharacterCard(actor)
            val message = ChatMessage(sender = "ai", roleName = card.name, content = item.getString("text"),
                timestamp = item.getLong("timestamp"), completedAt = System.currentTimeMillis())
            // The allocated timestamp is persisted with the outbox item. Replaying after a crash
            // updates this same message rather than appending a second copy.
            com.ai.assistance.operit.core.group.GroupProvenance.record(context, chatId, message, actor)
            chatHistoryDelegate.addMessageToChat(message, chatId)
            com.ai.assistance.operit.core.group.GroupDelivery.remove(groupStore, chatId, item.getString("id"))
            groupStore.log(chatId, "已发送", "私聊转群：${item.getString("id")}", actor)
            com.ai.assistance.operit.core.group.GroupContext.rememberMembers(context, chatId)
            val requested = groupStore.strings(item.optJSONArray("targets"))
            val listeners = members.filter { it != actor }
            val targets = com.ai.assistance.operit.core.group.GroupPolicy.targets(listeners, requested.filter { it in listeners })
            runGroupQueue(chatId, targets, "", null, ChatTurnOptions())
        }
    }

    fun processGroupTask(chatId: String, task: JSONObject) {
        if (isGroupRunning(chatId) || messageProcessingDelegate.isChatLoading(chatId)) return
        launchGroup(chatId) {
            com.ai.assistance.operit.core.group.GroupLifecycle.requireOpen(context, chatId)
            val board = com.ai.assistance.operit.core.tasks.TaskBoard(context, chatId, true)
            val id = task.getString("id")
            val dispatch = task.getJSONObject("dispatch")
            val event = dispatch.getString("id")
            val actor = dispatch.getString("recipient")
            val claimed = board.claim(id, event) ?: return@launchGroup
            try {
                val group = requireNotNull(resolveTargetGroupForChat(chatId))
                check(!group.ended) { "群已结束" }
                check(group.members.any { it.characterCardId == actor }) { "接手助手已离群" }
                check(com.ai.assistance.operit.core.group.GroupModeration(context).mayPublish(chatId, actor)) { "接手助手被禁言" }
                groupStore.update(chatId) { it.put("task_event", board.prompt(claimed)).put("task_actor", actor) }
                runGroupQueue(chatId, listOf(actor), "", null, ChatTurnOptions())
                check(groupStore.read(chatId).optString("last_member_error").isBlank()) { groupStore.read(chatId).optString("last_member_error") }
                board.dispatchState(id, event, "handled", "本次处理结束，事项是否完成请看实际进展")
            } catch (cancelled: CancellationException) {
                board.dispatchState(id, event, "interrupted", "执行中断，已保留实际进展")
                throw cancelled
            } catch (failure: Exception) {
                board.dispatchState(id, event, "failed", failure.message.orEmpty().take(600))
                throw failure
            } finally {
                groupStore.update(chatId) { it.remove("task_event"); it.remove("task_actor") }
            }
        }
    }

    private suspend fun runGroupQueue(
        chatId: String, initial: List<String>, userText: String, prebuilt: String?, options: ChatTurnOptions
    ) {
        val games = com.ai.assistance.operit.core.group.GroupGames(context)
        val initialGamePending = games.pending(chatId)
        val queue = java.util.ArrayDeque(if (initialGamePending != null) initialGamePending.filter { it != "user" } else initial)
        var first = true
        var quiet = 0
        var completedTurns = 0
        val startedAt = System.currentTimeMillis()
        groupStore.update(chatId) { it.remove("last_member_error") }
        groupStore.log(chatId, "开始", "本轮安排 ${initial.size} 位助手")
        while (queue.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val group = requireNotNull(resolveTargetGroupForChat(chatId)) { "群聊已删除" }
            if (group.ended) return
            val members = group.members.sortedBy { it.orderIndex }
            val ids = members.map { it.characterCardId }
            val actor = queue.removeFirst()
            if (games.pending(chatId)?.let { actor !in it } == true) continue
            if (actor !in ids) { groupStore.log(chatId, "跳过", "待发言助手已离群", actor); continue }
            if (com.ai.assistance.operit.core.group.GroupModeration(context).manuallyMuted(chatId, actor)) {
                groupStore.log(chatId, "跳过", "助手被禁言", actor); continue
            }
            val cards = members.associate { it.characterCardId to characterCardManager.getCharacterCard(it.characterCardId) }
            val settings = groupStore.read(chatId)
            val limit = settings.optInt("turn_limit", 0)
            val minutes = settings.optInt("minute_limit", 0)
            if ((limit > 0 && completedTurns >= limit) || (minutes > 0 && System.currentTimeMillis() - startedAt >= minutes * 60_000L)) {
                groupStore.update(chatId) { it.put("status", "paused").put("pending", JSONArray(listOf(actor) + queue.toList())) }
                groupStore.log(chatId, "限额", "达到用户设置的本次讨论上限，可继续")
                return
            }
            groupStore.update(chatId) {
                it.put("pending", JSONArray(listOf(actor) + queue.toList())).put("speaker", cards.getValue(actor).name)
                    .put("speaker_id", actor).put("published", false).remove("decision")
            }
            if (!com.ai.assistance.operit.core.engine.EngineWindow.associated(context, actor)) {
                com.ai.assistance.operit.core.group.GroupTools.ensureEnabledOnce(context, actor)
            }
            val gameBefore = games.state(chatId)?.let { com.google.gson.Gson().toJson(it) }
            messageProcessingDelegate.setInputProcessingStateForChat(chatId, InputProcessingState.Processing("${cards.getValue(actor).name} 正在回应"))
            groupStore.log(chatId, "发言", "开始回应", actor)
            try {
                // Resolve before launching, in the member's scope, never the group page's model.
                val engineMember = com.ai.assistance.operit.core.engine.EngineWindow.associated(context, actor)
                val binding = if (engineMember) Pair(null, null) else withContext(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(actor)) {
                    characterCardManager.ensureIndependentSettings(actor)
                    resolveRoleCardChatModelOverrides(actor)
                }
                require(engineMember || !binding.first.isNullOrBlank()) { "请为 ${cards.getValue(actor).name} 选择自己的模型" }
                // A manual compression can be waiting for the previous member's tool chain.
                // Keep this member queued until that boundary is committed or cancelled.
                messageProcessingDelegate.awaitSummaryForChat(chatId)
                sendMessageInternal(promptFunctionType = PromptFunctionType.CHAT,
                chatModelConfigIdOverride = binding.first, chatModelIndexOverride = binding.second,
                isContinuation = !(first && prebuilt != null), skipSummaryCheck = true,
                roleCardIdOverride = actor, chatIdOverride = chatId,
                messageTextOverride = if (engineMember || first && prebuilt != null) userText else "",
                prebuiltMessageContent = if (first) prebuilt else null,
                suppressUserMessageInHistory = true, forceDisableSummary = true,
                enableGroupOrchestration = false, isGroupOrchestrationTurn = true,
                groupParticipantNamesText = buildGroupParticipantNamesText(members, cards), turnOptions = options)
            check(messageProcessingDelegate.isChatLoading(chatId)) { "${cards.getValue(actor).name} 未能启动回应，请检查其个人配置" }
            check(withTimeoutOrNull(180_000) {
                while (messageProcessingDelegate.isChatLoading(chatId)) {
                    if (!groupStore.read(chatId).optBoolean("group_exists", true)) {
                        messageProcessingDelegate.cancelMessageAndWait(chatId)?.join()
                        return@withTimeoutOrNull true
                    }
                    delay(100)
                }
                true
            } == true) { "${cards.getValue(actor).name} 回应超时" }
            check(withTimeoutOrNull(60_000) {
                messageProcessingDelegate.activeStreamingChatIds.first { chatId !in it }; true
            } == true) { "上一位助手尚未释放会话，停止排队以免重复发送" }
            // Report failures against this member; other independently configured members can still reply.
            val state = messageProcessingDelegate.inputProcessingStateByChatId.value[chatId]
            if (state is InputProcessingState.Error) error(state.message)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                messageProcessingDelegate.cancelMessageAndWait(chatId)?.join()
                groupStore.update(chatId) { it.put("last_member_error", "${cards.getValue(actor).name}：${failure.message}") }
                groupStore.log(chatId, "成员失败", "${cards.getValue(actor).name}：${failure.message}", actor)
                uiStateDelegate.showErrorMessage("${cards.getValue(actor).name}：${failure.message}")
                first = false
                continue
            }
            first = false
            completedTurns++
            if (!groupStore.read(chatId).optBoolean("group_exists", true)) return
            com.ai.assistance.operit.core.group.GroupContext.rememberMembers(context, chatId)
            groupStore.log(chatId, "完成", "本轮回应处理结束", actor)
            val decision = groupStore.takeDecision(chatId)
            val action = decision?.optString("action")
            quiet = if (action == "quiet") quiet + 1 else 0
            if (action == "end" || action == "wait_user")
                groupStore.log(chatId, "个人决定", requireNotNull(decision).optString("reason"), actor)
            val current = groupStore.read(chatId)
            val gamePending = games.pending(chatId)
            if (gamePending != null) {
                if (games.state(chatId)?.let { com.google.gson.Gson().toJson(it) } == gameBefore) {
                    groupStore.log(chatId, "等待", "助手未提交游戏行动，请查看工具结果后继续", actor)
                    return
                }
                queue.clear()
                gamePending.filter { it != "user" }.forEach { queue.addLast(it) }
            }
            if (gamePending == null) {
                val next = com.ai.assistance.operit.core.group.GroupTurnPolicy.next(
                    queue.toList(), ids.toSet(), actor, current.optBoolean("published"))
                queue.clear()
                next.forEach { queue.addLast(it) }
                if (action == "wait_user") groupStore.log(chatId, "等待用户", decision?.optString("reason").orEmpty(), actor)
            }
            groupStore.update(chatId) { it.put("pending", JSONArray(queue.toList())) }
            maybeSummarizeAfterGroupRound(chatId, PromptFunctionType.CHAT)
            if (queue.isNotEmpty()) delay(500)
        }
        groupStore.log(chatId, "结束", if (quiet > 0) "成员选择暂时安静" else "本轮发言完成")
    }

    private suspend fun buildGroupParticipantNamesText(
        members: List<com.ai.assistance.operit.data.model.GroupMemberConfig>,
        memberCardsById: Map<String, CharacterCard>
    ): String {
        val useEnglish = !LocaleUtils.usesChineseContent(context)
        val userName = displayPreferencesManager.globalUserName.first()?.trim().orEmpty()
        val formattedUserName = if (userName.isNotBlank()) {
            "$userName（用户）"
        } else {
            "用户（用户）"
        }
        val participantNames = members
            .sortedBy { it.orderIndex }
            .mapNotNull { member -> memberCardsById[member.characterCardId]?.name?.trim()?.takeIf { it.isNotBlank() } }
            .distinct() + formattedUserName
        return if (useEnglish) participantNames.joinToString(", ") else participantNames.joinToString("、")
    }

    private suspend fun buildBoundGroupParticipantNamesText(chatId: String): String? {
        val groupId = chatHistoryDelegate.chatHistories.value
            .firstOrNull { it.id == chatId }
            ?.characterGroupId
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val group = characterGroupCardManager.getCharacterGroupCard(groupId) ?: return null
        val memberCardsById = group.members
            .associate { member ->
                member.characterCardId to characterCardManager.getCharacterCard(member.characterCardId)
            }
        return buildGroupParticipantNamesText(
            members = group.members,
            memberCardsById = memberCardsById
        )
    }

    private suspend fun resolveTargetGroupForChat(chatId: String): com.ai.assistance.operit.data.model.CharacterGroupCard? {
        val boundGroupId = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
            .getChatMetadata(chatId)?.characterGroupId ?: return null
        return characterGroupCardManager.getCharacterGroupCard(boundGroupId)
    }

    private fun extractEffectiveSpeechContent(content: String): String {
        val withoutThinking = ChatUtils.removeThinkingContent(content)
        val withoutStatus = ChatMarkupRegex.statusTag.replace(withoutThinking, " ")
        return ChatMarkupRegex.statusSelfClosingTag.replace(withoutStatus, " ").trim()
    }

    private fun shrinkForMemberPrompt(content: String, maxLength: Int = 220): String {
        val normalized = content.replace("\n", " ").trim()
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength) + "..."
    }

    private suspend fun awaitTurnComplete(
        chatId: String,
        targetCounter: Long,
        timeoutMs: Long = 180_000L
    ): Boolean {
        val start = System.currentTimeMillis()
        AppLogger.d(TAG, "等待回合完成: chatId=$chatId, targetCounter=$targetCounter, timeoutMs=$timeoutMs")
        val completed = withTimeoutOrNull(timeoutMs) {
            messageProcessingDelegate.turnCompleteCounterByChatId.first { counters ->
                (counters[chatId] ?: 0L) >= targetCounter
            }
            true
        } ?: false
        val elapsed = System.currentTimeMillis() - start
        AppLogger.d(
            TAG,
            "等待回合完成结果: chatId=$chatId, targetCounter=$targetCounter, completed=$completed, elapsedMs=$elapsed"
        )
        return completed
    }

    private fun restoreIdleIfCurrentlySummarizing(chatId: String) {
        val state = messageProcessingDelegate.inputProcessingStateByChatId.value[chatId]
        if (state is InputProcessingState.Summarizing) {
            messageProcessingDelegate.setInputProcessingStateForChat(chatId, InputProcessingState.Idle)
        }
    }

    /** Refresh stable chat occupancy and summarize completed autonomous experiences when needed. */
    fun maintainBackgroundContext(chatId: String, roleCardId: String) {
        if (com.ai.assistance.operit.core.engine.EngineWindow.associated(context, roleCardId)) return
        if (summaryJob?.isActive == true || sendTriggeredSummaryJob?.isActive == true ||
            _isSummarizing.value || messageProcessingDelegate.isChatLoading(chatId) ||
            android.os.SystemClock.elapsedRealtime() < (summaryRetryAfter[chatId] ?: 0L)) return
        summaryJob = coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val owner = resolveRoleCardId(chatId, roleCardId)
                withContext(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(owner)) {
                    val model = resolveRoleCardChatModelOverrides(owner)
                    val memory = resolveRoleCardMemoryProfileOverride(owner)
                    val settings = resolveChatContextSettingsForRequest(model.first)
                    // The transient learning/wake request can be much larger than the persisted
                    // conversation. Refresh from saved history before checking its threshold.
                    val window = refreshStableContextWindow(
                        chatId = chatId, roleCardId = owner, promptFunctionType = PromptFunctionType.CHAT,
                        chatModelConfigIdOverride = model.first, chatModelIndexOverride = model.second,
                        memorySpaceIdOverride = memory
                    ) ?: return@withContext
                    if (!settings.enableSummary) return@withContext
                    val messages = chatHistoryDelegate.getRuntimeChatHistory(chatId)
                    if (com.ai.assistance.operit.core.chat.SummaryContextSlice.plan(messages) == null)
                        return@withContext
                    val maxTokens = (settings.effectiveContextLength * 1024).toLong()
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    if (!AIMessageManager.shouldGenerateSummary(
                            messages, window, maxTokens, settings.summaryTokenThreshold.toDouble(),
                            settings.enableSummary, settings.enableSummaryByMessageCount,
                            settings.summaryMessageCountThreshold
                        )) return@withContext
                    val success = summarizeHistory(
                        chatIdOverride = chatId, roleCardIdOverride = owner,
                        chatModelConfigIdOverride = model.first, chatModelIndexOverride = model.second,
                        memorySpaceIdOverride = memory
                    )
                    if (!success) summaryRetryAfter[chatId] = android.os.SystemClock.elapsedRealtime() + 300_000
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                summaryRetryAfter[chatId] = android.os.SystemClock.elapsedRealtime() + 300_000
                AppLogger.e(TAG, "后台经历压缩失败", error)
            } finally {
                summaryJob = null
            }
        }.also { it.start() }
    }

    private suspend fun maybeSummarizeAfterGroupRound(
        chatId: String,
        promptFunctionType: PromptFunctionType
    ) {
        val chatContextSettings =
            resolveChatContextSettingsForRequest(currentChatModelConfigIdOverride)
        if (!chatContextSettings.enableSummary) return

        val currentMessages = chatHistoryDelegate.getRuntimeChatHistory(chatId)
        val currentTokens = tokenStatsDelegate.getLastCurrentWindowSize(chatId)
        val maxTokens =
            (chatContextSettings.effectiveContextLength * 1024)
                .toLong()
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
        val shouldSummarize = AIMessageManager.shouldGenerateSummary(
            messages = currentMessages,
            currentTokens = currentTokens,
            maxTokens = maxTokens,
            tokenUsageThreshold = chatContextSettings.summaryTokenThreshold.toDouble(),
            enableSummary = chatContextSettings.enableSummary,
            enableSummaryByMessageCount = chatContextSettings.enableSummaryByMessageCount,
            summaryMessageCountThreshold = chatContextSettings.summaryMessageCountThreshold
        )
        if (shouldSummarize) {
            // 群组编排后的总结，标记为群聊模式
            summarizeHistory(
                chatIdOverride = chatId,
                chatModelConfigIdOverride = currentChatModelConfigIdOverride,
                chatModelIndexOverride = currentChatModelIndexOverride,
                isGroupChat = true
            )
        }
    }

    private fun resolveRoleCardChatModelOverrides(roleCardId: String): Pair<String?, Int?> {
        val roleCard = runBlocking { characterCardManager.getCharacterCardFlow(roleCardId).first() }
        val bindingMode = CharacterCardChatModelBindingMode.normalize(roleCard.chatModelBindingMode)
        return if (
            bindingMode == CharacterCardChatModelBindingMode.FIXED_CONFIG &&
            !roleCard.chatModelConfigId.isNullOrBlank()
        ) {
            Pair(roleCard.chatModelConfigId, roleCard.chatModelIndex.coerceAtLeast(0))
        } else {
            Pair(null, null)
        }
    }

    private fun resolveRoleCardMemoryProfileOverride(roleCardId: String): String? {
        val roleCard = runBlocking { characterCardManager.getCharacterCardFlow(roleCardId).first() }
        val bindingMode =
            CharacterCardMemoryProfileBindingMode.normalize(roleCard.memoryProfileBindingMode)
        return if (
            bindingMode == CharacterCardMemoryProfileBindingMode.FIXED_PROFILE &&
            !roleCard.memoryProfileId.isNullOrBlank()
        ) {
            roleCard.memoryProfileId
        } else {
            null
        }
    }

    private suspend fun resolveChatContextSettingsForRequest(
        chatModelConfigIdOverride: String?
    ): ChatContextSettings {
        return apiConfigDelegate.resolveChatContextSettings(chatModelConfigIdOverride)
    }

    /**
     * 手动更新记忆
     */
    fun manuallyUpdateMemory() {
        if (_isUpdatingMemory.value) {
            uiStateDelegate.showToast(context.getString(R.string.chat_summarizing_memory))
            return
        }
        coroutineScope.launch {
            val currentChatId = chatHistoryDelegate.currentChatId.value
            val runtimeHistory =
                currentChatId?.let { chatHistoryDelegate.getRuntimeChatHistory(it) }.orEmpty()
            saveMessagesToMemory(
                sourceMessages = runtimeHistory,
                currentChatId = currentChatId,
                emptyToastMessage = context.getString(R.string.chat_history_empty_no_update)
            )
        }
    }

    fun enqueueSelectedMessagesForMemoryAutoSave(selectedMessages: List<ChatMessage>) {
        coroutineScope.launch {
            val currentChatId = chatHistoryDelegate.currentChatId.value
            val userMessages =
                selectedMessages
                    .sortedBy { it.timestamp }
                    .filter { it.sender == "user" }
                    .filter { it.content.isNotBlank() }

            if (currentChatId.isNullOrBlank()) {
                uiStateDelegate.showToast(context.getString(R.string.chat_history_empty_no_update))
                return@launch
            }
            if (userMessages.isEmpty()) {
                uiStateDelegate.showToast(
                    context.getString(R.string.chat_selected_messages_no_user_for_memory)
                )
                return@launch
            }

            try {
                val roleCardId =
                    resolveRoleCardId(
                        chatId = currentChatId,
                        roleCardId = null
                    )
                val profileId =
                    roleCardId?.let { resolveRoleCardMemoryProfileOverride(it) }
                        ?: preferencesManager.activeMemorySpaceIdFlow.first()
                MemoryAutoSaveCandidateRepository(context, profileId)
                    .enqueueSelectedUserMessages(
                        chatId = currentChatId,
                        triggerMessageTimestamps = userMessages.map { it.timestamp }
                    )
                uiStateDelegate.showToast(
                    context.getString(
                        R.string.chat_selected_messages_added_to_memory_queue,
                        userMessages.size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "选中消息加入自动记忆队列失败", e)
                uiStateDelegate.showToast(
                    context.getString(
                        R.string.chat_selected_messages_add_to_memory_queue_failed,
                        e.message ?: ""
                    )
                )
            }
        }
    }

    private suspend fun saveMessagesToMemory(
        sourceMessages: List<ChatMessage>,
        currentChatId: String?,
        emptyToastMessage: String,
        lastContentOverride: String? = null
    ) {
        val enhancedAiService = getEnhancedAiService()
        if (enhancedAiService == null) {
            uiStateDelegate.showToast(context.getString(R.string.chat_ai_service_unavailable_memory))
            return
        }
        if (sourceMessages.isEmpty()) {
            uiStateDelegate.showToast(emptyToastMessage)
            return
        }

        _isUpdatingMemory.value = true
        uiStateDelegate.showToast(context.getString(R.string.chat_summarizing_memory))

        try {
            val owner = resolveRoleCardId(currentChatId, null)
            val attributed = if (currentChatId != null && isGroupChatSession(currentChatId)) {
                com.ai.assistance.operit.core.group.GroupProvenance.forRequest(context, currentChatId, owner, sourceMessages)
            } else sourceMessages
            val history = attributed.map { it.sender to it.content }
            val lastMessageContent =
                lastContentOverride?.takeIf { it.isNotBlank() }
                    ?: attributed.lastOrNull()?.content
                    ?: ""
            val roleCardId =
                resolveRoleCardId(
                    chatId = currentChatId,
                    roleCardId = null
                )
            val memorySpaceIdOverride =
                roleCardId?.let { resolveRoleCardMemoryProfileOverride(it) }

            enhancedAiService.saveConversationToMemoryAsync(
                conversationHistory = history,
                lastContent = lastMessageContent,
                memorySpaceIdOverride = memorySpaceIdOverride,
                onSuccess = {
                    uiStateDelegate.showToast(context.getString(R.string.chat_memory_manually_updated))
                    _isUpdatingMemory.value = false
                },
                onError = { e ->
                    AppLogger.e(TAG, "手动更新记忆失败", e)
                    uiStateDelegate.showToast(
                        context.getString(
                            R.string.chat_manual_update_memory_failed,
                            e.message ?: ""
                        )
                    )
                    _isUpdatingMemory.value = false
                }
            )
        } catch (e: CancellationException) {
            _isUpdatingMemory.value = false
            throw e
        } catch (e: Exception) {
            _isUpdatingMemory.value = false
            AppLogger.e(TAG, "手动更新记忆失败", e)
            uiStateDelegate.showToast(
                context.getString(
                    R.string.chat_manual_update_memory_failed,
                    e.message ?: ""
                )
            )
        }
    }

    /**
     * 手动触发对话总结
     */
    fun manuallySummarizeConversation() {
        if (_isSummarizing.value) {
            uiStateDelegate.showToast(context.getString(R.string.chat_summarizing_please_wait))
            return
        }
        val currentChatId = chatHistoryDelegate.currentChatId.value ?: return
        summaryJob = coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val success = summarizeHistory(
                    chatIdOverride = currentChatId,
                    isGroupChat = isGroupChatSession(currentChatId)
                )
                if (success) {
                    uiStateDelegate.showToast(context.getString(R.string.chat_conversation_summary_generated))
                }
            } finally {
                summaryJob = null
            }
        }.also { it.start() }
    }

    /**
     * 超过压缩阈值时排队，当前工具链继续，结束后总结。
     */
    fun handleTokenLimitExceeded(
        chatId: String?,
        roleCardId: String?,
        isGroupOrchestrationTurn: Boolean,
        groupParticipantNamesText: String?
    ) {
        if (summaryJob?.isActive == true || sendTriggeredSummaryJob?.isActive == true || _isSummarizing.value) return
        AppLogger.d(TAG, "达到压缩阈值，等待当前工具链和回复完成后压缩")
        summaryJob = coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { summarizeHistory(
                chatIdOverride = chatId,
                roleCardIdOverride = roleCardId,
                isGroupChat = isGroupChatSession(chatId),
                isGroupOrchestrationTurn = isGroupOrchestrationTurn,
                groupParticipantNamesText = groupParticipantNamesText
            ) } finally { summaryJob = null }
        }.also { it.start() }
    }

    private suspend fun cancelSummaryStreamingInternal() {
        runCatching {
            getEnhancedAiService()
                ?.getAIServiceForFunction(FunctionType.SUMMARY)
                ?.cancelStreaming()
        }.onFailure { throwable ->
            AppLogger.w(TAG, "取消 SUMMARY 流失败: ${throwable.message}")
        }
        ToolProgressBus.clear()
    }

    /**
     * 取消正在进行的总结操作
     */
    private suspend fun cancelSummaryInternal(targetChatId: String? = null) {
        if (targetChatId == null || summaryPreparationChatId == targetChatId) {
            summaryPreparationJob?.cancel()
            summaryPreparationJob?.join()
        }
        val currentChatId = targetChatId ?: chatHistoryDelegate.currentChatId.value
        val shouldCancelSummary =
            _isSummarizing.value &&
                (targetChatId == null || _summarizingChatId.value == targetChatId)
        val shouldCancelAsyncSummary =
            _isSendTriggeredSummarizing.value &&
                (targetChatId == null || _sendTriggeredSummarizingChatId.value == targetChatId)
        val shouldCancelCurrentSummarizingUi =
            currentChatId != null &&
                messageProcessingDelegate.inputProcessingStateByChatId.value[currentChatId] is InputProcessingState.Summarizing

        if (!shouldCancelSummary &&
            !shouldCancelAsyncSummary &&
            !shouldCancelCurrentSummarizingUi
        ) {
            if (targetChatId == null) {
                // 兜住尚未被协调层标记，但底层 SUMMARY 请求仍在执行的场景。
                cancelSummaryStreamingInternal()
            }
            return
        }

        AppLogger.d(TAG, "取消正在进行的总结操作: chatId=$targetChatId")

        val affectedChatIds = linkedSetOf<String>()
        val jobsToCancel = linkedSetOf<Job>()

        if (shouldCancelSummary) {
            _summarizingChatId.value?.let { affectedChatIds.add(it) }
            summaryJob?.let { jobsToCancel.add(it) }
            summaryJob = null
            _isSummarizing.value = false
            _summarizingChatId.value = null
        }

        if (shouldCancelAsyncSummary) {
            _sendTriggeredSummarizingChatId.value?.let { affectedChatIds.add(it) }
            sendTriggeredSummaryJob?.let { jobsToCancel.add(it) }
            sendTriggeredSummaryJob = null
            _isSendTriggeredSummarizing.value = false
            _sendTriggeredSummarizingChatId.value = null
        }

        if (shouldCancelCurrentSummarizingUi) {
            currentChatId?.let { affectedChatIds.add(it) }
        }

        // 先真正取消 SUMMARY 模型请求，再取消协程/清理 UI，避免进度继续推进。
        cancelSummaryStreamingInternal()

        jobsToCancel.forEach { job -> job.cancel() }
        jobsToCancel.forEach { job ->
            try {
                job.join()
            } catch (_: CancellationException) {
            }
        }

        messageProcessingDelegate.refreshGlobalLoadingState()
        affectedChatIds.forEach { chatId ->
            messageProcessingDelegate.setPendingAsyncSummaryUiForChat(chatId, false)
            messageProcessingDelegate.setSuppressIdleCompletedStateForChat(chatId, false)
            messageProcessingDelegate.setInputProcessingStateForChat(
                chatId,
                InputProcessingState.Idle
            )
        }
    }

    fun cancelSummary() {
        coroutineScope.launch {
            cancelSummaryInternal()
        }
    }

    fun cancelSummaryForChat(chatId: String) {
        coroutineScope.launch {
            cancelSummaryInternal(chatId)
        }
    }

    suspend fun cancelSummaryForDestructiveMutation(chatId: String) {
        cancelSummaryInternal(chatId)
    }

    private fun queueSummaryAfterSend(
        originalChatId: String,
        roleCardId: String,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null
    ) {
        if (sendTriggeredSummaryJob?.isActive == true || summaryJob?.isActive == true || _isSummarizing.value) return
        _isSendTriggeredSummarizing.value = true
        _sendTriggeredSummarizingChatId.value = originalChatId
        messageProcessingDelegate.setPendingAsyncSummaryUiForChat(originalChatId, true)
        sendTriggeredSummaryJob = coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val success = summarizeHistory(
                    chatIdOverride = originalChatId,
                    roleCardIdOverride = roleCardId,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride,
                    memorySpaceIdOverride = memorySpaceIdOverride
                )
                if (!success) summaryRetryAfter[originalChatId] = android.os.SystemClock.elapsedRealtime() + 300_000
            } finally {
                _isSendTriggeredSummarizing.value = false
                _sendTriggeredSummarizingChatId.value = null
                messageProcessingDelegate.setPendingAsyncSummaryUiForChat(originalChatId, false)
                sendTriggeredSummaryJob = null
            }
        }.also { it.start() }
    }

    /**
     * 等待整轮完成后压缩历史，压缩本身不触发新回复
     */
    private suspend fun summarizeHistory(
        chatIdOverride: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null,
        roleCardIdOverride: String? = null,
        isGroupChat: Boolean = false,
        isGroupOrchestrationTurn: Boolean = false,
        groupParticipantNamesText: String? = null
    ): Boolean {
        val targetChatId = chatIdOverride ?: chatHistoryDelegate.currentChatId.value ?: return false
        if (_isSummarizing.value) return false
        _isSummarizing.value = true
        _summarizingChatId.value = targetChatId
        messageProcessingDelegate.setPendingAsyncSummaryUiForChat(targetChatId, true)
        try {
            val owner = resolveRoleCardId(targetChatId, roleCardIdOverride)
            check(!com.ai.assistance.operit.core.engine.EngineWindow.associated(context, owner)) {
                "主体上下文由引擎管理，软件保留原始交流记录"
            }
            return messageProcessingDelegate.withSummaryAfterTurn(targetChatId) {
                kotlinx.coroutines.withContext(com.ai.assistance.operit.data.preferences.AssistantScope.executionContext(owner)) {
                    summarizeIdleHistory(
                        targetChatId,
                        chatModelConfigIdOverride, chatModelIndexOverride, memorySpaceIdOverride,
                        owner, isGroupChat, isGroupOrchestrationTurn, groupParticipantNamesText
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogger.e(TAG, "等待压缩失败", error)
            uiStateDelegate.showErrorMessage(error.message.orEmpty())
            return false
        } finally {
            _isSummarizing.value = false
            _summarizingChatId.value = null
            messageProcessingDelegate.setPendingAsyncSummaryUiForChat(targetChatId, false)
            messageProcessingDelegate.setSuppressIdleCompletedStateForChat(targetChatId, false)
            restoreIdleIfCurrentlySummarizing(targetChatId)
        }
    }

    private suspend fun summarizeIdleHistory(
        chatIdOverride: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null,
        roleCardIdOverride: String? = null,
        isGroupChat: Boolean = false,
        isGroupOrchestrationTurn: Boolean = false,
        groupParticipantNamesText: String? = null
    ): Boolean {
        val currentChatId = requireNotNull(chatIdOverride)
        messageProcessingDelegate.setSuppressIdleCompletedStateForChat(currentChatId, true)
        messageProcessingDelegate.setInputProcessingStateForChat(
            currentChatId,
            InputProcessingState.Summarizing(context.getString(R.string.chat_compressing_history))
        )
        val owner = resolveRoleCardId(currentChatId, roleCardIdOverride)
        check(!com.ai.assistance.operit.core.engine.EngineWindow.associated(context, owner)) {
            "主体上下文由引擎管理"
        }
        val ownerModel = resolveRoleCardChatModelOverrides(owner)
        val effectiveChatModelConfigIdOverride = chatModelConfigIdOverride ?: ownerModel.first
        val effectiveChatModelIndexOverride = chatModelIndexOverride ?: ownerModel.second
        val effectiveMemorySpaceIdOverride = memorySpaceIdOverride ?: resolveRoleCardMemoryProfileOverride(owner)
        val effectiveIsGroupChat = isGroupChat || isGroupChatSession(currentChatId)

        var summarySuccess = false
        val summaryServiceKey = "summary-commit:${java.util.UUID.randomUUID()}"
        try {
            val service = EnhancedAIService.getChatInstance(context, summaryServiceKey)

            val currentMessages =
                chatHistoryDelegate.getRuntimeChatHistory(currentChatId).map { it.copy() }
            if (currentMessages.isEmpty()) {
                AppLogger.d(TAG, "历史记录为空，无需总结")
                return false
            }

            val slice = com.ai.assistance.operit.core.chat.SummaryContextSlice.plan(currentMessages)
                ?: return false
            val beforeTimestamp = slice.beforeTimestamp
            val afterTimestamp = slice.afterTimestamp
            val summaryConfig = readSummaryConfig(resolveRoleCardId(currentChatId, roleCardIdOverride))
            val summaryMessage =
                AIMessageManager.summarizeMemory(
                    service,
                    slice.source,
                    false,
                    effectiveIsGroupChat,
                    summaryConfig,
                    chatId = currentChatId,
                    roleCardId = resolveRoleCardId(currentChatId, roleCardIdOverride),
                    chatModelConfigIdOverride = effectiveChatModelConfigIdOverride,
                    chatModelIndexOverride = effectiveChatModelIndexOverride
                )

            if (summaryMessage != null) {
                chatHistoryDelegate.addSummaryMessage(
                    summaryMessage = summaryMessage,
                    beforeTimestamp = beforeTimestamp,
                    afterTimestamp = afterTimestamp,
                    chatIdOverride = currentChatId,
                    expectedSource = slice.source,
                )

                refreshStableContextWindow(
                    chatId = currentChatId,
                    roleCardId = roleCardIdOverride,
                    groupOrchestrationMode = isGroupOrchestrationTurn,
                    groupParticipantNamesText = groupParticipantNamesText,
                    chatModelConfigIdOverride = effectiveChatModelConfigIdOverride,
                    chatModelIndexOverride = effectiveChatModelIndexOverride,
                    memorySpaceIdOverride = effectiveMemorySpaceIdOverride
                )
                summarySuccess = true
            } else {
                AppLogger.w(TAG, "总结失败或无需总结")
                uiStateDelegate.showErrorMessage(context.getString(R.string.chat_summarize_failed_no_valid_summary))
            }
        } catch (e: CancellationException) {
            // 总结被取消，这是正常流程
            AppLogger.d(TAG, "总结操作被取消")
            throw e // 重新抛出取消异常，让协程正确取消
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成总结时出错: ${e.message}", e)
            uiStateDelegate.showErrorMessage(
                context.getString(
                    R.string.chat_summarize_generation_failed,
                    e.message ?: ""
                )
            )
        } finally {
            EnhancedAIService.releaseChatInstance(summaryServiceKey)
        }
        return summarySuccess
    }

    fun setUiBridge(uiBridge: ChatServiceUiBridge) {
        this.uiBridge = uiBridge
    }

    /** 从当前聊天绑定的模型配置中读取总结配置。 */
    suspend fun readSummaryConfig(assistantId: String? = null): ConversationSummaryConfig {
        return try {
            val functionalConfigManager = FunctionalConfigManager(context, assistantId = assistantId)
            val modelConfigManager = ModelConfigManager(context)
            val functionMappings = functionalConfigManager.functionConfigMappingWithIndexFlow.first()
            val chatMapping = functionMappings[FunctionType.CHAT] ?: FunctionConfigMapping()
            if (chatMapping.configId.isNotBlank()) {
                val config = modelConfigManager.getModelConfigFlow(chatMapping.configId).first()
                ConversationSummaryConfig(
                    globalRules = config.summaryCustomRules.takeIf { it.isNotBlank() },
                    sectionOverrides = config.summarySectionOverrides,
                    dialogueReviewEnabled = config.enableSummaryDialogueReview,
                    dialogueReviewTitle = config.summaryDialogueReviewTitle
                )
            } else {
                ConversationSummaryConfig()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取总结配置失败", e)
            ConversationSummaryConfig()
        }
    }
}
