# 主体存储与正常入口

主体继续使用 CharacterCard.id。现有 internal_state:<id> 开关决定参与者，不新增选择器，不把主动联系 target_role、窗口或模型当成身份。internal-state、thoughts、attention 三个现有职责域通过 SubjectStateBoundary 和 CompanionStore.updateSubject 共同约束；数据仍分开保存。关系仍在 GroupStore，人格仍在 CharacterCard。

## 数据版本与采用

subject_boundary.schema=1 标识本批采用格式，owner 就是助手 ID，domain 是职责域。revision 是该域实际提交版本，last_source 记录宿主来源；报告原有 semantic_revision 和定义 version 不改含义。普通客观事件、时间更新会改变提交版本，报告的冲突判断仍沿用语义版本及事件顺序检查，不能用提交版本的任何增加一概否定所有报告。frame.stateVersion 记录请求读取时的提交版本，日志包含主体、窗口、帧、读版本和保存版本或拒绝码。

采用只补充文件归属和宿主元数据，不能补心理原因、证据或旧作者。保留所有原有字段、实例作用域、当前值、定义和未知扩展字段。原先没有来源的内容标记 unchanged_or_unknown，旧源信息不改写。人格文本不因此成为主体自己形成的认识。

每个作用域沿用 CompanionStore 的进程内文件锁和 AtomicFile；模型和工具网络操作不在锁内。Android 的前台聊天、群聊、感知与主动活动运行于宿主主进程；repair/crash 辅助进程不作为第二写入者。本批不引入独立引擎进程或跨设备同时写入。未来若增加第二个执行进程，必须先实现进程间写入租约，不能复用当前进程内锁冒充跨进程保护。

## 正常接线

- ConversationService → CompanionContext → requireRecipient/prepare：从宿主元数据验证私聊归属或群成员，准备帧、选取既有状态/念头/待办。group_context 与用户发言区分，不推断群历史中未知作者。
- EnhancedAIService 最终成功业务轮 → IO 上 submit：既有报告幂等和冲突检查；失败仅报告非致命错误。没有增加模型调用，没有实现同一轮先提交再据此生成回复。
- ChatHistoryDelegate → noteMessage：依据窗口归属、群消息不可变作者 ID 分发事实；重复消息不重复更新。接收主体、作者和来源窗口分别保存。
- ToolExecutionManager → noteTool：串行/并行路径使用宿主生成的 invocation ID，参数注入/副本及同一结果投递重试保留 ID；不同实际调用不会仅因参数相同而合并。存档后的重复结果仍查索引去重。此保护不承诺对外部工具进行恰好一次执行，未添加自动重试工具。
- CompanionRuntime → noteEvent：既有唤醒和探索结果记录在对应主体。观察写入失败不重跑已完成活动。
- 详情编辑 → updateFromUser：保留 user_edit，拒绝助手借用户入口写入；现有语义版本校验继续防止覆盖并发修改。
- AssistantWindowRouter 保持目的地选择职责；切窗/重启后按同一角色文件读取。没有按模型或窗口复制状态。

## 冲突处理

XC 的外部原始数据与本地回执保留。内部状态开启后，正常上下文只注入内部状态，ThoughtPool 不重复单独注入；关注档位也不再把旧 XC 回执当作当前情绪依据。用户明确授权的外部 MCP 能力不被删除。

主动联系完成后的旧 XC 自动回传也在内部状态开启时停止，避免后台继续维护第二套自动情绪；未开启内部状态的助手原 XC 行为继续。人格与偏好的补充要求及已核对的注入路径见 [六批后续规划](2-six-batch-continuity-and-preferences.md)，尚未实现的采用机制不能被当作本批成果。

旧 attention_gear 继续作为原全局宿主配置使用，明确作者未知，直到观察助手自行设置自己的 attention:<id>。不得复制旧全局配置并宣称每位助手都曾如此决定。

关系编辑继续有独立权威和行版本，用户来源为 user_edit，历史缺失来源保持未知。内部状态已启用的主体不能被主助手代写关系或通过 assistant_admin 改人格/删除。用户仍可明确编辑。人格配置与未来主体人格演化之间的优先级、关系发展的规则均尚未实现，留给第 2 批；不得把管理编辑包装为心理成长。

## 历史、迁移和恢复

继续使用原 internal-state-history.sqlite。events/experiences/semantic_reports 沿用既有保留上限；versions 也只保留热区 300 行，并按维度和版本索引查询。当前实例旧字段的披露边界通过只读历史端口查原定义，不因归档而失去来源。状态投影复制必须使用 InternalStateDocument.copy，不能再用普通 JSONObject 副本丢掉历史端口。

联系时间不再随快照载入全部 archived_contacts；当前条件查询最新联系，历史时间结算通过每页最多 256 个时间点的有序流处理。时间积分规则未改，session 仍只看自身窗口。定义来源查询缓存最多 256 项；普通历史页最多读取 21 条。

采用时建立一次性 `<原文件hash>.subject-v0` AtomicFile 检查点。归档事务仍先于热文件原子替换，内容键去重使中断后可重复归档；检查点/热文件未完成写入由 AtomicFile 恢复。检查点可能是已归档后的有界文档，因此恢复必须保留配套 SQLite 归档。

人工恢复：停止应用及所有后台宿主，整体备份 companion 目录，恢复目标 hash 的 `.subject-v0` 为 `.json` 并保留同一安装的 SQLite 归档，再启动让采用重新执行。不要在应用运行中替换，不要另建第二个助手，不要把另一助手文件改名使用。回到检查点是显式回到迁移时刻，迁移后的已接受变化应从整体备份保留，不能宣称它们仍在旧检查点里。

## 验证记录

新增入口测试使用生产 prepare/submit、消息/工具/后台适配器和真实聊天元数据；存储测试使用 Robolectric 的 AtomicFile/SQLite。迁移夹具直接写旧文件仅用于模拟旧安装，不作为正常聊天实测证据。

覆盖跨窗口 global/session、其他助手隔离、群作者、工具幂等、旧帧拒绝、用户来源、并发事实、重启、归档提交中断、原子文件恢复、旧定义来源索引、大量历史的有界快照及分页。性能断言仅是 JVM 上宽松的回归界线，不是手机耗时或内存承诺。

CI 与真机未验证范围在 PR 中更新；没有手机、真实模型或完整感知后台运行实测。第 2 批禁止重新造身份、记忆/资料库、历史归档或额外自动状态注入层。
