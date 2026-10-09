package com.ai.assistance.operit.ui.features.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallPublicTextTest {
    @Test fun softwareStateDoesNotReachSpeech() {
        assertEquals("你好", VoiceCallPublicText.clean("<operit_state>mode=voice_call\nprivate context</operit_state>你好<voice_call_quiet/>"))
    }

    @Test fun softwareStateAcrossSentencesIsSuppressed() {
        val cleaner = VoiceCallPublicText.StreamCleaner()
        assertEquals("你好", cleaner.clean("你好<operit_state>内部状态。"))
        assertEquals("", cleaner.clean("下一段也不能朗读。"))
        assertEquals("我在", cleaner.clean("</operit_state>我在"))
        assertEquals("继续说吧", cleaner.clean("继续说吧"))
    }

    @Test fun runtimeAndQuietControlsAreHiddenButNormalTextSurvives() {
        val cleaner = VoiceCallPublicText.StreamCleaner()
        assertEquals("", cleaner.clean("[运行状态：正在通话。"))
        assertEquals("我听着呢", cleaner.clean("输入为转写]我听着呢<voice_call_wait seconds=\"3\"/>"))
        assertEquals("", cleaner.clean("`mode=voice_call`<voice_call_quiet/>"))
    }
}
