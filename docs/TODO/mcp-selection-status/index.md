---
fork: https://github.com/yuan69082-code/Operit
branch: fix/mcp-selection-status
status: validated-awaiting-release
---

# MCP、群聊、待办、手机/软件感知与通话改进

用户报告：未选择的 termux 仍参与启动并报错；已选择的四个远程服务在管理页显示灰色 4/0，工具发现和运行状态不一致。

本次修复启动列表、助手选择、工具发现和连接状态的衔接。保留各助手独立选择，共享连接不得因另一助手取消选择而被关闭。

分步记录：

- [01-MCP](01-MCP.md)：选择、连接和发现状态
- [02-Groups](02-Groups.md)：发言归属、结束讨论、成员模型、禁言和主动进群发言
- [03-TaskBoard](03-TaskBoard.md)：独立助手和群聊的私人打算、共同事项、批注与处理事件
- [04-PhoneAwareness](04-PhoneAwareness.md)：手机与应用观察、隐藏事件唤醒、逐应用内容授权
- [05-CallOutcomes](05-CallOutcomes.md)：拒接、取消和挂断统一发送给通话所属助手
- [06-SoftwareAwareness](06-SoftwareAwareness.md)：指定助手接收设置变化、软件访问及聊天停留记录
- [07-Exploration](07-Exploration.md)：按助手独立、默认关闭的自主探索开关与预约
- [08-RoomAndRecall](08-RoomAndRecall.md)：助手房间、日记、阅读角、中文召回与上下文连续性

用户于2026-10-14确认本批功能完成后统一测试、构建、推送和发布。Android Build #71 已通过整批单元测试、APK 构建、签名与更新身份校验，待合并后发布到软件内更新源；验证记录和实测限制见08文档。
