package com.ai.assistance.operit.util

import org.junit.Assert.*
import org.junit.Test

class UserInnerThoughtTest {
    @Test fun ordinaryHistoryIsUnchanged() {
        val text = "普通消息\n<attachment id=\"file\" />"
        assertEquals(text, UserInnerThought.encode("", text))
        assertEquals(UserInnerThought.Parts("", text), UserInnerThought.decode(text))
    }

    @Test fun preservesLiteralMarkupAndUnicodeWithoutCreatingAppTags() {
        val thought = "想说 🐱 <think>秘密</think> &lt; </user_inner_thought>\n<attachment id=\"x\" />"
        val spoken = "嘿嘿\n第二行"
        val encoded = UserInnerThought.encode(thought, spoken)
        assertFalse(encoded.contains("<think>"))
        assertFalse(encoded.contains("<attachment"))
        val decoded = UserInnerThought.decode(encoded)
        assertEquals(thought, decoded.thought)
        assertEquals(spoken, decoded.spoken)
        assertEquals(spoken, encoded.substring(decoded.spokenOffset))
    }

    @Test fun thoughtOnlySurvivesHistoryDisplayTrimming() {
        val decoded = UserInnerThought.decode(UserInnerThought.encode("没有说出口", "").trim())
        assertEquals("没有说出口", decoded.thought)
        assertEquals("", decoded.spoken)
    }

    @Test fun replyAndAttachmentContextRemainOutsideThought() {
        val prefix = "<reply>之前的话</reply>\n"
        val body = "你好\n<attachment id=\"photo\" />"
        val decoded = UserInnerThought.decode(prefix + UserInnerThought.encode("心里话", body))
        assertEquals("心里话", decoded.thought)
        assertEquals(prefix + body, decoded.spoken)
    }

    @Test fun mergedMessagesRetainBothAttributions() {
        val second = UserInnerThought.encode("第二个想法", "第二句")
        val first = UserInnerThought.decode(UserInnerThought.encode("第一个想法", "第一句") + "\n" + second)
        assertEquals("第一个想法", first.thought)
        val rest = UserInnerThought.decode(first.spoken)
        assertEquals("第二个想法", rest.thought)
        assertEquals("第一句\n第二句", rest.spoken)
    }
}
