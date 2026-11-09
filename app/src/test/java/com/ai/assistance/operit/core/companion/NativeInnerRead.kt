package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.RuntimeContextSections
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.assertTrue

/** Simulates a model requesting a missing page, using the registered production tool and
 * the exact delivered-result binding. No direct database writes or forged read hashes. */
internal fun nativeInnerRead(context: Context, frame: InternalStateFrame, section: String, id: String? = null): InternalStateFrame {
    val handler = AIToolHandler.getInstance(context)
    CompanionTools.register(handler, context)
    val result = handler.executeTool(AITool("companion_internal_state", (mapOf(
        "action" to "get", "section" to section,
        "__operit_package_chat_id" to frame.chatId, "__operit_package_caller_card_id" to frame.roleId
    ) + if (id == null) emptyMap() else mapOf("id" to id)).map { ToolParameter(it.key, it.value) }))
    assertTrue(result.error, result.success)
    val turns = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "读取后再决定")),
        RuntimeContextSections(instructions = "模拟正常请求", internalStateFrame = frame)) +
        PromptTurn(PromptTurnKind.TOOL_RESULT, result.result.toString(), result.toolName)
    return requireNotNull(CurrentRequestContext.internalStateFrame(
        SubjectToolMaterials.attach(turns, System.currentTimeMillis(), listOf(result), context)))
}
