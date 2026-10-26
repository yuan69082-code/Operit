# 内部状态闭环

## 来源与边界

参照 [Orbis 2.6.13 功能说明](https://github.com/AZHi-xinxin/Orbis/blob/b9885e2edfeb1985b0c4214dfd85404cf2fb85eb/docs/RELEASE-2.6.13.md) 中的助手定义、宿主结算、自动带入与双方可见思想。本实现独立编写，未复制其 AGPL 源码。状态为软件内部变量，不是生理测量。

按助手存于 CompanionStore 的 `internal-state:<role>`，总开关默认关闭。关闭暂停计算和注入，重新开启不补算关闭期间的经历。UI 收起私密层不改变计算。跨窗口 global 与按 chat ID 隔离的 session 共享维度定义，分别保存实例值。

## AI 接口

`companion_internal_state` 的 get 分页返回定义、状态、真实事件和版本。create、revise、merge、deactivate 必须提交乐观版本号、非空经过说明和已有事件 ID。AI 不能开启总开关、报告事件、设置当前值或改写历史。最多 64 个活跃维度；这是技术容量限制，没有预设情绪枚举。

完整定义字段为 id、name、description、baseline、min、max、maxDelta、halfLifeHours、scope、private、share、participates、triggers 和 effects。仅接受有限数值和合法范围。halfLifeHours=0 关闭自然恢复。time 规则 delta 表示每小时漂移；其他 source 的 delta 为每事件增量，同一次事件的匹配规则合计限制在 maxDelta 内。恢复和持续漂移用解析式计算，避免轮询频率改变结果。

effects 的 action 是操作类别，不是状态维度枚举：contact、explore、todo、tool、reply、defer、drop、rest。weight 为 -10 至 10；cue 说明状态如何影响选择；target 可指定现有待办、阅读来源 ID 或工具名。维度高于基准时使用权重原方向，低于基准时反向。

修订保留当前值，收窄范围不得挤压现有实例，scope 不可改写。修订前把所有已有会话按旧规则结算。合并仅限同作用域，按来源在其范围内的位置映射至目标，宿主平均目标与映射值，来源停用。来源历史、目标变化和目标规则版本都保留。停用不删除历史，重新定义使用新 ID。

## 真实经历

ChatHistoryDelegate 在消息落盘后记录用户消息或助手回复；排除流式未完成内容、隐藏占位和预览，按消息身份持久去重。群聊用户消息归于成员，助手回复只归于 GroupProvenance 的实际作者；未知作者不猜测。工具和思考标记不参与正文匹配。

ToolExecutionManager 在授权和实际执行之后记录结果，严格代理先解析真实工具名。阅读、待办进展/完成/失败另有专门事件。状态工具自身不会产生触发循环。CompanionRuntime 记录唤醒、自主选择和探索完成/失败/中断；历史导入不是新事件。事件保留来源、窗口、时间、触发身份、版本、前后值。计算不会调用额外模型。

## 决策接入

CompanionContext 在真实请求前结算时间，在估算上下文时只计算副本。状态放在现有 operit_state 内。输入包括当前维度、最近重要变化、开放待办、阅读进度和浮起念头，复用原存储。

候选分数为各参与维度的 `(value-baseline)/(max-min) × weight × (1+min(持续小时/24,1))` 之和，同时附每项贡献和 cue。按分数排序，不截掉较低分候选。目标匹配可单独改变某项待办、来源或工具的排序。participates=false 不计分。available 只提示可用性，实际权限仍由正常工具管线检查。

模型必须先比较状态、排序、来源和现实任务，再选择表达、行动或放下；允许选择低分候选，唤醒时在原 companion_note 留下取舍。这里验证的是模型实际收到的输入变化，不宣称模型一定服从分数。

普通唤醒在模块启用后改为多来源注意力判断。探索仍通过原 ExplorationStore 的独立预约、开关和每日预算执行，普通唤醒不能直接变成无预算探索。原 ThoughtPool、ReadingRoom、私人待办及语音/群聊入口保留。

内部状态开启后，它是唯一自动状态注入来源；XC 的绑定、数据、工具和事件桥接保留，关闭新模块时原 XC 行为维持。ThoughtPool 工具也不再额外注入 XC 分享欲。用户主动调用的 XC 工具结果仍是外部资料，不覆盖内部状态。

## 观察窗

助手空间 → 情绪与状态：总开关、最多五张重点卡和全部展开、趋势、变化事件、演化规则。无数值滑块、人工规则编辑或运行按钮。私密显示要求该维度 share=true 且本次本机展开。卡片、趋势、事件和版本共同过滤；撤回许可同时收起旧记录，历史私密版本不因后来公开而自动暴露。聊天工具卡不展示原始状态工具参数/结果；底层历史仍供模型使用，此机制不是存储加密或导出权限控制。

历史在原子 JSON 文档中完整保留，本版未做分片归档。大量长期事件会增加文件体积，后续可在不删除历史的前提下分页落盘。UI 每三秒读取计算副本，不触发额外唤醒或模型费用。

## 验证

本机以 Kotlin 2.1.20、JRE 21、真实 org.json 和 JUnit 4.13.2 编译执行：InternalStateEngineTest、InternalStateEventsTest、ExplorationPolicyTest、CompanionToolBudgetTest、VoiceCallPublicTextTest，共 38 项通过。

新增 21 项覆盖动态维度、宿主自身行为、重复事件、范围和增量限制、时间结算、跨窗口/局部隔离、版本冲突、修订不重置、合并/停用保留历史、双重私密门、历史撤权、唤醒决策输入变化、联系/探索/表达等候选权重差异、持续性、定向待办和群聊归属。

Android 全量 JVM 测试与 APK/Compose 编译使用现有 Android Tests 和 Shared desktop preview 工作流。此机器没有 Android SDK，本地结果不是全应用构建成功证明；按用户要求触发远端构建后不等待完成。XC 桥接全套测试、群聊及设备 UI/语音联调留给既有远端测试及实机验证，不声称已完成实机验收。

[DONE]
