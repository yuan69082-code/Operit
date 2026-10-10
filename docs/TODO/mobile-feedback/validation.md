# 验证与发布记录

已发布个人版 **1.12.2+46**，北京时间2026年10月11日00:03。

- 代码提交：acc4a63183caee7b4a6ad8249c9fccb411a9c1d0。
- [Android Tests 第40轮](https://github.com/yuan69082-code/Operit/actions/runs/38064764975)：成功；1367项，1361通过、0失败、6项原有LaTeX分句测试跳过。报告已核对，新增10项通过。
- [Android Build 第46轮](https://github.com/yuan69082-code/Operit/actions/runs/38064764955)：测试与 assembleDebug 成功，Gradle用时16分34秒。
- APK v2签名有效，证书SHA-256为 `11aab8ecd6c292e507fe4d75c9f6ae7bb6da535262b91d8c9618af815c4992dc`，与原个人版一致。
- [发布页](https://github.com/yuan69082-code/Operit/releases/tag/v1.12.2%2B46)已作为 latest；上传的 app-debug.apk 为441395253字节，SHA-256为 `d8fc7fd63d85cafe88774ed6fc051d5d0d90f01858042a1bab80671cc793636f`。
- 本地XC参考源码 a38a0a3 的情绪、互动时间窗和MCP协议测试：19通过、0失败。未修改XC或调用线上写入接口。

检查还覆盖主动活动最终朗读过滤、取消完成后再接收用户消息、忙碌队列遇到主动活动时提前取消、录音期间摄像头回调不丢弃分段录音。

仍需手机验证真实麦克风和转写服务、模型摘要审阅时间、后台服务存活和用户现有XC连接。没有通过编译结果推断这些已验证，也不承诺具体账单降幅；减量措施为有界摘要、短资料预览、减少重复状态与能力清单、压缩互斥和失败冷却。
