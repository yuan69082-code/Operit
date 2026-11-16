package com.ai.assistance.operit.core.chat

import android.app.Application
import com.ai.assistance.operit.core.companion.AssistantRoom
import com.ai.assistance.operit.core.companion.OperitStorageTestRunner
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SummaryAddressTest {
    @Test fun addressPersistsPerAssistantWithoutChangingOriginalText() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cards = CharacterCardManager.getInstance(context)
        val first = cards.createCharacterCard(CharacterCard("", "甲"))
        val other = cards.createCharacterCard(CharacterCard("", "乙"))
        AssistantRoom(context, first).summaryAddress("园园")
        assertEquals("园园", AssistantRoom(context, first).read().getString("summary_user_address"))
        val instruction = SummaryAddress.instruction(context, first)
        assertTrue(instruction.contains("‘我’"))
        assertTrue(instruction.contains("园园"))
        assertTrue(instruction.contains("第三方"))
        assertFalse(SummaryAddress.instruction(context, other).contains("园园"))
        val original = "用户提出看书，我尚未同意；第三方小林说他已经读完。"
        val once = SummaryReview.finalize(original) { "<summary_review>approve</summary_review>" }
        val twice = SummaryReview.finalize(once) { "<summary_review>approve</summary_review>" }
        assertEquals(original, twice)
        assertTrue(SummaryAddress.instruction(context, other).contains("不因此中断压缩"))
    }
}
