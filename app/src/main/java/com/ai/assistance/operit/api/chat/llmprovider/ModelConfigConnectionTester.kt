package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AssetCopyUtils
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.MediaPoolManager
import kotlinx.coroutines.CancellationException

enum class ModelConnectionTestType {
    CHAT,
    TOOL_CALL,
    IMAGE,
    AUDIO,
    VIDEO
}

enum class ModelConnectionTestOutcome {
    PASSED,
    UNVERIFIED,
    FAILED
}

data class ModelConnectionTestItem(
    val type: ModelConnectionTestType,
    val outcome: ModelConnectionTestOutcome,
    val error: String? = null
) {
    val success: Boolean
        get() = outcome == ModelConnectionTestOutcome.PASSED
}

data class ModelConnectionTestReport(
    val configId: String,
    val configName: String,
    val providerType: String,
    val requestedModelIndex: Int,
    val actualModelIndex: Int,
    val testedModelName: String,
    val items: List<ModelConnectionTestItem>
) {
    val success: Boolean
        get() = items.none { it.outcome == ModelConnectionTestOutcome.FAILED }

    val verified: Boolean
        get() = items.isNotEmpty() && items.all { it.outcome == ModelConnectionTestOutcome.PASSED }
}

object ModelConfigConnectionTester {
    /**
     * Ask for a real call before returning its result. A fabricated assistant call has no provider
     * signature, and rebuilding the returned call would discard hidden protocol metadata.
     */
    internal suspend fun runToolCallProbe(
        request: suspend (List<PromptTurn>) -> String
    ): ModelConnectionTestOutcome {
        val history = listOf(
            PromptTurn(kind = PromptTurnKind.SYSTEM, content = "You are a helpful assistant."),
            PromptTurn(
                kind = PromptTurnKind.USER,
                content =
                    "Call the echo tool exactly once with the text \"ping\". " +
                        "After the tool replies, respond with its result without calling more tools."
            )
        )
        val assistantResponse = request(history)
        val callContent = toolProbeContent(assistantResponse)
        val call = ChatMarkupRegex.toolCallPattern.findAll(callContent).toList().singleOrNull()
            ?: return ModelConnectionTestOutcome.UNVERIFIED
        if (call.groupValues[2] != "echo" ||
            ChatMarkupRegex.containsAnyToolLikeTag(callContent.removeRange(call.range))) {
            return ModelConnectionTestOutcome.UNVERIFIED
        }

        val body = call.groupValues[3]
        val parameter = ChatMarkupRegex.toolParamPattern.findAll(body).toList().singleOrNull()
            ?: return ModelConnectionTestOutcome.UNVERIFIED
        val echoedText = parameter.groupValues[2].trim()
        if (parameter.groupValues[1] != "text" || echoedText != "ping" ||
            body.removeRange(parameter.range).isNotBlank()) {
            return ModelConnectionTestOutcome.UNVERIFIED
        }

        // Only execute this fixed, local echo. Never dispatch model-selected tools from a probe.
        val toolResultTagName = ChatMarkupRegex.generateRandomToolResultTagName()
        val continuation = request(
            history + listOf(
                PromptTurn(kind = PromptTurnKind.ASSISTANT, content = assistantResponse),
                PromptTurn(
                    kind = PromptTurnKind.TOOL_RESULT,
                    toolName = "echo",
                    content =
                        "<$toolResultTagName name=\"echo\" status=\"success\">" +
                            "<content>$echoedText</content></$toolResultTagName>"
                )
            )
        )
        val reply = toolProbeContent(continuation)
        return if (!ChatMarkupRegex.containsAnyToolLikeTag(reply) &&
            reply.replace(ChatMarkupRegex.anyXmlTag, "").isNotBlank()) {
            ModelConnectionTestOutcome.PASSED
        } else {
            ModelConnectionTestOutcome.UNVERIFIED
        }
    }

    private fun toolProbeContent(response: String): String {
        return ChatUtils.removeThinkingContent(
            response
                .replace(ChatMarkupRegex.metaTag, "")
                .replace(ChatMarkupRegex.thinkSelfClosingTag, "")
                .replace(ChatMarkupRegex.searchSelfClosingTag, "")
        )
            .replace(ChatMarkupRegex.thinkTag, "")
            .replace(ChatMarkupRegex.statusTag, "")
            .replace(ChatMarkupRegex.statusSelfClosingTag, "")
            .trim()
    }

