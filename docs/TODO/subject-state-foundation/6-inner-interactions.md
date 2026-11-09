# 第4批：内在作用与统一材料预算

分支 feat/subject-interactions 承接 b868b9f 与书房修复 d0701b8，依赖 PR #55 → #54 → #53。本批 PR #56；不合并、不发布。实现已接线，最终验收结果待运行结束补充，未完成的运行不能计为通过。

## 原库对应与适配

参考引擎提交0567ba4，仓库与园园指定的三份工程流程文档只读。本轮读取这些文档及 domain/affect_union、open_needs、open_affect、dynamic_mind、person_relations、mind_intent，以及 dynamic_mind_service、person_relation_dynamics、continuity_core_service 的相关实现。

- affect_union 的稳定身份、成员版本、来源、解释、结束状态与单次非递归作用，适配为 InnerInteractions 的 unions 候选。原库 reanchor_changes 的固定 .25 系数及时间投影不直接复制；成员变化由正常候选明确提出，不产生第二次数值传播。
- open_needs 的开放类别、具体需要与独立解释和 proposed effect，沿用第2批内在记录及第3批类别事件。联合不覆盖成员，不新建需要权威。
- open_affect / dynamic_mind 的当下体验与持续内容区分，沿用 inner/episodes 采用规则。dynamic_mind_service 的关注参与取材，适配为有界查询和轮换，不复制固定疲劳系数或强制唤醒。
- person_relations / person_relation_dynamics 的对象、方向与来源，沿用关系候选；不复制关键词或固定亲密系数，不把另一方陈述当共同事实。
- continuity_core_service 对实际材料、来源、版本的核对，适配为请求帧 materialSources、interactionMembers、来源快照哈希，不建立独立模型调用。
- mind_intent 的候选与权威分离、支持与冲突并存，适配为 interpretation/decision/effects；decision 不代表已执行动作或新权限。

园园新增要求包括2048/16384 Token统一预算、求生欲不受联合/时间自动改值、保持多种感受且不重复计入现实事实、无行动不处罚/加倍/循环唤醒。原库默认系数不替代这些要求。

## 实际接线与时序

普通聊天及已有后台活动仍经过 ConversationService.prepareConversationHistory → CompanionContext.compose → InnerStructureContext.prepare → InternalStateStore.prepare。请求读取同一 CharacterCard.id 的身份、关系、偏好、需要、体验、重点事件及联合。CurrentRequestContext.stage/project 放入本轮宿主附件，不写成用户新消息。

原 operit_state_report 可带 unions；InternalStateReportStream → InnerStructureContext.submit → InternalStateReport.apply 是唯一报告入口。原 updates/revise、inner propose/adopt、episodes 与 unions 在同一 detached transaction 校验，全部通过才经 CompanionStore 和原 CategoryEvents 日志提交。任一项非法整份不采用，不重跑模型、工具或已发消息。

双向变化通过同一报告的原成员操作实际保存，联合记录其相互作用方向；允许反向变化、无变化和拉扯。人格/偏好仍须先候选、后续请求明确采用，联合不能绕过。读取联合不重放 effects、不调度唤醒。

仍是本轮读取既有状态 → 同次正常生成形成正文和候选 → 业务轮结束采用 → 后续请求读取。没有新增模型调用，不宣称本轮正文读取了轮末新版本。

## 联合、成员、来源与恢复

联合具有稳定id/version、owner/source_chat、成员及版本、对象、解释、决定、状态、证据、有向作用说明；提交记录成员前后版本/哈希。成员值由原记录负责。结束联合不删除成员；成员独立演变不被旧联合覆盖；停用数值成员在读取中标为版本不可用，历史仍在。

窗口局部数值成员使联合标记 local_chat，另一窗口不投影或改写该局部联合。持续成员跨窗共享，模型更换不改变身份；群聊不注入私人跨窗活动、记忆检索或私人联合。

同源材料可支持不同心理意义，保留同一来源ID/哈希；原更新器每轮同目标只更新一次。多个作用边记录解释，不重复驱动成员，不把 A→B→A 当递归计算。重试沿原frame/digest收据去重，过期版本拒绝，失效报告不能覆盖新值。

union 是第3批归档 storage_kind 的扩展，当前头与增量版本沿用 SQLite archive 和 category_commit 重做记录。热文件只留最后一次有界提交，不嵌全部历史。get section=union 按id/offset查询；category_versions 的id=union:ID取历史，每页8项。正常材料最多取一页，独立游标轮换，不先加载全库。

不清空旧值、不重分类旧经历、不补造证据。旧报告缺省unions不触碰联合。旧客户端不认识联合时不应降级回写；恢复使用支持新storage_kind的版本与原文件/归档成对备份。主权威schema及原迁移机制没有另造一套。

