package com.ai.assistance.operit.data.model

/** Client call outcomes distinguish user actions from an unanswered timeout. */
enum class VoiceCallUserOutcome(val text: String, private val facts: String) {
    UNANSWERED("无人应答", "你发起的来电在等待两分钟后仍无人接听，已自动结束并停止响铃。本次从未接通，也未采集本次通话的声音或画面；这不表示用户主动拒接。"),
    HUNG_UP("通话结束", "用户挂断了已接通的通话，收音与摄像头已关闭。"),
    DECLINED("拒接来电，未接通", "用户拒接了你发起的来电，本次从未接通，也未采集本次通话的声音或画面。"),
    CANCELLED("已取消通话，未接通", "用户在接通前取消了本次通话。本次没有接通；这是用户取消，不是你拒接。");

    val message: String get() = "[语音通话]\n$text"
    val modelInstruction: String get() = if (this == UNANSWERED)
        "[客户端通话事件：$facts 这是软件状态，不是用户说的话。你可以自行决定是否发送文字、是否调用 request_voice_call 再次拨打，也可以不回复并直接结束本轮。不要为了交代处理结果而强行发消息，不要假装仍在通话、编造未接通的内容或猜测无人接听的原因。]"
    else
        "[客户端通话事件：$facts 这是软件记录的操作，不是用户说出的原话。请在文字聊天中回应，不要假装还在通话、编造未接通时的通话内容或推测用户操作的原因，也不要复述内部状态。]"
}
