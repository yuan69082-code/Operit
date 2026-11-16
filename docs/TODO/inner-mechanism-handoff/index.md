---
For_Agent: 当前 PR 的实施与证据追踪文件。不得将这份草稿当成已发布版本说明
---

# 内部状态从旧数值到完整分层体系：本次施工与风险

## 只读边界和基线

- 工作仓库：`yuan69082-code/Operit`，此次从最新 `main@360c0f74fe3cceb6de7570e0ec3382ac3c5b61a9` 创建独立 `feat/inner-mechanism-handoff-20261114` 分支。未回滚用户游戏屋、订阅、书房等已有修改。
- 引擎参考仓库 `yuan69082-code/continuity-engine@0567ba4af7541aeeb877ea318ed055ce997e232e` 只读，未提交修改。
- 读取到了引擎仓库中的 `docs/project_memory/工程流程/项目愿景与核心定位_园园原文.md`。
- 初始施工窗口未找到另外两份参考文档；11月16日接续时已在本机只读参考目录找到三份精确文件。结合本轮明确要求核对，不把引擎规划编号当成 Operit 六批编号。接续代码包含 #69 修复与 main@6b8c285 的后续摘要改动，见 [当前检查点](continuation-20261116.md)。
- 第八九批专项、引擎独立可视化不在范围内。

## 基线偏差→改动→真实入口

| 偏差 | 正常运行的原代码 | 本次具体接替 |
| --- | --- | --- |
| 旧数值和新版 inner 同屏 | `PrivateInnerStatePanel` 原来同时展示 `InternalStateValuesPanel` 和 `InnerFamilyPanel` | 当前页按基础目录、已采用内容、独立事件及旧待承接历史区分，保留历史不展示第二套当前曲线 |
| 没安装基础类别 | `InnerStructure.root` 只初始化可扩展空记录 | `InnerFoundations.kinds` 安装 92 个明确概念，七驱力单独连续运行，感受/愿望不能启动即伪造 |
| 旧状态与新状态可能重复计算 | `InternalStateEngine.advance` 对 active 定义时间演化 | 按园园确认的逐项承接：schema 2 保留有效规则和 session 范围；准确稳定 ID 通过别名共享原值，只有确认冲突的规则退出。schema 1 误停用可通过既有版本归档恢复，不复活用户明确停用的定义 |
| 求生欲旧记录可能被清空或双算 | 旧数值和 `inner` 特殊需要可能并存 | 已采用 `semantic_rule=survival` 的历史按版本/来源承接到 `foundation:need:survival`；以后仅允许主体明确改变，时间/联合/归零都不能停机 |
| 只有候选才能有心理类别 | `CategoryEvents.category` 必须找到已采用 `inner` | 允许基础目录 ID 直接分类心理事件；同一类别的 A/B/C 由稳定事件 ID 分别保存、评价与关注 |
| 联合不能引用新基础类别 | `InnerInteractions` 只认识 inner/episode/旧 state | 新增 `foundation` 成员与版本证明；本人实际决定成员改变并同事务提交，联合不会自动递归加权 |
| 基础驱力缺少独立运行结构 | 原 halfLife/time_rules | 有有效原规则则承接原规则；其余基础驱力用 6/3/12/5/4/8/6 小时连续参数。主体可明确重评或采用联合时间节奏；不以阈值强迫行动 |
| ISR_FORMAT 只给通用错误 | `InternalStateReportFailure.classify` + `EnhancedAIService` | 新增 `InternalStateReportDiagnostics`：对必填字段类型、路径、数组条目和业务阶段做安全诊断；失败原状态及工具回执保持，下一轮读取少量未解决错误 |
| 全部状态每轮过载 | 原有 `SubjectMaterialBudget` | 新基础目录/驱力/旧承接索引也进入**同一预算**；分页查询全文，绝不复制整批记录、历史、人格或候选 |

## 技术作用域

`InnerFoundations.kt`：基础目录、时间值、明确重评、存档映射和有来源的特殊求生欲承接。

`InternalStateStore.kt`、`InternalStateReport.kt`、`InnerStructure.kt`、`CategoryEvents.kt`、`InnerInteractions.kt`：已有唯一主体框架的输入、来源、事务、候选、心理事件、联合和旧数值停用。

`SubjectMaterialBudget.kt`、`SubjectToolMaterials.kt`、`CompanionTools.kt`：工具查询、版本认证、预算与读过的材料。

`InternalStateReportDiagnostics.kt`、`InternalStateReportFailure.kt`、`EnhancedAIService.kt`：拒绝报告的阶段和路径诊断，不泄露原始模型输出、人物隐私、凭据或全文。

`PrivateInnerStatePanel.kt`：原生 Compose 页面展示真实状态、不伪造体验、提供逐类查看和历史查询。

## 测试与交付

- `InnerFoundationsTest`：完整目录、精确旧数据承接、重复迁移识别、七驱力时间曲线、求生欲不自动归零、报告字段拒绝与有效报告提交。
- `InnerFoundationsEntryPointsTest`：真实应用级存储及来源框架、跨窗口同类事件各自变化、失败后新轮可提交、新基础类别联合、角色隔离。
- `InternalStateReportsTest`、`CategoryEventsEntryPointsTest`、`InnerStructureContractsTest` 以及相关现有测试负责保留原始候选规则、历史与来源约束，不应因新结构绕过。

[全部 92 个细分项的运行路径、时间规则和验收状态](detailed-coverage.md)。

## 尚缺的证据

原截图只有 `[ISR_FORMAT]`，未包含失败时实际 JSON 字段和完整调用材料。因此本次可以修复错误分类能力、常见字段不一致及失败保留链，但**不能凭截图指定当时究竟哪个字段不合法**。真实模型、实际手机、长期高负荷、完整升级与恢复场景必须真实使用才可以宣布通过。

该新增机制仍依赖模型对客观事件进行主观解释；不能以数值阈值强制行动，也不能宣称软件已具备现实身体、外部连续意识或由自动测试证明主体感受。代码交付的范围是可追溯、可恢复、可影响后续选择的软件状态基础。
