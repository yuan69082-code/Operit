package com.ai.assistance.operit.core.chat.plugins

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class MessageProcessingPluginRegistryTest {
    private fun params(message: String) = MessageProcessingHookParams(
        context = mock(Context::class.java),
        enhancedAIService = mock(EnhancedAIService::class.java),
        chatId = "subject-chat",
        messageContent = message,
        chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "原来的历史")),
        workspacePath = null,
        maxTokens = 4096,
        tokenUsageThreshold = 0.8,
        onNonFatalError = {}
    )

    @Test fun ownedWakesNeverProbeAPluginThatMatchesEveryMessage() = runBlocking {
        var probes = 0
        val plugin = object : MessageProcessingPlugin {
            override val id = "test.native-wake-does-not-probe"
            override suspend fun createExecutionIfMatched(params: MessageProcessingHookParams): MessageProcessingExecution {
                probes++
                return MessageProcessingExecution(
                    object : MessageProcessingController { override fun cancel() = Unit },
                    stream { emit("plugin took over") }
                )
            }
        }
        MessageProcessingPluginRegistry.register(plugin)
        try {
            // These sources share CompanionRuntime's host-owned turn in production. Even a
            // plugin whose matching rule accepts every nonempty input must not see a probe.
            listOf("system wake", "phone perception", "exploration", "reading").forEach { source ->
                assertNull(MessageProcessingPluginRegistry.createExecutionIfMatched(params(source), nativeWake = true))
            }
            assertEquals(0, probes)
        } finally {
            MessageProcessingPluginRegistry.unregister(plugin.id)
        }
    }

    @Test fun interactivePluginKeepsOriginalInputHistoryAndLiteralProtocolExamples() = runBlocking {
        val original = params("请解释这个协议的格式")
        val example = "<operit_state_report>{\"frame_id\":\"example\",\"updates\":[],\"create\":[]}</operit_state_report>"
        var received: MessageProcessingHookParams? = null
        val expected = MessageProcessingExecution(
            object : MessageProcessingController { override fun cancel() = Unit },
            stream { emit(example) }
        )
        val plugin = object : MessageProcessingPlugin {
            override val id = "test.plugin-keeps-plain-input"
            override suspend fun createExecutionIfMatched(params: MessageProcessingHookParams): MessageProcessingExecution {
                received = params
                return expected
            }
        }
        MessageProcessingPluginRegistry.register(plugin)
        try {
            val execution = MessageProcessingPluginRegistry.createExecutionIfMatched(original, nativeWake = false)
            assertSame(original, received)
            assertSame(original.chatHistory, received!!.chatHistory)
            assertSame(expected, execution)
            val visible = StringBuilder()
            execution!!.stream.collect { visible.append(it) }
            assertEquals(example, visible.toString())
            assertEquals("请解释这个协议的格式", original.messageContent)
        } finally {
            MessageProcessingPluginRegistry.unregister(plugin.id)
        }
    }

    @Test fun existingOneArgumentEntryStillAllowsOrdinaryPluginMatching() = runBlocking {
        var probes = 0
        val plugin = object : MessageProcessingPlugin {
            override val id = "test.plugin-existing-entry"
            override suspend fun createExecutionIfMatched(params: MessageProcessingHookParams): MessageProcessingExecution? {
                probes++
                return null
            }
        }
        MessageProcessingPluginRegistry.register(plugin)
        try {
            assertNull(MessageProcessingPluginRegistry.createExecutionIfMatched(params("ordinary input")))
            assertEquals(1, probes)
        } finally {
            MessageProcessingPluginRegistry.unregister(plugin.id)
        }
    }
}