## 实际材料取用

- SubjectMaterials 使用助手已固定绑定的 MemoryRepository；未固定绑定时不猜全局空间，不跨助手查询，不要求重复选择已有配置。
- 当前输入至多3词，当前事件理解/对象及已采用类别至多3词，复用中文分词。ObjectBox 原库查询最多3条相关记录，加1条独立轮换记录，不先findAll。独立样本交替优先，避免匹配排除所有相反材料；技术相关性不等于主体重要性。
- 没有新增embedding或模型调用，不每轮访问全部MCP。原授权记忆/资料工具按需调用；SubjectToolMaterials 在真实工具续轮为实际返回附来源ID，失败仍是失败。正文只留原工具返回，不重复复制进主体材料。
- 原 ChatHistoryManager.recentAssistantActivity 与 StudyStore.recentActivity 提供真实保存消息、回复、批注关联；保留作者、时间、窗口、回复ID、保存状态与locator。saved不等于已读，不以模型自述认定成功。
- 只有实际选入的 retrieved_materials 才进入 frame.materialSources；查询失败不生成已读凭证。候选保存接收快照、哈希、类型与定位；后来原文变化不冒充过去读到的内容。数字体验也能引用本轮材料，在增量体验记录保留绑定。
- 旧聊天、摘要、记忆不冒充新发生或自动相信的事实。原文不改写，新理解仍须采用；不靠异步长期摘要保证刚刚回复的可见性。

书房仍是整书文件，recentActivity单书扫描随文件增长，未在本批重写。ObjectBox返回行数有界，但单条原始记忆大小与底层文本检索耗时仍受已有库影响，不将有界行数说成恒定扫描时间。

## 统一预算与完整模型窗口

SubjectMaterialBudget 统一选择身份、关系、偏好、驱力/体验、重点事件、联合、已取得记忆、近期结果、宿主材料及匹配世界书。默认2048、上限16384；companion_internal_state action=budget/tokens 修改本主体设置，走原归属与持久更新边界。它不是完整模型窗口。

已核对各通道 calculateInputTokens/TokenCacheManager：现有请求前能力为启发式估算，没有精确tokenizer。材料以 ChatUtils 汉字×1.5、其他UTF-16单元×0.25为基础，对非汉字非ASCII字符额外计UTF-8字节，再加25%余量，为固定附件外壳留128估算Token。metadata 明示 exact=false、自定义分词器误差上界未知。25%是余量，不是误差保证；代码、特殊字符、不同分词器仍可能偏离。服务端usage只能事后得到。

完整对象轮流争取空间，身份先获一次机会，不独占所有轮次；其余类别按请求轮换。过大对象不截断JSON，明确 omitted_counts 和原工具取回方式。未选中身份仍为权威，不用初始人设填补。材料游标不改写主体重点。

EnhancedAIService 在最终请求组装及工具续轮调用原通道输入估算，历史、指令、工具、附件计入一次，再核对启用的输出上限与配置窗口。超出估算窗口在发送前明确失败，不重跑工具；输出未配置或通道不可识别时标未知，不宣称已精确预留。多模态编码与模型分词仍有估算边界，无额外计量模型调用。

## 验收状态与边界

首轮联合草稿 ccbdfd8 的 Android Tests 42585454809 已通过。预算接线后42587376139的Windows检查与打包通过；Android测试在 syncSttModelAssets 的OCR下载校验失败，未运行到测试。后续运行结果待补，不能把前次成功当最新版本通过。

接线测试运行生产composer、CurrentRequestContext、报告解析、提交与下一轮读取，包括相反方向和环状作用说明、原子拒绝、幂等/过期、独立成员、窗口局部联合、停用成员、求生欲、分页历史、真实聊天/书房材料到候选采用、预算与工具来源；保留前三批和独立修复测试，不删除失败测试。

构造候选只验证宿主机制，不证明真实模型心理或行为。无真机、真实模型、真实MCP及系统后台调度完整实测；不保证模型必须形成联合或说预设台词。测试数量、构建签名结果在运行结束补充。

## 第5批交接

沿用 CharacterCard.id、InternalStateFrame、InnerStructureContext.prepare/submit、InternalStateReport 和原增量归档。联合 decision/effects、成员 transitions、source_bindings、材料locator与已有聊天/书房/工具回执，是后续选择和真实反馈的来源。

第5批区分决定、执行请求、真实返回、保存成功和未知，不把decision当执行收据，不把重复材料当新经历。保留第2批人格/偏好采用、第3批求生欲特例；不另建主体副本、记忆库或书房存储。本批不实现完整器官反馈、梦休息、闲置唤醒或数值强制工具调用。
