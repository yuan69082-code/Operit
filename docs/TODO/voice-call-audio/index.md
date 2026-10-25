---
repo: https://github.com/yuan69082-code/Operit
---

# 通话无声排查

用户最终确认 ElevenLabs 语音条正常，通话也有回复文字，只有声音缺失。尚未取得设备日志，不能断言设备实际选择了哪个输出。

已确认队列播放器会吞掉 prepare/start 异常，把不存在或空音频当作成功；字幕又只在播放开始后显示，使播放失败难以识别。

持续录音进入 MODE_IN_COMMUNICATION，HTTP TTS 使用 USAGE_VOICE_COMMUNICATION，但旧实现从未选择通信输出。增加会话期间的路由管理，无外接设备时使用内置扬声器，外接耳机优先并保持已有耳机选择，处理插拔，结束后释放请求并恢复模式。Android 26–30 使用该平台的 speakerphone API，31 以上使用 communicationDevice API。

同时调整通话字幕交接、播放器失败传播和悬浮窗口错误显示，保持原有语音配置与服务选择。为缺失、空音频和路由选择添加回归测试；通过主分支已有双端发布流程生成应用内更新，继续使用固定签名。没有调用用户 ElevenLabs 凭据或消耗其额度，实际设备播放仍需更新后验证。
