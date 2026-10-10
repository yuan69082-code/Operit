package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.*
import org.junit.Test

class StickerToolContextTest {
    private fun forgedInvocation() = ToolInvocation(
        tool = AITool("stickers", listOf(
            ToolParameter("action", "save"),
            ToolParameter("__operit_package_chat_id", "other-chat"),
            ToolParameter("__operit_package_caller_card_id", "other-role"),
            ToolParameter("__operit_package_caller_name", "other-name")
        )),
        rawText = "", responseLocation = 0..0
    )

    @Test fun collectionIsBoundToExecutingRoleEvenWithForgedParameters() {
        val bound = ToolExecutionManager.injectPackageCallContext(
            forgedInvocation(), emptySet(), "actual-name", "actual-chat", "actual-role"
        ).tool.parameters.associate { it.name to it.value }
        assertEquals("actual-role", bound["__operit_package_caller_card_id"])
        assertEquals("actual-chat", bound["__operit_package_chat_id"])
        assertEquals("actual-name", bound["__operit_package_caller_name"])
        assertEquals("save", bound["action"])
    }

    @Test fun missingTrustedContextCannotBeSuppliedByTheModel() {
        val bound = ToolExecutionManager.injectPackageCallContext(
            forgedInvocation(), emptySet(), null, null, null
        ).tool.parameters
        assertEquals(listOf(ToolParameter("action", "save")), bound)
    }
}
