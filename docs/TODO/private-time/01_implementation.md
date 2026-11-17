# 实现与验证

- PrivateTimePolicy：时区与跨日窗口、活动范围、计划修订、进度与整理回执、合并逾期提醒。
- PrivateTime：同一SQLite活动档案保存配置/计划/精华，复用SubjectLife领取和后台执行。未设置不凭空预约，暂停取消无自动恢复；时段中途提醒仅来自已保存checks。时段结束只询问如何收尾。
- RoomTools：free_status提供完整菜单及格式，free_config/free_plan/free_progress/sleep_choice落实安排。用户消息、工具执行与真实回执继续使用原入口。
- PrivateTimePanel：私域→内部状态→自由时间，设置时段、人工记忆位置、可选睡觉/疲劳提醒、计划进度与精华观察。
- 精华角：独立草稿和确认内容，来源操作与历史保留；确认内容优先进入原有材料预算，全文384 token份额，草稿与旧版按需读取。超额不替换当前确认内容。
- 旧consolidation范围在醒来时交回主体选择，不在做梦中并行整理。

本地PrivateTimePolicyTest初始9项通过；完整Android编译、入口和回归测试由PR #75的同提交CI验证；发布仅在所测合并候选全部检查通过后进行。

边界：系统不是连续占用模型数小时；已选时段和检查点提供后续机会。MCP整理结果的主观意义由主体解释，程序只核对可取得的真实回执和已知只读操作。未连接的OB/HY能力不会伪造。历史不常驻上下文，技术缓存过期清理；个人经历不按程序猜测“无用”后删除。


## 双向闭环补接

正常InnerStructureContext → PrivateTime.planMaterials（当前/相关计划的一条索引）→ 同一SubjectMaterialBudget → 主体结合本轮情绪、驱力与既有认识形成选择 → 原companion_life工具修改真实预约 → SubjectLife执行 → SubjectActions真实回执 → ActionUnderstanding/LifeUnderstandings/CategoryEvents原报告采用 → 下一正常请求重新取用 → 下一次计划与活动。

计划索引不是已执行的事实，进度自述和工具回执区分；没有情绪到行动的固定公式。控制测试在正常请求、报告采用、注册工具和生活调度入口串接“白天新状态—修改安排—取得研究材料—经验反馈—独立活动使用新方法”，不直接写入数据库最终状态。

新增模型菜单仅按需提供；普通轮最多新增一条计划索引和明确确认的精华，均计入既有材料预算。源码、模拟集成、真实模型与手机长期验证分开报告。
