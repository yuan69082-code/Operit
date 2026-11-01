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
    @Test fun activeEmptyThoughtHasExplicitMarkerAndRemainsAnEmptyEditor() {
        val encoded = UserInnerThought.encode("", "我回来了", includeEmpty = true)
        assertTrue(encoded.contains("[人类思考链]"))
        assertTrue(encoded.contains(UserInnerThought.EMPTY))
        assertTrue(encoded.contains("【正文回复】"))
        val parts = UserInnerThought.decode(encoded)
        assertTrue(parts.present)
        assertEquals("", parts.thought)
        assertEquals("我回来了", parts.spoken)
    }

    @Test fun disabledProjectionRemovesAllThoughtBlocksIncludingMergedMessages() {
        val source = UserInnerThought.encode("不发送甲", "正文甲") + "\n" +
            UserInnerThought.encode("不发送乙", "正文乙")
        assertEquals("正文甲\n正文乙", UserInnerThought.forModel(source, false))
        assertEquals(source, UserInnerThought.forModel(source, true))
    }

    @Test fun legacyMessagesDisplayNormallyButModelReceivesNewExplanation() {
        val old = "[用户双栏消息：心里话是用户未说出口的想法，AI可以理解，但不要当作用户已经说出口的话；正文才是实际说出的话。这不是AI的思考过程。]\n<user_inner_thought>想你</user_inner_thought>\n[正文／实际说出的话]\n你好"
        assertEquals("想你", UserInnerThought.decode(old).thought)
        assertEquals("你好", UserInnerThought.forModel(old, false))
        val projected = UserInnerThought.forModel(old, true)
        assertFalse(projected.contains("这不是AI"))
        assertFalse(projected.contains("不要当作"))
        assertEquals(UserInnerThought.encode("想你", "你好"), projected)
    }
}
