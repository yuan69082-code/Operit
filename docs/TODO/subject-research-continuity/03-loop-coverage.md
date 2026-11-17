---
repository: https://github.com/yuan69082-code/Operit
status: implementing
baseline: 07d2e9ffeba023ed31b0bf813dca15df344a8668
---

# 多重闭环逐项核对

本文件描述真实生产调用及证据边界。下列“已有”指源码与调用链已核对，不等同于真实模型、手机或长期运行已经验收。当前最终集成 CI 尚未完成，未发布本轮改动。

主要路径统一为 ConversationService.prepareConversationHistory → CompanionContext.compose → InnerStructureContext.prepare → InternalStateStore.prepare → SubjectMaterialBudget.select → CurrentRequestContext → EnhancedAIService → ToolExecutionManager/SubjectToolMaterials → 最终 InternalStateReportStream → InnerStructureContext.submit。工具执行事实先保存，主观变化轮末原子采用，后续业务轮再读；工具续轮只增加实际取得的回执，不额外制造思考轮次。


## 1. 情绪、注意、记忆与事件解释

输入为当前原始消息、已保存事件或实际工具结果。CategoryEvents 保存事件自己的对象、解释、强度、长期意义和注意状态；CategoryEvents.timeView 只产生适用的时间投影。SubjectMaterials 以当前问题及部分事件/内在内容查询原 MemoryRepository.subjectMaterials，数据库先限量取得相关记录与独立轮换样本。MCP 仍按实际工具选择读取，经 SubjectToolMaterials 绑定实际送达内容。

断点：旧心理事件只有按 ID 轮换，没有针对本次问题的相关检索。本次把 episode 接入 SubjectRecall，相关旧事与原轮换共同参与同一材料预算，不改重点、不合并同名事件、不产生新现实经历。仍保留独立轮换，不把情绪选择变成只查支持旧认识的材料。

反向变化沿 episodes appraise / life / inner 候选 / unions 同一报告采用。自动化证据为 CategoryEventsEntryPointsTest 的同源跨类、回忆、独立变化和本次新增的离页相关事件；真实 MCP 与真实情绪解释质量未验证。


## 2. 驱力、需要、欲望、目标与选择

InnerFoundations 提供既有七驱力、需要目录及求生欲专门规则；InnerStructure/CategoryEvents 分别保存具体愿望、对象和阶段。LifeUnderstandings.direction 保存目的、目标、投入限度与野心的完整文字，不形成第二份待办。已选入的状态经同一请求影响模型判断，ActionUnderstanding.decisions 保存具体选择，SubjectActions.validateDeclaration 核验操作关联。

已有 InnerInteractions 的成员、解释、支持/牵制关系与可选时间规则保持。未增加数值阈值行动、失败惩罚或分数最高者自动获胜。source/time/version 仍由宿主核验，心理方向由报告提出。

证据：InnerFoundationsEntryPointsTest 的冲突联合、求生欲、时间成员覆盖保护；SubjectLifeEntryPointsTest.experienceAndDirectionAreReadLaterAndCanSupportRealActionDeclaration。真实模型是否作多因素判断未由这些测试证明。


## 3. 人格、自我认识、价值、立场、关系与偏好

CharacterCardManager.combinePrompts 继续把人设作为初始参考；InnerStructure 的已采用记录为持续依据。RelationshipNetwork.promptSections 对启用助手不再次注入旧关系权威；其编辑入口提交建议。InnerRows/InnerMaterialSelection 提供相关、轮换、全文分块读取。候选编辑新版本、真实后续轮次读取、准确全文采用及旧版本历史保持。

断点：真实工具结果发生在请求开始后，旧 development 校验误把它当成尚未发生的依据，阻断由操作结果发展出方法/自我认识。此次只放行“候选提交前已经取得、实际送达、带宿主署名和回执哈希”的本轮操作材料；仍不得同轮采用，普通材料的时间要求不放宽。

证据：InnerStructureContractsTest 新增时序正反例；SubjectGrowthFlowTest 第三至五轮从实际计算回执提出、后续读取采用并再次使用完整方法内容。现有导入窗口、候选编辑及多助手测试保留，无数据重置。


## 4. 心理事件与持续影响

CategoryEvents.apply 按稳定 ID、类别、原始 evidence 和 expected_version 更新。focus 是单独的主体排序记录，容量 3—6 与全部历史数量无关。当前强度、重要性、注意、解决状态相互独立；相关旧事可再次进入正常材料，原事实与旧解释不被重写。

已有入口为报告 episodes、companion_internal_state episode/focus/source/category_versions 与私域详情。本次只补相关选择，不改变原事件语义或自动解决事件。

证据：CategoryEventsEntryPointsTest 的 A/B/C 独立变化、重点替换、归档来源、中断 journal 恢复、大量事件，以及新增离页相关取回。正常结果可以有相反方向或无变化。


## 5. 联合关系与跨模块作用

