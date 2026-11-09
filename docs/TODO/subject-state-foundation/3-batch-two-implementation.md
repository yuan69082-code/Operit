# 第 2 批：完整内在结构

依赖未合并草稿 PR #53 的 c6068de；默认分支仍为 8b09126。参考引擎 0567ba4 只读。新分支 feat/subject-inner-structure；不合并、不发布。

## 对应关系与实施顺序

- 复用：CharacterCard.id、CompanionStore.updateSubject、请求帧、语义版本、报告去重、原 AtomicFile / SQLite 历史归档。轮末提交时序不变。
- 适配：dynamic_mind 的主观记录与实际事实分离，open_needs 的开放类别和层级引用，open_affect 的解释/对象/未解决事项，person_relations 的有方向认识；不搬固定强度公式或完整事件机制。
- 适配：growth_process / GrowthProcessService 的候选与已采用区分、精确变化范围、原始引用与内容绑定、前态版本核验；采用 validate_personality_process 的明确过程对应，不使用旧通用 Learning 的计数/置信阈值。
- 不等同：旧四类数值维度不等于完整需要/人格；保留其原值、时间与来源，仅可由新记录明确引用，不自动重命名迁移。关系旧编辑是配置/表述，不是双方共同事实。
- 新增：偏好的期限、取向、主体偏好/配合行为、情境和例外；持久候选与采用流程，正常请求按预算读取。

## 实际结构、采用与归属

`inner_structure.schema=1` 位于原 `internal-state:CharacterCard.id` 当前文件中，不建立第二主体或另一数据库。`records` 保存当前已采用记录，`pending` 保存尚未采用候选；变化放在根部增量 `inner_history`，由原 InternalStateHistory / SQLite 归档。现有 global/session 数字实例、时间规则及定义完全保留，不粗暴合并窗口局部值。

- 每条记录有开放 `kind`、描述、状态、对象/情境/例外；family 负责契约，kind 不限词表。
- drive.direction；need.level 与 parent_ref 表达广义/细分；desire 必须关联已采用需要/驱力及具体对象。拒绝循环和不存在的父记录。
- emotion / sentiment / feeling 分别是当前反应、持续情感、体验解释，保存 current_state、unresolved 和来源；没有公式将情绪减弱等同问题解决，也不联动改写人格。
- personality / self_knowledge / value / stance 每条独立版本、准确前后态与来源。仅在明确采用时成为主要身份依据，不做“聊够几轮”“达到某分数”的判断。
- relation.object_ref 使用现有角色 ID 或 user；方向为 owner → object_ref，label 与 understanding/feeling 分开。一个对象只有一个当前关系记录 ID，停用保留历史。
- preference.aspect 开放扩展；orientation 为 like/dislike/neutral/uncertain，horizon 为 long_term/short_term/contextual，ownership 为 self/accommodation；context/exceptions 必须明确给出（可以空），对象可选。思维方式只保存自述倾向/方法，不保存模型隐藏思维链。

原报告增加可选 `inner` 数组，与 updates/create/revise 共用同一个帧和原子提交。`propose` 保存 record、expected_version、origin、evidence、reason；`adopt/discard` 只处理本帧见到的先前候选，同轮不能先提出再采用。模型不直接写当前权威。

采用核验 owner、准确原版本和前态、候选内容哈希、真实来源哈希。人格相关类别还需 development.account、before、after、anchors(ref/quote/interpretation)：引文须存在于原文；发展来源早于提出候选的输入帧，当前断言、时间演化、状态报告不能伪装成先前成长依据。明确采用初始配置可用配置原文，但保留 initial_configuration 来源；已有导入卡没有单独导入证明的，不能补造“导入人格经历”。宿主只校验对应关系，不评分观点是否值得拥有。

来源不局限于本轮预算选中的事件：经现有 get 查阅的本主体历史可以按明确 ID 引用，最多8×6个引用定点恢复，且必须在帧的时间/序号边界内；不是全量读历史。原事件只有节选时只验证已有节选，不冒充完整资料。配置依据使用未加入宿主提示标签的原配置文本；不会把宿主写的优先级说明当作人格来源。MCP/资料读取仍用既有工具，本批没有建立跨系统学习材料导入协议。

这些规则适配引擎 `growth_process_service` 的准确变化范围与来源对应、`subject_growth_service` 的采用边界，以及 `learning_service.validate_personality_process` 的真实过程验证。没有照搬旧通用学习的置信度/次数门槛，也没有另建学习平台。配置/引文验证不是“已证明具有某种情绪”的宣称。

## 正常入口与权威

正常 ConversationService → CompanionContext.compose → InnerStructureContext.prepare 读取角色/关系来源和主体状态；旧后台、群聊、工具续轮仍使用这一请求帧。正常 EnhancedAIService 的轮末保存通过 InnerStructureContext.submit → InternalStateStore.submit → InternalStateReports.apply → InnerStructure.apply。没有增加模型调用，仍是完成业务轮之后提交，采用后的内容供后续请求读取。

