---
fork: https://github.com/yuan69082-code/Operit
status: private-continuity-followup-validation
---

# 指定助手连续状态：六批实施记录

当前续作见 [私域连续性七项整合](9-private-continuity-delivery.md)。前六批和 #59 已包含在 +136，后续 +140 保留并补 Gemini 档位与共享流边界。本轮 #61 接续 +140，新增私域呈现、活动/梦醒随记、全文候选承接和报告顺序兼容。以下“不合并/未发布/未实施”均为历史批次交接时状态，不能作为当前缺失判断。

新归档布局和候选流程不再使用旧 8/32/128 条数上限；已有确定内容声明不需要伪造历史原文。旧文档的严格正文前报告约定由本轮明确兼容规则取代，来源、版本和有效采用校验仍保留。七项及最终发布证据统一续在第 9 号记录，不另建冲突规划。

第6批在 feat/subject-time-dreams / [PR #58](https://github.com/yuan69082-code/Operit/pull/58) 接续 #57 → #56 → #55 → #54 → #53，实施与验证记录见 [时间、休息、梦与恢复](8-time-rest-dreams.md)。用户最新授权六批完成后整体合并发布；下文“不合并/不发布”是此前批次交付时的历史边界，不再限制本次最终整合。第5批操作回执、软件能力与接续入口仍见 [第5批交接](7-action-feedback.md)。

2026-11-10续作：GitHub恢复后，710a9a4完整合入用户指定的PR #59（Gemini空parts和读流错误修复），没有重复异常处理入口。运行124详细报告确认2000项、0失败、6原有跳过，真实快照恢复测试与新增Gemini10项均通过。最终合并发布仍须等待同提交APK及最终整合发布流程；最终发布信息以PR #53/#58为准，不把本段测试结果当作已经发布。

第3批从未合并 #54 / b456315 接续；类别内部事件、重点、求生欲特例及第4批接入点见 [类别内部事件](4-category-events.md)。此前两批记录保留为对应交付时事实。

同分支另有独立提交的 [实际活动承接修复](5-activity-continuity.md)，验证聊天与书房保存事实，未合并或发布。

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
