---
fork: https://github.com/yuan69082-code/Operit
branch: fix/mcp-selection-status
status: code-complete-awaiting-batch-validation
---

# MCP 选择与运行状态修复

用户报告：未选择的 termux 仍参与启动并报错；已选择的四个远程服务在管理页显示灰色 4/0，工具发现和运行状态不一致。

本次修复启动列表、助手选择、工具发现和连接状态的衔接。保留各助手独立选择，共享连接不得因另一助手取消选择而被关闭。

步骤见 [01-MCP](01-MCP.md)。按用户要求先逐项修改，本批问题全部处理完毕后再统一测试、构建、推送和发布。本步骤不单独发布。