CharacterCardManager.combinePrompts 保留原文本并标明初始配置。CurrentRequestContext 在可编辑提示词 hook 后绑定真实宿主帧与持续人格优先级，保留身份事实、工具契约、权限及运行限制；未采用时初始行为仍可用。用户修改卡/语音补充/标签不会修改 records；候选如果引用的配置已变化会拒绝，要求重新提出。已接受报告的重试仍返回原回执，不因后来配置变化重复应用或损坏状态。

旧数值维度仍唯一负责数值与时间；新记录可用 legacy_ref 明确引用，不自动复制数值成完整人格。ThoughtPool、AttentionGear、记忆、MCP、资料库保持原职责。

启用内部状态时，RelationshipNetwork.save/delete 改为保存有来源的编辑/结束建议，原文保留在原事件中的 inner_source；不直接覆盖已采用关系。用户编辑仍是 user_relation_proposal / user_edit，助手建议明确作者。关系工具与原编辑器说明采用状态；模型只读自己的已采用关系，原关系表仍按旧可见性保留旧表述（标明 legacy_unadopted）。未启用时原功能不变。关系展示从 records 派生，旧关系表不反向同步新值，因此没有两份当前权威。关系建议从实际工具调用继承 hostInvocationId 去重；拒绝同一次重试换内容。

## 容量、兼容与恢复

- 原 SubjectStateBoundary schema 1、语义版本、AtomicFile/SQLite 事务、来源查找、去重与冲突拒绝沿用。没有模型或工具网络请求占有状态文件锁。
- 首次准备只加入结构 schema/空容器，不转换、清空旧数据，不把 characterSetting 伪装成亲历。原 schema 容器重复准备不重置；写入中断使用原 AtomicFile 恢复，历史归档先提交再换快照，继续复用第 1 批 checkpoint。
- 当前记录最多128、候选32、操作8/轮；身份材料最多6000字符，容量不足拒绝整个事务，不能截断已采用身份。停用记录仍占当前记录容量，本批不引入删除历史/自动人格淘汰；达到容量时须明确修订已有记录。
- 新选材 JSON 总预算14000字符，完整保留全部活动身份，其余按相关性和游标轮换完整记录。名录8项；来源/对象名录16/32项，并给出总数。原数字选材预算独立保留；这里不宣称完整请求只有14000字符，也不把字符当token。
- 新结构使用自己的有界选材游标，避免被旧数值条目的较短轮换周期饿死；游标只是读取调度，不是人格或情绪演化。较长关系编辑只在正常选材中给出有来源通知，完整原文通过原 get 查阅，跨窗口可接续。
- 每条记录及来源数量有契约上限。当前历史保留最多40条（且沿用2MiB字段限制），完整变化另存原归档；get 的 inner.records/pending 和 inner_history 每页8条，分别使用 inner.next_offset / inner_history_next_offset。原 events/versions 仍每页20条。不是先读全历史再截断。
- 正常读取及来源查询/提交在 IO 调度器；关系编辑器新增的持久读取也移出主线程。原文据不足时拒绝，不补造理由或证据。
- 旧格式不带 inner 时保留新结构；无报告、坏报告、过期报告不损坏已采用值，不重跑业务轮。错误类型的 inner 不能被当作空数组忽略。原报告解析16384字符上限不放宽。
- 旧客户端不会理解新结构/人格优先级，不能宣称降级仍具有本批行为；旧格式兼容指新版接收旧格式时不清空新数据。降级前保存完整 companion 文件及 SQLite 归档；恢复不得只换一个旧快照覆盖较新的历史，不能同时启动两个写入者。

## 验收与第 3 批交接

新增正常入口集成测试使用真实 CharacterCard、ChatHistory、CompanionContext、请求投影、轮末提交适配器、AtomicFile 和 SQLite；覆盖跨窗/重启/隔离、配置与采用优先级、候选证据/版本、关系建议、偏好语义、旧格式、历史分页。契约测试补开放层级、准确前后态、来源变动、停用人格、容量与选材；第 1 批原迁移中断、global/session、并发和报告隔离测试继续运行。实际 CI 结果见本 PR，未完成前不宣称通过。真机、真实模型、真实后台系统调度未验证；提示词优先级测试验证发送材料，不保证模型必然表现某种主观状态。

第 3 批沿用 InnerStructure、同一 inner 报告、主体事务与现有历史；不得另造身份、人格存储、关系权威或归档。待完成类别内部事件与特殊规则，尤其对象事件与需要、偏好形成/改变/暂停、相同类别不同事情的关联。kind 可扩展不等于已经实现完整事件分类。第 4 批补双向作用，第 5 批补完整行为反馈，第 6 批补时间/休息/梦；本批没有自动执行这些后续规则。人格配置和用户建议始终保持来源，不能让未来事件规则绕过准确前态与采用边界。
