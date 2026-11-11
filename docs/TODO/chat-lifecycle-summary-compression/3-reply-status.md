# 回复完成状态与耗时

## 已核对的边界

2026-11-11 用户日志的四次正常结束中，最后一段模型流结束到回合完成通知约 13–15 秒。不能把这段时间全部当成仍在等待模型：

- 第一轮 22:23:41.748 流结束，22:23:44.090 完成私有状态回报处理，22:23:47.965 保存消息并清理发送 runtime，22:23:56.323 才发出回合完成通知。
- 其他正常轮次的状态回报处理约 1.5–1.9 秒；保存消息后还有约 8 秒的下一轮窗口预估。
- runtime 清理后已经发布终态，因此这约 8 秒会拖延统计和回合回调，不能据此断言它一直维持输入框 loading。
- 同一日志模型首段响应有 66 秒、127 秒的真实上游等待；本次客户端修复不能把它声称为已消除。

## 最小修正

1. EnhancedAIService 的输入状态绑定发送 execution。取消、外部准备状态和新一轮获得控制权后，旧流的 Receiving、Completed、Error 都不能覆盖新轮状态。子任务没有前台状态发布权，其失败仍正常向调用方抛出。
2. 移除 ChatViewModel 将全局服务状态写入“当前选中窗口”的旧监听。普通发送已由 MessageProcessingDelegate 按 chat 监听，摘要和其他本地操作也各自按 chat 设置状态；全局状态没有窗口归属，重放 Processing/Receiving 会让已空闲窗口显示忙碌。
3. 最终正文和工具链结束后、持久化私有状态回报期间显示“正在保存回复”。仍等待实际持久化和发送 runtime 清理，不能把工具执行、错误或未保存消息伪装成成功。
4. 去掉工具续答前专为展示状态而添加的固定 300 毫秒等待；工具执行及结果处理顺序不变。
5. 取消操作同步捕获当前发送及功能模型使用的 provider 并取消，不再另外排队执行全服务取消。否则旧 sendJob 已退出后，迟到的取消任务仍可能打断同一 provider 的下一轮。摘要和摘要审阅使用的功能调用也登记在当前请求集合中，保留其可取消能力。

## 窗口预估的耗时证据

正常 prepareConversationHistory 约 9.4–15.5 秒；结束后的 estimateRequestWindowFromMemory 会再次完整 compose 下一轮提示词。ConversationService 在系统提示构建后调用 CompanionContext.compose，其中包含实时角色状态投影、近期记录、资料标题与工具能力。记录中的约 8 秒间隔落在这一构建阶段，但没有足够细粒度日志把所有耗时归因于某一个数据库查询。

不能把旧 preparedHistory 当成下一轮精确上下文：这一轮已提交状态回报，下一条输入也会改变动态材料选择。完整预估作为后台任务，保持 publishEstimate=false，并在回传时检查 turn 归属。回合结束先保存 immutable TurnTokenSnapshot、同步记账并发完成回调；预估只更新当前窗口，不再次累加 token 或再次通知回合完成。新发送、取消和摘要开始撤销旧预估任务。后台窗口刷新不另行排队写聊天累计统计，避免旧窗口值与新轮统计交叉覆盖；正常聊天保存仍带上当前窗口值。

## 验证范围

- ExecutionInputStateTest：旧轮错误/完成不能覆盖新轮工具状态，取消与新准备状态撤销旧轮权限，子任务无前台权限，结束后迟到回调不能恢复忙碌。
- EnhancedReportIsolationTest.subtaskFailureDoesNotReplaceForegroundInputState：实际 EnhancedAIService 子任务传输失败仍是失败，前台状态不被污染，无未捕获异常。
- EnhancedReportIsolationTest.cancellationReachesChatAndFunctionProvidersBeforeReturning：普通发送及功能调用在 cancelConversation 返回前已经取消 provider，没有会在后续轮次执行的取消任务。
- EnhancedReportIsolationTest.preparationFailureReleasesBusyStateAndReportsItsActualCause：构建请求前读取历史失败，收到原始失败原因、释放 busy，下一条正常发送成功。
- EnhancedReportIsolationTest.cancelledWakeSignalsFailureAndTheNextUserTurnCanRun：主动唤醒等待模拟 provider 时被取消，只发一次取消终态，不计成功回合，紧接用户消息能够完成。
- TokenStatisticsDelegateTest：已捕获 token 的记账不读取下一轮已经重置的 service；后台窗口刷新不重复累加用量，也不覆盖另一个选中窗口。
- 发布门禁负责运行定向测试及编译；本页不预填尚未执行的通过结果。
