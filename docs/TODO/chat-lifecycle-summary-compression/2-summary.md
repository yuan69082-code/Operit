# 摘要 AI 复核与重复压缩

## 证据与范围

上传日志没有一次完成的上下文压缩，不能把用户报告的“压缩后仍为 70%”说成已在该日志中复现。本次在当前代码中确认了三个独立问题，并增加对应回归用例。

1. 原有 `enableSummaryDialogueReview` 只决定摘要是否附带少量“对话回顾”原文摘录。`SummaryReviewer.review()` 仍无条件调用聊天模型，原来的设置不能关闭第二次 AI 复核。
2. 摘要标记插在保留的最近 2～8 轮前面，而自动压缩把标记后的所有用户消息都当成“上次压缩后新增”。刚保留的旧轮次也被再次计数，使原有“至少 3 个新轮次”的防重复条件失效；消息数触发与后台摘要准备也受影响。
3. 原先最低保留两整轮，不受保留字符预算限制。若倒数第二轮含很大的工具结果，即使超过预算也完全保留，压缩后仍可能带着大量原文。

## 修改

### 独立的复核开关

设置 → 上下文与总结 → **摘要 AI 复核**。默认开启，旧配置解析后继续开启。设置直接写回当前绑定的模型配置，独立于“对话回顾”摘录开关，手动与自动压缩共用。

关闭后不构造聊天模型复核器、不发送复核请求，生成的非空摘要直接进入保存流程。关闭时的缓存与开启时的缓存分开，重新开启后不能把未复核稿当作已复核结果。原对话快照检查、空摘要检查保持生效，压缩进度也不再显示“等待 AI 审阅”。

实现入口：[AIMessageManager.kt](../../../app/src/main/java/com/ai/assistance/operit/core/chat/AIMessageManager.kt)、[ReviewedSummaryBatches.kt](../../../app/src/main/java/com/ai/assistance/operit/core/chat/ReviewedSummaryBatches.kt)、[ContextSummarySettingsScreen.kt](../../../app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/ContextSummarySettingsScreen.kt)。

### 正确区分旧尾部和新轮次

只对 `sender == "summary"` 的消息，使用已有持久化字段 `sentAt` 保存本次不可变快照的末条消息时间戳，范围包含被概括的前缀和原样保留的尾部；`completedAt` 仍是完成时间。普通消息的耗时字段含义不变，无数据库结构迁移。

自动压缩依据快照边界之后真正新增的用户轮次计数。压缩期间追加的消息比快照边界新，即使早于压缩完成时间，也不会被误当作旧记录。用户不足 3 个新轮次时延续原有保护；达到 95% 时仍可提前压缩，避免为了抑制重复而阻止必要操作。自主群聊的 AI 请求仍可在接近容量时触发，单个请求的多个工具片段不重复计数。后台摘要准备同样不再把刚保留的旧消息当作新增轮次。

历史版本摘要的 `sentAt == 0` 保持原计数语义，下一次成功压缩后写入新边界。自动压缩和手动插入摘要两条提交路径均保存该边界；用户主动压缩不受自动触发的轮次限制。

实现入口：[SummaryTriggerPolicy.kt](../../../app/src/main/java/com/ai/assistance/operit/core/chat/SummaryTriggerPolicy.kt)、[SummaryContextSlice.kt](../../../app/src/main/java/com/ai/assistance/operit/core/chat/SummaryContextSlice.kt)。

### 按预算保留完整近期对话

优先保留最近完整一轮，再在原有字符预算内向前扩展，最多仍保留 8 轮。很短的近期讨论仍可保留多轮；超预算的倒数第二轮可以被概括，不再因为最低两轮要求强行保留。最新一轮即使较大也保持完整，用户输入、AI 回复与工具链不拆开。原始聊天记录与主体资料均未删除。

这个修复不保证每次压缩都降到某个百分比。固定系统提示、工具定义、人格与其他必要输入不会因压缩历史而消失；最新一整轮也可能很大。没有改大上下文上限、伪造占用数字或增加无证据的压缩基线状态。

## 验证

新增/更新用例覆盖关闭复核时零复核调用、重新开启后的缓存隔离、空稿拒绝、旧配置默认值与设置持久化、8 个保留旧轮次不触发重复压缩、1～2 个新增轮次保护、95% 近上限处理、消息数阈值、压缩期间追加消息、旧摘要兼容、第二次压缩更新边界、自主群聊请求和超大工具链的完整保留。

相关测试类：`ReviewedSummaryBatchesTest`、`SummaryContinuityTest`、`SummaryTriggerPolicyTest`、`ModelConfigSummariesFlowTest`。构建与测试结果由本次修复的统一验证流程记录，新增测试尚未单独宣称运行成功。

静态检查已通过：`git diff --check`；摘要设置中英文 XML 可解析、键名一致且无重复。
