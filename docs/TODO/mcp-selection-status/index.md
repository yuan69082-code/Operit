---
fork: https://github.com/yuan69082-code/Operit
branch: fix/mcp-selection-status
status: code-complete-awaiting-batch-validation
---

# MCP、群聊、独立待办与手机感知改进

用户报告：未选择的 termux 仍参与启动并报错；已选择的四个远程服务在管理页显示灰色 4/0，工具发现和运行状态不一致。

本次修复启动列表、助手选择、工具发现和连接状态的衔接。保留各助手独立选择，共享连接不得因另一助手取消选择而被关闭。

分步记录：

- [01-MCP](01-MCP.md)：选择、连接和发现状态
- [02-Groups](02-Groups.md)：发言归属、结束讨论、成员模型、禁言和主动进群发言
- [03-TaskBoard](03-TaskBoard.md)：独立助手和群聊的私人打算、共同事项、批注与处理事件
- [04-PhoneAwareness](04-PhoneAwareness.md)：手机与应用观察、隐藏事件唤醒、逐应用内容授权

按用户要求先逐项修改，本批问题全部处理完毕后再统一测试、构建、推送和发布。当前仅完成上述步骤的代码修改与静态差异检查，尚未运行本批测试、构建或手机验证；不单独发布。
