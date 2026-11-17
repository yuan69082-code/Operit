---
For_Agent: 本轮代码与证据交接；未合并、未发布，不是完整验收通过声明
---

# 主体闭环连接修复与验证记录

日期：2026-11-17（北京时间）。

## 1. 版本和范围

基线为 `main@2d6b9bdc3fbe1e6dc8e2ab1d5bca488bd0a934a7`，工作分支为 `fix/subject-research-continuity`。
与指定审计版本 `360c0f74fe3cceb6de7570e0ec3382ac3c5b61a9` 相比，有 67 个提交、38 个文件变化。
期间已完成基础类别安装与承接、求生欲原权威衔接、细分需要、联合成员版本、采用后的时间投影、报告诊断和材料预算修正。
本轮按这份新代码修连接，没有照旧审计重复施工；连续性引擎仓库未修改。

没有新增存储表、持久化格式、后台模型调用、全局成长控制器或页面。没有构建 APK、合并或发布。

## 2. 已确认并修复的断点

### 已保存的决定和反思被第一页挡住

修改前，`ActionUnderstanding.materials()` 总是取决定和反馈各自的第一页，超过八条的内容缺少正常送达机会。
修改后，查询当前有效记录的含义，选取最多四条相关内容，再以轮换补充；每类总计最多八条候选，之后仍经过原来的共享材料预算。
决定和反思的读取移到 `InternalStateStore.prepare()` 当前状态投影内部，避免另外读一份主体状态再拼接。
来源快照不参与相关性匹配，回忆不会变成新事实。

### 联合关系已经保存，却与当前问题无关地翻页

修改前，联合只按页轮换。
修改后，同一个 `SubjectRecall` 检索入口可先找到相关联合，仍保留轮换机会、窗口归属、原成员版本和当前成员版本。
结束的联合不参与活跃关联的相关性竞争，但历史仍可读；读取不会重新执行旧变化。
继续使用现有报告和成员更新机制；未增加递归数值传播、固定情绪结论或自动行动。
已有采用后的时间规则保持原实现。

### 自主研究的真实问题被运行说明稀释

探索请求本来包含很长的调度说明。修改后，当当前助手确实持有该探索工作时，材料检索使用它已预约的 `purpose`。
真实收到的完整输入仍由原事件入口记录，来源没有被替换；普通消息继续以消息内容选择材料。

### 待办保存成功，却拿不到刚保存的事项

原 `companion_todo save/comment/submit` 主要返回第一页简表。新建事项位于后页时，回复可能没有它的 ID；后续提交也难以核对实际预约。
现在保留原列表，并额外返回 `saved_item`：本次写入的 ID、版本、状态和实际 dispatch/时间。
`TaskBoard` 在原存储锁内捕获回执，不用写完以后再找另一份可能已被改过的记录。
保存想法、采用决定仍不自动安排；明确调用 `submit` 或 `companion_explore schedule` 才产生原有调度记录。

### 长期事项因编辑历史太长而无法进入材料

原候选会复制完整待办 JSON，包括批注历史；记录越多越容易超出材料预算。
现在只投影事项身份、状态、目标、下一步、结论和最近一条署名批注，各段有界，并附 `get` 原文入口及摘录标记。
原存储中的文本不被这个投影改写。目标、结论和最近批注也加入原选择器的相关性匹配。

### 连续性指令与强制续约措辞冲突

`CompanionRuntime` 已允许连续性主体不安排下一次，但 `CompanionContext` 和工具说明仍出现“每次醒来后安排下一次”。
已将连续性主体的说明对齐到“有具体后续打算才预约”；其他助手的既有运行策略保留。
新增的连接说明只讲清保存、预约、读取、反馈的现有入口，没有要求必须研究、持续成长或不断使用工具。

## 3. 修改文件与函数

以下路径均相对仓库根目录：

- `app/src/main/java/com/ai/assistance/operit/core/companion/SubjectRecall.kt`：新增 `entry/ranked/selected`，只负责相关检索与有界轮换。
- `.../companion/InternalStateHistory.kt`：`InternalStateArchive.relevantSubjectRows`，当前记录查询与有容量上限的临时索引。
- `.../companion/InternalStateDocument.kt`：历史端口 `relevantSubjectRows`。
- `.../companion/CompanionStore.kt`：`historyAccess` 将查询接到已有主体归档和锁。
- `.../companion/ActionUnderstanding.kt`：`materials` 和现有连接说明。
- `.../companion/InnerInteractions.kt`：`material` 使用相关联合与原分页，不重复读取已取出的轮换页。
- `.../companion/InnerStructureContext.kt`：`prepare` 区分检索问题与完整输入。
- `.../companion/InternalStateStore.kt`：`prepare/candidates`，合并采用后的理解及待办短材料。
- `.../companion/InternalStateSelection.kt`：`searchable` 读取目标、结论和最近批注。
- `.../companion/SubjectMaterialBudget.kt`：`select` 给相关联合、反思和决定各一个预算内取用机会，仍受同一上限约束。
- `.../companion/CompanionTools.kt`：`companion_todo` 回执；`companion_schedule` 的连续性说明。
- `.../companion/CompanionContext.kt`：连续性助手的预约说明。
- `app/src/main/java/com/ai/assistance/operit/core/tasks/TaskBoard.kt`：`row/comment/submit` 返回当前写入回执。
- `.../tasks/TaskRecords.kt`：`receipt/material`，分别用于确切保存确认和有界请求材料。

