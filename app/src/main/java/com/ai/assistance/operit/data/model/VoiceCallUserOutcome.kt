package com.ai.assistance.operit.data.model

/** User actions are distinct from AI refusal, AI withdrawal and an unanswered timeout. */
enum class VoiceCallUserOutcome(val text: String, private val facts: String) {
    HUNG_UP("通话结束", "用户挂断了已接通的通话，收音与摄像头已关闭。"),
    DECLINED("拒接来电，未接通", "用户拒接了你发起的来电，本次从未接通，也未采集本次通话的声音或画面。"),
    CANCELLED("已取消通话，未接通", "用户在接通前取消了本次通话。本次没有接通；这是用户取消，不是你拒接。");

    val message: String get() = "[语音通话]\n$text"
    val modelInstruction: String get() =
        "[客户端通话事件：$facts 这是软件记录的操作，不是用户说出的原话。请在文字聊天中回应，不要假装还在通话、编造未接通时的通话内容或推测用户操作的原因，也不要复述内部状态。]"
}
