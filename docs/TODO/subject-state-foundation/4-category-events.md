# 第 3 批：类别内部事件、重点与特殊规则

承接未合并 #54 / b456315，其依赖为 #53 / c6068de；开始时主线 8b09126。独立分支 feat/subject-category-events，不合并或发布。引擎 0567ba4 及园园指定的三份工程流程材料只读，已实际查阅。所有施工与验证产物放 Operit。

## 对应与边界

- 复用第 1、2 批 CharacterCard.id、主体事务、语义版本、帧重试、归属检查、inner 候选采用、AtomicFile 与同一 SQLite 归档。
- 参考引擎 open_needs 的开放类别及需要引用；dynamic_mind 的 episode/source_keys、解释与现实来源分离；dynamic_mind_service 的 ROOT/BRANCH/REOPEN 与原始事实边界。没有复制按对象/类别生成事件 ID 的旧合并方式，也没有带入限定情绪重开白名单。
- 原引擎 subjective_context_projection 的选材数量不等于永久重点排序。本批每类3–6个重点位置、独立重要性/注意力/解决状态、求生欲自主数值且理由可空，来自本次明确要求和三份增补材料。
- 不搬通用 need strength 的 overflow、固定强度变化、affect_union 或联合演化；第4批以后不得暗中改变求生欲。旧数值定义及时间仍保留原义，不能从旧“生”推算求生欲。

## 已接线的契约

原 operit_state_report 可选 episodes 数组，使用同一帧、同一提交与拒绝边界，不新建报告或模型调用。appraise 是待宿主校验的本轮候选，轮末采用；与人格不同，不要求心理事件先等待另一轮。正常链为 CompanionContext / InnerStructureContext.prepare → 原流式报告解析 → InnerStructureContext.submit → Reports → CategoryEvents → 下一次正常请求材料。仍是轮末提交，当前正文不会读取本次提交后的版本。

category_ref 指向已采用 inner 类别记录；id 是独立心理事件稳定 ID。objects/goals、intensity、importance、long_term_impact、attention、status、understanding 分别保留；basis 明确 input/result/recollection/reinterpretation。原事实仍是 existing events，心理事件不会追加伪装成新现实输入。引用绑定原事实哈希，跨类别可以共同引用。related 保存 same_matter/split_from/merged_from/corrects 的明确关联，旧版本及原始事实不删除。不同事件不会因关键词或同对象合并。

focus 由主体明确给类别、原版本、capacity、按顺序的事件 ID 和变更理由。容量3–6，名单可为空或不足三项。技术只校验存在、类别、版本、容量和理由格式，不评分，不按强度、年限或最后访问时间排序。历史事件总数无限定，不自动解决或清理。当前请求的分页选材游标与重点顺序分开。

inner.record 的 experience_refs 是本次明确关联的最多6个事件；必须走原 propose/adopt，身份发展仍验证原文、准确前后态与证据。采用后增量保存关联边，后一次关联不删除前一次；偏好期限、配合行为、取向完全沿用第2批。后续可按记录 ID 分页取得全部累计关联。当前候选所列引用并不宣称是全部经历。

广义生为 need/broad/semantic_rule=life；求生欲为 need/specific/semantic_rule=survival，parent_ref 指向前者。strength 由主体给出有限非负数，允许零，不设默认高值；objects/goals 支持多关联。只有该需要的 propose/adopt 理由允许缺省或空，其余证据、版本、归属仍校验。数值只由此候选采用路径写入，不经过旧时间推进；禁止 legacy_ref 混入第二份强度权威。归零没有停止/删除/关闭操作。标记采用后不能无声移除。

## 保存、恢复与旧数据

继续使用 internal-state-history.sqlite；category_current 保存事件当前头、重点和经历关联，按 scope/kind/id 或 parent 索引查询；版本增量进入原 history 的 category_versions。没有第二历史库或全量 JSON 事件表。正常材料每类读取最多3条索引记录、选完整1条，额外材料上限12000字符；工具查询每页8条+一条分页探针，SQL LIMIT/OFFSET，ID 查询 LIMIT 唯一键，不先加载全量历史。

category_commit.schema=1 是当前快照中最多64条的持久重做记录。报告验证完全在 detached document 上，AtomicFile 成功写入才是提交点；下次读取先在一个 SQLite 事务重放该记录，再允许后续读取/写入。category_commits 的帧 ID+摘要保证重复重放不重复产生版本；事务中断回滚，下次读取继续。索引提交失败不启动模型或工具重跑。只有已提交记录可以重放，不允许直接把未采用候选写入索引。原主体锁覆盖本地读改写，不覆盖模型网络调用。

旧数据及原迁移检查点完全保留；不把旧日志每次数值变化升格为心事、不虚构对象、不重新解释窗口局部值。未明确关联的旧来源仍在原 events/experiences，正常材料标明尚未关联，待主体通过候选关联。无需全量心理事件迁移；首次使用创建空索引/schema，重复检查不会造经历。

companion_internal_state get section=episode/focus/experience_link/category_versions/source 提供现有工具内的有界读取。source 按 ID 同时支持热记录和已归档事实。旧格式不带 episodes 时新数据保持原样；非法报告不改已接受事件。不保证旧版客户端能正确执行新事件契约，恢复应保存当前主体文件及同一 SQLite 整体，不能启动两个写入者或只替换旧快照。

## 验证与第 4 批交接

新增 CategoryEventsEntryPointsTest 经过正常 prepare、流解析、提交、CompanionContext 后续组合，以及真实 AtomicFile/SQLite；覆盖同类A/B/C、共同来源、回忆、重点替换、超过六件、求生欲理由空/零/时间不变、多经历关联、跨窗和存储重建、其他主体隔离、过期与重试、重做记录恢复。构造报告验证数据接线，不能视为真实模型表现；完整结果在 PR 更新。真机、模型与系统后台调度尚未实测。

第4批沿用 CategoryEvents 与当前宿主读端，事件版本在 category_versions，类别权威仍是 inner.records，经历关联是 experience_link。复杂跨模块双向作用尚未做；不得另建状态权威、历史库，或绕过人格/关系/偏好采用与求生欲特例。第5批行动反馈、第6批梦/休息仍按现有六批规划，不能把本批的 source 关联说成完整作用已经成立。

附加的书房/聊天/唤醒实际结果一致性缺陷单独提交，见后续交接，不混入心理演化规则。