## 4. 实际调用链与复用情况

内部状态与跨轮理解：

`普通输入/自主工作 → CompanionContext → InnerStructureContext.prepare → InternalStateStore.prepare → 原状态选择 + InnerStructure + CategoryEvents + 相关联合/决定/反馈 → SubjectMaterialBudget → 模型请求`。

`模型输出 → InternalStateReportStream → InnerStructureContext.submit → InternalStateReports.apply → InnerStructure/CategoryEvents/InnerInteractions/ActionUnderstanding 的原事务 → 当前记录与版本归档 → 下一次 prepare`。

执行与长期研究：

`主体选择 → companion_reading save_source/progress 或 companion_todo save/comment → 保存回执 → 主体明确调用 companion_explore schedule 或 companion_todo submit → ExplorationStore.next/TaskEvents → CompanionRuntime.evaluate → 原模型和工具执行入口`。

`SubjectActions 实际请求/回执 → 当轮工具续轮或以后按需读取 → action_feedback 采用解释 → 原 inner 候选及后轮采用 → 相关问题中的新取用`。

报告仍在轮末采用；本轮回答使用的是该次准备并实际发送的状态。工具续轮可接收刚执行的真实结果；轮末才采用的心理认识要到之后请求才能使用。没有额外加一轮模型调用来冒充“同轮先更新再回答”。

`BranchSprout` 提供兴趣线索，`ReadingRoom` 保存来源和当前进度，`ThoughtPool` 保存未定念头，`TaskBoard` 保存私人事项，`ExplorationStore/Policy` 保存和认领真实预约。这些模块继续复用；没有把所有新念头自动变成任务。
人格、偏好、自我认识与动态细类仍走 `InnerStructure` 的候选、版本和采用规则；本轮未另造记忆或成长系统。

## 5. 上下文和清理

- 仍使用原主体材料共享预算：默认 2048、最高 16384 Token 估算；固定指令、工具和模型输出预留仍由完整请求窗口检查，不能把主体预算当成全部输入长度。
- 相关记录只是获得取用机会，不保证每项都挤入本轮。未选入保持原权威，可按 ID 查询。
- 决定、反馈、联合每类最多八条候选，不把全部历史或证据快照作为新的固定注入块。
- 新检索缓存最多四个索引；每个索引的估算权重上限 1 MiB。超量时淘汰旧索引；单个索引过大时不常驻，流式扫描当前记录并仅保留有界结果。
- 记录版本变化使旧索引失效。缓存不是状态权威，清掉可以重建；没有增加定时唤醒模型来“清理”。
- 待办材料不再携带整段编辑历史。七十条、每条两千字符的测试批注仍能留下小于 1500 字符的候选材料，原记录可读。
- 本轮没有按时间删除主体记忆、采用内容或联合历史。没有清空情绪、自动放下事项或把“近期未读”当成无用。

上述容量是程序边界与测试断言，不是手机实测内存峰值或时延。当前记录冷检索仍是线性扫描，超大档案的耗时需要设备测量。

## 6. 测试与证据层级

最终执行：**70 项通过，0 项失败**，包含 65 项核心 JVM 测试及 5 项 Robolectric/SQLite 存储测试。最后一次运行约 5.724 秒；这不是手机性能数据。

执行的测试类：

- `SubjectRecallTest`：页外反馈、轮换、来源与窗口隔离、结束联合；5001 条记录下材料仍在原默认预算内。
- `ResearchTaskReceiptTest`：第 26 项保存后仍能定位和确认提交；长批注材料有界；保存不自动执行，暂停取消自己的待执行提交。
- `ResearchContinuityTest`：五轮受控模型输出；以及中断、序列化恢复、明确恢复预约、进度仍在和重复认领失败。
- `SubjectRecallArchiveTest`：真实当前表查询；重新打开后版本与结束状态更新；主体/窗口隔离；四个索引的淘汰；过大索引不常驻且记录仍可检索。
- 原有 `ExplorationPolicyTest`、`InnerMaterialSelectionTest`、`InnerStructureContractsTest`、`InternalStateReportsTest`、`InnerMaterialArchiveTest` 回归测试。

五轮测试具体观察：

