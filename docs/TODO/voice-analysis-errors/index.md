---
repo: https://github.com/yuan69082-code/Operit
---

# 语音分析错误

录音已保存但分析报错。SSE原解析将error:null也判失败，真实错误原因被抹掉。本次修复空error误判，展示HTTP及流式错误的code/type/message并过滤密钥、URL、音频长数据。输出截断及内容拒绝分别说明。原录音和重试保持。

没有用户的实际失败响应，不能认定截图一定由空error引起。本次不更换模型、服务或修改录音。新增空error、实际错误、脱敏、非JSON错误页测试，Android编译和JVM测试由PR流水线验证。
