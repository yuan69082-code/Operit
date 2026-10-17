package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.junit.Assert.*
import org.junit.Test

class CurrentPerceptionContextTest {
    private fun user(text: String) = PromptTurn(kind = PromptTurnKind.USER, content = text)
    private fun snapshot(id: Int, body: String) =
        """<attachment id="message_insert_extra_bundle_$id" filename="Time:$id" size="100">$body</attachment>"""

    @Test fun removesOldObservationsButKeepsChatMemoriesAndManualAttachments() {
        val manual = """<attachment id="photo" filename="report.txt">user file</attachment>"""
        val previous = user("old message " + snapshot(1, "【屏幕文本】\nold screen\n\n【相关记忆】\nour agreement") + manual)
        val current = user("new message " + snapshot(2, "【屏幕文本】\ncurrent screen"))
        val turns = listOf(previous, current)
        val result = CurrentPerceptionContext.retainLatest(turns)
        assertFalse(result[0].content.contains("old screen"))
        assertTrue(result[0].content.contains("old message"))
        assertTrue(result[0].content.contains("our agreement"))
        assertTrue(result[0].content.contains(manual))
        assertEquals(current, result[1])
        assertEquals(previous, turns[0])
        assertTrue(turns[0].content.contains("old screen"))
    }

    @Test fun currentWakeRemainsVisibleAcrossToolContinuations() {
        val current = user(snapshot(1, "[Screen Text]\ncurrent screen"))
        val tool = PromptTurn(kind = PromptTurnKind.TOOL_RESULT, content = "tool output")
        assertEquals(listOf(current, tool), CurrentPerceptionContext.retainLatest(listOf(current, tool)))
    }

    @Test fun noNewObservationDoesNotResurrectAnOldScreen() {
        val result = CurrentPerceptionContext.retainLatest(listOf(
            user("old " + snapshot(1, "【屏幕文本】\nold screen")),
            user("new input without device observation")
        ))
        assertEquals("old ", result[0].content)
        assertEquals("new input without device observation", result[1].content)
    }

    @Test fun englishMemorySectionAndLegacyDeviceAttachmentsAreHandled() {
        val legacy = """<attachment id='message_insert_extra_battery_1'>old battery</attachment>"""
        val old = user(legacy + snapshot(1, "[Current Screen App]\nold app\n\n[Related Memories]\nshared plan"))
        val result = CurrentPerceptionContext.retainLatest(listOf(old, user("new")))
        assertFalse(result[0].content.contains("old battery"))
        assertFalse(result[0].content.contains("old app"))
        assertTrue(result[0].content.contains("shared plan"))
        assertFalse(result[0].content.contains("size="))
    }

    @Test fun repeatedPreparationDoesNotChangeTheResultOrTouchOtherTurnKinds() {
        val assistant = PromptTurn(kind = PromptTurnKind.ASSISTANT, content = "quoted " + snapshot(1, "example"))
        val turns = listOf(user(snapshot(1, "old")), assistant, user(snapshot(2, "latest")))
        val result = CurrentPerceptionContext.retainLatest(turns)
        assertEquals(result, CurrentPerceptionContext.retainLatest(result))
        assertEquals(assistant, result[1])
    }
}
