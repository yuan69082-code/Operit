package com.ai.assistance.operit.util

import org.junit.Assert.*
import org.junit.Test
import com.ai.assistance.operit.data.model.VoiceCallUserOutcome

class VoiceMessageDisplayTest {
    @Test fun voiceMessageShowsOnlySourceAndSpeech() {
        val full = "[语音条转写] 【原话】你好，今天好吗？\n【声源判断】matched\n【声音】轻声\n【环境音】风声"
        assertEquals("[语音条]\n你好，今天好吗？", VoiceCallMessageText.forDisplay(full))
    }
    @Test fun directSendPreservesOriginalTranscriptAndShowsAnalysisFailure() {
        val original = "[语音条转写] 【原话】今天想见你\n【声源判断】matched\n【声音】轻声"
        val sent = original + VoiceCallMessageText.LOCAL_ANALYSIS_FAILED_NOTICE
        assertTrue(sent.startsWith(original))
        assertEquals("[语音条]\n今天想见你\n[本地声学分析失败]", VoiceCallMessageText.forDisplay(sent))
        assertFalse(VoiceCallMessageText.forDisplay(sent).contains("声源判断"))
    }
    @Test fun successfulReanalysisDoesNotShowFailureNotice() {
        val sent = "[语音条转写] 【原话】你好\n【声源判断】matched\n【本地声学测量】音量-30 dBFS"
        assertEquals("[语音条]\n你好", VoiceCallMessageText.forDisplay(sent))
    }
    @Test fun callTranscriptsKeepTheirCallLabel() {
        assertEquals("[语音通话]\n你好", VoiceCallMessageText.forDisplay("[语音通话转写] 【原话】你好\n【声源判断】matched"))
    }
    @Test fun unansweredIsNotAUserDeclineAndAllowsRedial() {
        val outcome = VoiceCallUserOutcome.UNANSWERED
        assertEquals("[语音通话]\n无人应答", outcome.message)
        assertTrue(outcome.modelInstruction.contains("不表示用户主动拒接"))
        assertTrue(outcome.modelInstruction.contains("request_voice_call"))
        assertTrue(outcome.modelInstruction.contains("不回复"))
    }
}
