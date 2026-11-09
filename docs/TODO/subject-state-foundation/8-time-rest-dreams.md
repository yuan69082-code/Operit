# 第6批：时间、休息、梦与恢复

从 ae2bb38 / PR #57 接续，分支 feat/subject-time-dreams，依赖 #57 → #56 → #55 → #54 → #53。只读参考引擎及三个指定文档。用户最新授权在第6批验证完成后整合这六批、合并并按现有双端渠道发布；此前不合并不发布的限制已取消，不包含无关 PR。本页随实现更新，不代表尚未运行的验收已经通过。

## 已核对与施工范围

- 复用 InternalStateEngine / InternalStateTimeRules 的逐实例时间锚点及已采用规则、原子报告/类别归档、统一材料预算、正常模型通道与既有预约。时间读取不另起模型调用。
- 旧主动联系自动初次安排、恢复失败后重排，以及 ExplorationPolicy 的默认三小时续约，与指定主体要求冲突。限定启用内部状态的助手停止这些自动判断，保留明确安排和其他助手原行为。
- 参考引擎 domain/dreaming.py、services/dream_process_service.py 的梦实分源、生成身份和原结果恢复；不移植周期 dream_interval、保留分数或强制现实素材引用。梦允许没有现实剧情依据，不作为他人现实行为证据。
- 休息/醒来与梦通过原有私聊或已安排后台业务轮的可选内部报告采用。没有每次休息必做梦或新周期模型调用。设备停止与恢复不提交休息/醒来候选。
- 新记录进既有类别增量归档，按需查询，纳入既有备份恢复和统一预算。保留前五批及书房实际活动修复。

## 验证待完成

正常材料组合、报告采用、私域查询、梦后新理解、跨窗和重启、既有备份恢复、时间重复/回退/离线、无自动续约、权限与全量回归。真实模型及真机证据与构造模型输出分开记录。

## 实际入口与时序

- InnerStructureContext.prepare 在 IO 上从既有归档读取 rest/dream/dream_understanding 的有界页，经 SubjectMaterialBudget 与其他材料一起选择；群聊排除私域材料。未选入的全文可由正常 companion_internal_state get 查询，SubjectToolMaterials 仅在实际完整返回送进工具续轮后登记来源。
- 正常 InternalStateReportStream → InnerStructureContext.submit → InternalStateReports.apply → SubjectRestDreams.apply，使用原主体帧、语义版本、重试回执和原子提交。rest 是主体明确选择而非系统状态；dream 使用宿主 frame 派生稳定 ID。每轮最多一段，不定时造梦，也不要求每次休息生成。
- 当前业务轮读取旧版本；休息、梦和理解在轮末合法报告中保存。后续正常私聊或已授权具体预约可读取。生成梦发生于已有该次模型调用中；没有额外必需调用。若主体明确安排以后继续，将由原预约在届时触发正常模型调用，并非零模型成本的离线做梦。
- 醒后理解须引用确实提供的 dream_not_reality 来源，保存感受和解释及各自版本；普通内在候选可引用同一梦，仍须先提出、以后采用。技术保存成功不意味着已经理解。梦与理解可重新查询，不重复采用旧效应。
- 当前头与各版本复用 InternalStateArchive.category_current/category_versions；AtomicFile 只保留有界提交日志。归档事务失败可在下次读取重放索引，报告重复不会造第二段梦。没有复制整个历史计算材料。
- 备份仍使用 RawSnapshotBackupManager 的完整 files/shared_prefs/databases 路径；companion 内的主体文件、归档和能力产物均包含。恢复 UI 原本要求重启；测试仅模拟 ChatHistoryManager 单例重建，不冒充手机杀进程实测。
- 时间仍按既有逐实例 at 锚点和有效条件规则计算；重复时间、倒退时间不重复结算，离线正向间隔按规则补算，不另触发模型。无可信外部时间源时，跨设备重启后的正向墙钟跳变无法与真实离线间隔完全区分；未新增网络校时或保活。
- 求生欲是已采用的细分 need，不挂接旧数值时间引擎；联合、休息、恢复不替它改值。其他状态继续原先各自采用的规则，不统一冻结。

## 六批接线收尾对照

1. 主体底座：CharacterCard.id → InternalStateStore/SubjectStateBoundary → CompanionStore，前后台均使用正常请求帧。保留 global/session 与 user_edit 来源，原子版本和归档不重建。
2. 内在结构：InnerStructure 的 propose/adopt 与人格 development 校验，InnerStructureContext/CharacterCardManager 提供采用身份及初始配置边界。人格、自我认识、关系和偏好持久保存，后续正常请求按统一预算读取。
3. 类别事件：CategoryEvents 的 episode/focus/experience_link，独立事情及版本归档；本轮没选入不会删除或解决。求生欲特殊规则保留。
4. 联合与材料：InnerInteractions 与同轮报告原子提交，成员版本独立；SubjectMaterials/SubjectToolMaterials 保留实际提供来源，SubjectMaterialBudget 统筹默认2048、上限16384 Token；本地分词/远程估算和完整窗口核查沿用。
5. 操作与反馈：ToolExecutionManager → SubjectActions 的执行前事实与结果回执，ActionUnderstanding 的决定/理解另行采用；SoftwareCapabilityArtifacts 的真实本地计算能力构建/运行保持。恢复不盲目重放未知操作。
6. 时间休息梦：既有时间引擎、CompanionSchedule/ExplorationPolicy 限定接管、SubjectRestDreams 私域报告与上述正常读取链。没有周期梦、闲置兜底、额外身份或记忆库。

书房独立修复 d0701b8 及后续修正随依赖保留：实际回复 ID、后台表达先持久化、同助手按权限查询、未知结果不冒报成功。书房整书扫描限制仍在，不在本批重写。

现存验证边界：真实模型理解/表达质量、手机长时间后台与进程恢复、实际 MCP、本地分词器设备运行未实测。模拟模型测试证明报告和数据链，不证明主观体验质量。无权内容仍由各入口限制；公开表达由原权限和正常选择负责，不默认转发梦。
