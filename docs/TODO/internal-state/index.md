---
repo: https://github.com/yuan69082-code/Operit
base: 6d690855a0f202ed29acbffdd1e949d342045329
branch: feat/internal-state
---

# Internal State

最新 main 已包含助手空间和自主探索意向。现有 XC 是外部状态来源，普通唤醒仍限定联系判断。本次增加 Operit 自有事件计算系统，不迁移或删除 XC 数据。

1. 动态维度、宿主事件、时间恢复、版本和作用域隔离
2. 复用 CompanionStore、ThoughtPool、ReadingRoom、ExplorationStore 和私人待办，向聊天及所有唤醒提供状态驱动的候选排序
3. 助手空间增加观察入口、双重私密可见、趋势、事件和演化记录
4. 核心计算及决策差异测试，检查最新远端后提交推送，触发现有双端构建流程

只参考 Orbis 的状态持续、宿主计算与共享许可思想，不复制其 AGPL 实现。新模块默认关闭，由每位助手的总开关启用；维度不预设。权限和探索预算仍由原系统执行。

实现与验收记录见 [闭环说明](01-core-and-decision.md)。

[DONE]