InnerInteractions.apply 在同一权威事务内核验真实成员版本、来源及重复权威别名；解释、成员转换版本与可选时间规则一起保存。material 同时展示原成员引用和 current_member_versions，旧联合不会覆盖成员后来变化；结束联合不删除成员。

局部补丁已把 union 加入相关检索，本次继续保留。InnerTemporalEffects 只执行明确采用的规则，并用成员版本使旧锚点失效；不对稳定人格或求生欲自动改值，不递归制造现实事件或唤醒。experience/direction 已能作为成员或具体决定依据，本次补其相关取回。

证据：CategoryEventsEntryPointsTest 的联合原子性、按需 native read、双向相反变化及循环一次边界；InnerFoundationsEntryPointsTest 的联合时间及求生欲拒绝；SubjectRecallArchiveTest 的旧联合修订/结束索引失效。不是通用分数计算器，也不声称模型必定选择某个结果。


## 6. 兴趣形成与长期自主活动

正常报告保留 decision/direction；companion_reading save_source/progress 和 companion_todo save 保存来源与实际进度。只有明确 companion_explore schedule 或待办 submit 才有可认领预约。ExplorationStore.next/CompanionRuntime.evaluate 接上实际普通模型和工具流程；完成不自动续约，暂停/中断不会悄悄重跑。

本次复用了 exact saved_item/dispatch 回执、purpose 检索、去除完整批注历史重复注入。ActivityNotes 原触发支持中断后相关取材；BranchSprout 对连续性助手不再复制已有聊天摘录到预算外。

单槽覆盖核查：exploration 原本就是一个自由判断预约，不能称多个兴趣均已预约。本次保留正常替换行为，在真实工具回执新增 schedule_scope 与 schedule_replaced，准确返回被替换的时间/目的，不伪造第二计划。私人待办已有逐项独立 dispatch，正常提交两项不会互相覆盖；探索轮禁止 submit 的原防绕额度规则保留。新测试验证两个探索预约只认领新槽位，两个私人事项分别提交、暂停其一不取消另一项。私人待办/阅读存储调整的兼容方案仍等待确认。

五轮验证在 SubjectGrowthFlowTest 中走真实消息消费、实际计算器、原预约认领和轮末报告，模型输出与到期时钟受控。Fixture 将模型路由替换为模拟 Provider，ConversationService 的准备适配器直接调用生产 InnerStructureContext，工具续轮仍用生产 normalizeConversationHistoryForModel；它没有覆盖图像识别或完整普通提示词组装。CompanionContext 的正常材料组合另由既有入口测试覆盖。程序接线通过与否以最终 CI 为准，不能据此声称真实模型长期研究成功。


## 7. 意向、决定、行动与实际结果

SubjectActions.claim → started → finish 是执行前登记和真实回执持久化；ToolExecutionManager 在短事务外执行原工具与权限检查。ActionUnderstanding 的技术反馈与主观解释分开。报告失败不撤销回执，重复 hostInvocationId 回放原结果，unknown 不盲目执行；新的明确再试有独立身份。

已有正常接线保留，不重写执行器。SubjectToolMaterials 只把确实输出到续轮的回执注册为材料，未送达不算理解；回执读取不会自动更改心理参数。

证据：SubjectActionEntryPointsTest 的拒绝/部分/未知、并发、重复、恢复、候选原子失败及跨助手群聊隔离；EnhancedReportIsolationTest 的完整共享流异常隔离。真实外部执行端的恰好一次不作保证。


## 8. 经验反馈与自我修正

ActionUnderstanding.feedback 关联真实操作，LifeUnderstandings.experience 保存有来源、范围和不确定性的认识；稳定方法/偏好/自我认识进入原 inner 候选流程。原始结果、当时解释和后来修订都有独立版本，非一条混合“记忆”。

断点：life 仅轮换且 material 先序列化嵌套来源后截断；相关方法可能未送达，来源复制反而占满材料。本次接入相关检索，投影只取理解正文及适用说明，并提供完整原文工具入口；存储原文完全不改。

SubjectGrowthFlowTest 检查实际均值回执、方法候选的后续采用、另一组实际计算以及第五轮完整方法读取。模拟输出写明选择，只证明真实通道取得和保存这些信息；不证明模型自行发现了方法。


## 9. 新内容进入原有运行

InnerStructure 的开放 kind、CategoryEvents 的类别内事件、ThoughtPool 的实际念头、LifeUnderstandings 的经验与方向继续沿原存储。新增内容有当前索引、历史和再次取用，不靠预设固定心理内容表。InnerMaterialSelection 检索已采用内容，候选轮换及 native 分块读取提供后续采用资格。

本次补新经验/方向、旧事件和联合的相关选择；不把未采用兴趣自动转成长期任务。分页和本轮预算不改变存储原文及记录数量。私人待办和阅读旧总量门槛/历史覆盖仍列为待处理，不能宣称所有外围资料已无限保存。