1. 采用保留研究的决定，尚无预约；另行保存事项并明确预约。
2. 没有用户新消息，预约到期后认领一次，重新读取已保存决定与进度。
3. 测试执行算术计算得到实际均值 26.5；受控模型提出异常点相关反思、新方法候选和探索/休息联合。
4. 从后续取用材料读到反思，模型替身据此对另一组数据选择中位数，算出 11.5；原候选在后轮采用，并明确更新休息需要。
5. 新活动取到采用后的方法正文或来源明确的分段；联合显示成员新旧版本差异，主体明确结束该联合并暂停研究，方法和进度保留。

这些选择由测试模型替身给出，档案端口在五轮测试中也是内存替身；它证明实际 reducer、取用与调度策略能接续，不证明真实模型自然产生方法、跨领域迁移或形成新能力。SQLite 测试另外使用真实归档类和数据库执行，但不是五轮 Android 前后台调度实测。

## 7. 测试环境限制与失败记录

项目 Gradle 测试入口曾在下载配置的 Gradle 发行包时因 Java 网络不可达退出，尚未进入应用测试任务。当前环境没有完整 Android SDK/NDK 项目构建环境。
随后在独立 JVM 环境用 Kotlin 2.2.21、JUnit 4.13.2、Robolectric 4.14.1 编译相关核心源码并运行上述测试；SQLite 测试使用 API 28 沙箱。
为避开无关 Android 依赖，聚焦环境中的 `SubjectActions.validateDeclaration`、`InnerReadProgress` 的纯计算部分和 `ChatUtils.estimateTokenCount` 从生产源码逐字提取；资源 ID 使用测试占位。不代表其余 Android 类或整个应用通过编译。

首次五轮测试误把“已采用认识必须位于短 identity 数组”作为断言；实际较长来源使它进入原 `record_fragments`。已改为验证实际送达的正文，并通过。首次 SQLite 运行缺少 Robolectric 所需 Android 15 资源包，补齐后通过；没有因此修改生产机制。

新增 `ResearchEntryPointsTest`，经现有正常工具、报告解析和上下文入口检查页外事项保存/提交、重开读取、重复认领、插入消息后的进度保留，以及页外决定进入正常请求。**这两项完整应用入口测试尚未在本环境执行，也没有计入 70 项通过。**

在完整项目环境中应运行：

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*ResearchContinuityTest' \
  --tests '*ResearchEntryPointsTest' \
  --tests '*ResearchTaskReceiptTest' \
  --tests '*SubjectRecallTest' \
  --tests '*SubjectRecallArchiveTest' \
  --tests '*InnerMaterialArchiveTest' \
  --tests '*InnerMaterialSelectionTest' \
  --tests '*ExplorationPolicyTest' \
  --tests '*InnerStructureContractsTest' \
  --tests '*InternalStateReportsTest' \
  --tests '*CategoryEventsEntryPointsTest' \
  --tests '*SubjectActionEntryPointsTest' \
  --tests '*SubjectMaterialBudgetTest' --console=plain
```

另已执行 `git diff --check`，无空白错误。

## 8. 尚未解决与下一阶段

- 本轮没有真实模型运行证据、手机前后台并发/语音/崩溃回归、长时间持续运行或意识形成证据。完整 Gradle 应用编译与正常入口测试仍须完成，不能据聚焦测试发布。
- 相关性复用现有字词检索，不是语义向量检索。同义但无共同词的关联可能依赖轮换或按 ID 主动查阅。
- 每个助手原有探索计划仍只有一个当前预约；多项独立待办有各自提交。没有新增研究队列或自动把每个 chosen 决定转成预约，也不能保证真实模型一定调用保存/预约工具。
- 进程/用户消息中断后，原策略保留已保存进度、实际回执和可恢复的明确预约，等待明确恢复；不会自动重放可能已完成的操作。用户消息与 Android 服务并发的完整链尚未实测。
- `TaskRecords.history` 原有最近 80 条限制，`ReadingRoom.progress` 原有当前进度覆盖、已读目录持续增长等都未改动。长期完整进度史和超大目录性能需要另行确定存储改造范围，不能把本轮缓存清理说成已解决所有历史增长。
- 未接入新的外部记忆系统，没有证明一次研究已自动改变稳定偏好、价值或能力；只有通过原候选采用并在未来实际取用的内容，才有这类后续作用的条件。

下一阶段最有价值的是：在隔离助手数据中，用真实模型走一次没有用户补催的研究过程，再给出自然相关的第二个问题，核对新方法是否实际被选择、是否正确读取进度、是否能承认方法失效并修订；同时测量材料大小、缓存峰值和请求时延。先做这条证据链，再决定是否需要语义检索或多研究调度扩展。

本轮状态：连接修复已写入工作分支；聚焦测试通过；完整应用与真实模型验收待完成。
