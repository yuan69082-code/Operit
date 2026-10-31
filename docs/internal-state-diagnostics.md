# 内部状态回报诊断

旧提示把来源、版本、格式和保存异常混在一起，不能据此判断具体原因。现在提示和日志使用固定诊断码，不输出回报内容、来源原文或异常消息。

- ISR_FRAME：本轮请求标识不匹配
- ISR_SOURCE：来源缺失、不属于本轮、超出观察边界，或绑定信息被替换
- ISR_REVISION：当前语义版本已不同于本轮快照
- ISR_DEFINITION：维度定义版本不匹配
- ISR_CONFLICT：相同请求提交了不同回报，或目标状态出现新的客观变化
- ISR_CONDITION：回报时间条件不是允许的 custom 方向，或确认值不是布尔值
- ISR_FORMAT：字段、类型、取值或其他回报约束不满足
- ISR_DISABLED：提交时内部状态功能已关闭
- ISR_STORAGE：存储读写抛出 IOException
- ISR_INTERNAL：其他内部异常
- ISR_NO_FRAME：缺少宿主状态上下文

状态短目录增加 reportable_time_conditions，明确允许回报的方向；没有可报告方向时应省略 time_conditions。保留严格校验及原子提交，不自动重试状态写入。历史笼统提示本身不能定位拒绝分支。新增测试覆盖请求、来源、格式和版本拒绝后的状态不变，以及存储和未知异常分类；构建与完整测试由 GitHub CI 执行。