    suspend fun run(
        context: Context,
        modelConfigManager: ModelConfigManager,
        config: ModelConfigData,
        requestedModelIndex: Int = 0,
        onActiveServiceChanged: (AIService?) -> Unit = {}
    ): ModelConnectionTestReport {
        val actualModelIndex = getValidModelIndex(config.modelName, requestedModelIndex)
        val testedModelName = getModelByIndex(config.modelName, actualModelIndex)
        val configForTest = config.copy(modelName = testedModelName)
        val items = mutableListOf<ModelConnectionTestItem>()

        val service =
            AIServiceFactory.createService(
                config = configForTest,
                modelConfigManager = modelConfigManager,
                context = context
            )
        onActiveServiceChanged(service)

        try {
            val parameters = modelConfigManager.getModelParametersForConfig(configForTest.id)

            suspend fun collectResponse(
                history: List<PromptTurn>,
                availableTools: List<ToolPrompt>? = null
            ): String {
                val buffer = StringBuilder()
                service.sendMessage(
                    context,
                    history,
                    parameters,
                    stream = false,
                    availableTools = availableTools,
                    enableRetry = false,
                    recordTokenUsage = false,
                ).collect { chunk -> buffer.append(chunk) }
                return buffer.toString()
            }

            suspend fun runCase(
                type: ModelConnectionTestType,
                block: suspend () -> ModelConnectionTestOutcome
            ) {
                val item =
                    try {
                        ModelConnectionTestItem(type = type, outcome = block())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ModelConnectionTestItem(
                            type = type,
                            outcome = ModelConnectionTestOutcome.FAILED,
                            error = e.message
                        )
                    }
                items.add(item)
            }

            runCase(ModelConnectionTestType.CHAT) {
                collectResponse(listOf(PromptTurn(kind = PromptTurnKind.USER, content = "Hi")))
                ModelConnectionTestOutcome.PASSED
            }

            if (configForTest.enableToolCall) {
                runCase(ModelConnectionTestType.TOOL_CALL) {
                    val availableTools =
                        listOf(
                            ToolPrompt(
                                name = "echo",
                                description = "Echoes the provided text.",
                                parametersStructured =
                                    listOf(
                                        ToolParameterSchema(
                                            name = "text",
                                            type = "string",
                                            description = "Text to echo.",
                                            required = true
                                        )
                                    )
                            )
                        )

                    runToolCallProbe { history ->
                        collectResponse(history, availableTools)
                    }
                }
            }

            if (configForTest.enableDirectImageProcessing) {
                runCase(ModelConnectionTestType.IMAGE) {
                    val imageFile =
                        AssetCopyUtils.copyAssetToCache(context, MediaCapabilityProbe.IMAGE_ASSET_PATH)
                    val imageId = ImagePoolManager.addImage(imageFile.absolutePath)
                    if (imageId == "error") {
                        throw IllegalStateException("Failed to create test image")
                    }
                    try {
                        val prompt =
                            buildString {
                                append(MediaLinkBuilder.image(context, imageId))
                                append("\n")
                                append(MediaCapabilityProbe.IMAGE_PROMPT)
                            }
                        val response =
                            collectResponse(
                                listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt))
                            )
                        if (MediaCapabilityProbe.matchesImage(response)) {
                            ModelConnectionTestOutcome.PASSED
                        } else {
                            ModelConnectionTestOutcome.UNVERIFIED
                        }
                    } finally {
                        ImagePoolManager.removeImage(imageId)
                        runCatching { imageFile.delete() }
                    }
                }
            }

            if (configForTest.enableDirectAudioProcessing) {
                runCase(ModelConnectionTestType.AUDIO) {
                    val audioFile =
                        AssetCopyUtils.copyAssetToCache(context, MediaCapabilityProbe.AUDIO_ASSET_PATH)
                    val audioId = MediaPoolManager.addMedia(audioFile.absolutePath, "audio/mpeg")
                    if (audioId == "error") {
                        throw IllegalStateException("Failed to create test audio")
                    }
                    try {
                        val prompt =
                            buildString {
                                append(MediaLinkBuilder.audio(context, audioId))
                                append("\n")
                                append(MediaCapabilityProbe.AUDIO_PROMPT)
                            }
                        val response =
                            collectResponse(
                                listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt))
                            )
                        if (MediaCapabilityProbe.matchesAudio(response)) {
                            ModelConnectionTestOutcome.PASSED
                        } else {
                            ModelConnectionTestOutcome.UNVERIFIED
                        }
                    } finally {
                        MediaPoolManager.removeMedia(audioId)
                        runCatching { audioFile.delete() }
                    }
                }
            }

            if (configForTest.enableDirectVideoProcessing) {
                runCase(ModelConnectionTestType.VIDEO) {
                    val videoFile =
                        AssetCopyUtils.copyAssetToCache(context, MediaCapabilityProbe.VIDEO_ASSET_PATH)
                    val videoId = MediaPoolManager.addMedia(videoFile.absolutePath, "video/mp4")
                    if (videoId == "error") {
                        throw IllegalStateException("Failed to create test video")
                    }
                    try {
                        val prompt =
                            buildString {
                                append(MediaLinkBuilder.video(context, videoId))
                                append("\n")
                                append(MediaCapabilityProbe.VIDEO_PROMPT)
                            }
                        val response =
                            collectResponse(
                                listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt))
                            )
                        if (MediaCapabilityProbe.matchesVideo(response)) {
                            ModelConnectionTestOutcome.PASSED
                        } else {
                            ModelConnectionTestOutcome.UNVERIFIED
                        }
                    } finally {
                        MediaPoolManager.removeMedia(videoId)
                        runCatching { videoFile.delete() }
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching { service.cancelStreaming() }
            throw e
        } catch (e: Exception) {
            if (items.none { it.type == ModelConnectionTestType.CHAT }) {
                items.add(
                    ModelConnectionTestItem(
                        type = ModelConnectionTestType.CHAT,
                        outcome = ModelConnectionTestOutcome.FAILED,
                        error = e.message ?: "Unknown error"
                    )
                )
            }
        } finally {
            onActiveServiceChanged(null)
            service.release()
        }

        return ModelConnectionTestReport(
            configId = configForTest.id,
            configName = configForTest.name,
            providerType = configForTest.apiProviderTypeId,
            requestedModelIndex = requestedModelIndex,
            actualModelIndex = actualModelIndex,
            testedModelName = testedModelName,
            items = items
        )
    }
}