证据：InnerStructureEntryPointsTest 的动态细类、具体愿望、偏好和全文采用；InnerMaterialArchiveTest、SubjectRecallArchiveTest 的有界查询和大记录；本轮五轮测试验证新方法进入后续独立活动。


## 10. 能力使用与发展反馈

SoftwareCapabilityArtifacts 复用已有计算表达式运行器，build 生成不可变本地产物及版本，run 真正执行，previous_version 关联调整。它支持纯算术能力，不能宣称任意程序安装或真实身体。普通脚本/工具包仍沿原权限与运行器。

SubjectActions 记录产物及实际结果；反馈经 ActionUnderstanding/life/inner 影响以后取材和操作声明，不以工具成功等同成长。此次没有改变已有运行器、权限或计费。

证据：SubjectActionEntryPointsTest.realCapabilityBuildRunAdjustAndFeedbackUseNormalTools、interruptedBuildRecoversOnlyItsOwnImmutableArtifactWithoutExecution；材料相关取用新增测试补后来取得解释。真实任意插件安装和外部平台未测试。


## 11. 开放式思考与元认知

新问题可成为 thought/待考虑的 direction；是否长期保留由报告与原候选流程决定。方法、思考偏好、自我局限、旧判断修订分别有相应结构，实际影响由下一轮材料和操作声明承接。原 instructions 不要求认同用户、固定成长方向或反复生成到行动。

本次不添加“必须成长”的计划或特殊元认知模型；补齐新方法、旧判断和关联的相关读取与实际操作结果反馈。跨领域是否有效由真实模型判断，代码不凭关键词代替心理选择。

证据边界：可证明新问题/方法能保存、修订、进入后续工具流程；目前没有真实模型的开放研究实验、长期成长或意识证据。


## 12. 连续性与运行恢复

唯一助手归属、InternalStateStore 版本、原归档 journal、SubjectMessageInbox、SubjectLife、ActivityNotes 和 SubjectActions 保持。用户插入消息先持久化，活动在原生命周期边界取消/暂停，真实回执及进度保留。睡眠暂存实际事件，按原明确结束安排/选择续接；不在读取、重启或空闲时凭空新增模型调用。

本次恢复检索使用活动原触发，不使用另一窗口或其他助手的活动内容。原睡眠、延期、梦过程、醒后随记、短回应后续接机制继续工作；研究调度须通过既有探索许可，不从普通待办绕开。

证据：SubjectLifeEntryPointsTest 的事件暂存、消息幂等/并发、梦取消、恢复材料轮换、恢复活动版本；EnhancedReportIsolationTest 的下一条可发送、忙状态清理、共享流失败隔离。真机系统后台准时运行、语音硬件及长期性能未验证。


## 整体冲突检查与保留项

- 新旧权威：已采用人格与关系只由原 inner 采用，映射旧状态是兼容地址，未初始化第二人格；求生欲和联合时间保护保留。
- 提示与规则：连续性助手无需每醒来必续约；原非连续性助手行为保持。探索权限和每日额度不能通过待办连接被绕过。
- 材料：决定/反馈已在权威 prepare 注入，移除外层重复；life 同样在一个 prepare 内取回；聊天枝芽不再重复复制；工具正文仍占完整窗口，材料清单不再复制其全文。
- 历史与清理：索引为可重建、有容量限制的技术缓存。原缓存只能放四个类别，新接通六个类别后会相互淘汰，导致每轮重新扫描当前记录。本次改为四个主体缓存，每个主体六类共同受 1,048,576 的估算索引权重限制；小索引可共存，超限按最近使用淘汰可重建索引，容量不增加为六份独立预算。该权重不是实测 Java 堆字节数。失效/首次构建仍逐行扫描当前头，不扫描版本历史；过大的索引不常驻，这部分最坏读取时间随当前记录量增长，不能宣称固定时间。未清理人格、经历、未完成操作或真实回执。旧待办 80 条历史、300 项门槛及阅读 60 来源/目录/进度覆盖需要确认后的兼容调整。
- 普通失败：保留 +204 的共享流异常隔离、最终报告失败 notice、实际动作不重放、消息发送结束清理；测试不删断言、不关闭功能。
- 无关正常链路：没有修改订阅、语音、摘要、书房整书存储、外部记忆服务、签名和发布策略。完整回归仍要覆盖共同调用链。


## 参考边界与剩余验收

引擎本机 HEAD 为 0567ba4；有其他工作区未提交文档，保持原样，只读代码。subject_growth_service.py/learning_service.py 的来源根、版本、候选与后续采用提供参照；Operit 复用已经存在的多助手状态、归档和工具，不照搬引擎单主体部署或建立第二权威。

最终仍需：兼容存储确认与实施、上述真实入口测试取得结果、最终完整 Android/Windows 回归与构建、最终 main 再核对、合并发布及 APK/更新清单/签名/版本验证。本文件在这些步骤完成前不作为“整体闭环完成”结论。
