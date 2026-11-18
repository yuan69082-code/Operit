---
repository: https://github.com/yuan69082-code/Operit
status: implemented_pending_validation
---

# 持续牵挂与旧事浮现

沿用 experience/direction、联合、行动回执及现有共享材料预算。便签是已有认识的关注入口，不再建立目标、记忆或调度权威。连续性引擎只读。

1. 为 life 记录增加可选 attention：长短期、简况、重要性、当前注意、常驻和本意保护。缺省保留原字段；明确变更保留历史。
2. 轻量索引分页读取，常驻简况优先进入同一材料预算；详情仅按 ID 读取。旧事利用现有选材候选，相关/轮换/随机/混合可选。
3. 已有 related、联合与行动关联继续使用原记录 ID/version。补齐当前关联成员取用和正常反馈后的下一轮续接，不新增数值连锁或空闲唤醒。
4. 私域新增便签观察与首次共同设置。配置按助手保存，详情默认折叠，更新进度与更换本意分开。
5. 测试初始设置、常驻稳定、全文保护、跨轮双向反馈、预算/重复、重启/并发/助手隔离；执行现有回归与发布工作流。真实模型与实机表现另列证据。

完整软件检查以现有自动化回归、编译与改动调用链审查为证据，不声称穷尽所有实机问题。

## 实际入口与证据范围

- `SubjectConcerns` 在已有 life 行上维护 attention；`InternalStateStore.prepare` 取简况及当前关联成员，`SubjectMaterialBudget` 在同一总额内选择。`LifeUnderstandings.apply`、`InternalStateReports` 原子采用进度和明确变更。
- `companion_internal_state get section=contract id=concerns` 给模型完整说明；`section=concerns id=direction:ID` 读取详情并绑定当前版本，`section=concern_config` 查配置。工具续轮复用同一帧，不重复加完整便签。
- `SubjectConcernsEntryPointsTest` 覆盖跨窗/自主入口、来源、进度与原文、容量、并发和五轮状态—决定—实际工具—反馈—后续读取。`SubjectMaterialBudgetTest` 覆盖共享预算、抽选与去重。
- 五轮中的决定和心理解释是模拟输入；软件能力构建和算术执行使用真实生产工具。没有真实模型长期成长或实机性能结论。完整执行结果记录在 PR 80 与发布工作流。
