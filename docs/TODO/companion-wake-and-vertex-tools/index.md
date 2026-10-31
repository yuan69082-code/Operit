---
fork: https://github.com/yuan69082-code/Operit
branch: fix/companion-wake-and-vertex-tools
---

# 感知唤醒与 Vertex 工具调用修复

日志暴露了隐藏消息显示、存储锁阻塞主线程、调度中断与主动暂停混用、动态工具参数缺少结构、状态条件报告不匹配以及 HTTP 429 重试问题。

保留已发布的数据与工具接口。修改范围限于聊天展示、Companion 调度与文件访问、Gemini 请求适配和内部状态报告。复用 PR 44 的工具结果边界修复和 PR 38 的状态诊断，不引入旧分支的其他改动。

实施与验证见 [01-repair.md](01-repair.md)。
