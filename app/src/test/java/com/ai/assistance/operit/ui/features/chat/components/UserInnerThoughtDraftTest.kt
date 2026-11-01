package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ai.assistance.operit.util.UserInnerThought
import org.junit.Assert.*
import org.junit.Test

class UserInnerThoughtDraftTest {
    @Test fun spokenSelectionAndImeCompositionRoundTrip() {
        var value = TextFieldValue(UserInnerThought.encode("想法", "你好"))
        UserInnerThoughtDraft(value) { value = it }.changeSpoken(
            TextFieldValue("你好世界", TextRange(3), TextRange(2, 4)))
        val restored = UserInnerThoughtDraft(value) {}
        assertEquals("想法", restored.thought)
        assertEquals(TextRange(3), restored.spoken.selection)
        assertEquals(TextRange(2, 4), restored.spoken.composition)
        assertEquals("你好世界", restored.spoken.text)
    }

    @Test fun removingThoughtRestoresOrdinaryDraftAndCursor() {
        var value = TextFieldValue("正文", TextRange(1))
        UserInnerThoughtDraft(value) { value = it }.changeThought("想法")
        assertEquals("正文", UserInnerThoughtDraft(value) {}.spoken.text)
        UserInnerThoughtDraft(value) { value = it }.changeThought("")
        assertEquals(TextFieldValue("正文", TextRange(1)), value)
    }

    @Test fun clearingDraftClearsBothEditors() {
        val cleared = UserInnerThoughtDraft(TextFieldValue("")) {}
        assertEquals("", cleared.thought)
        assertEquals("", cleared.spoken.text)
    }
}
