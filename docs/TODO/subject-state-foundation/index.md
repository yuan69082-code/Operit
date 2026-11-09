---
fork: https://github.com/yuan69082-code/Operit
status: validating
---

# 指定助手连续状态：第 1、2 批

当前第 2 批从未合并的草稿 #53 / c6068de 建立依赖分支 feat/subject-inner-structure。实际实现、采用契约、兼容边界及第 3 批交接见 [完整内在结构](3-batch-two-implementation.md)。下文权威清点保留为第 1 批交付时的事实，人格与关系的新权威以第 2 批交接为准。

基线为 main 8b09126，沿用 #50 的历史归档、报告幂等和 #52 的 IO 修复。参考引擎只读核对 0567ba4：current_identity 的来源边界、persistent_runtime_service 的旧持有者拒绝与恢复、PreservedRuntimeRepository 的身份和存储版本校验。没有移植 Python 运行时，也未决定后续实现语言。

1. 在现有 CharacterCard.id 和存储事务上统一主体归属、版本、真实来源；保持 global/session。
2. 接通消息、报告、工具、后台、用户编辑；消除旁路覆盖与重复权威。
3. 检查迁移、归档按需读取、重启、并发和 IO；补充测试后运行构建。

第 1 批未实施第 2–6 批机制。第 2 批只补完整结构、候选采用与正常选材，不实施第 3–6 批或页面改版。模型仍只调用原有业务轮；报告在业务轮成功结束后保存。保留依赖清楚的草稿 PR，不合并或发布。

## 权威清点

- 内部状态：InternalStateStore → CompanionStore 的 internal-state:CharacterCard.id 文件；InternalStateEngine 计算时间/遗留触发，Reports 提交语义回报，UserEdits 标注 user_edit。模型通过 CompanionContext 读取，前后台共用，session 继续以 chatId 隔离。
- 历史：InternalStateHistory 和现有 SQLite 归档负责，不新建档案库。语义版本与宿主提交版本分开，不把普通读取当新体验。
- XC：远程 MCP 是其原数据权威，本地 xc-binding 只是回执。内部状态启用时不自动注入 XC，也不允许旧 XC 回执间接抬高关注档位；明确调用的外部工具继续保留。
- 念头：ThoughtPool 的 thoughts:role，已有独立内容与生命周期。内部状态选取念头时，不重复注入旧念头提示；不改其生命周期规则。
- 人格：CharacterCard 文本及标签仍是用户配置，现有提示词入口继续使用；不转成主体自我发展，也不编造历史。与未来人格演化的合并策略留给第 2 批。
- 关系：RelationshipNetwork 的有向 from/to 记录继续单独保存；用户编辑明确标注，其他助手不能代写启用内部状态的主体看法。未来关系发展与手工编辑的冲突尚未定义。
- 关注档位：AttentionGear 是感知调度设置，不是情绪。需按现有观察助手身份隔离，原全局记录保留为来源不明确的旧配置，不能猜归属。
- 窗口：AssistantWindowRouter 只选投递位置，不拥有主体状态；切窗、恢复与换模型不能复制或重建主体。
- 记忆/资料：现有本地记忆、MCP、阅读和资料能力保持原权威，不重复建设。

## 验证与交接

人格采用、偏好归属及完整六批后续范围见 [六批连续目标与偏好](2-six-batch-continuity-and-preferences.md)。本批不提前实现第二至第五批。

实现及补充需求已纳入草稿 [PR #53](https://github.com/yuan69082-code/Operit/pull/53)。最终组合验证的提交、测试、构建结果和未验证范围在该 PR 更新，不能把测试代码存在当作已经通过。第 2 批从本底座继续，不重建归档或另一套身份。
