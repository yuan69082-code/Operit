# 睡眠、活动交接与经验续接：实现与验证

基线为 main `2d6b9bdc3fbe1e6dc8e2ab1d5bca488bd0a934a7`（shared +195），施工 PR 为 [#71](https://github.com/yuan69082-code/Operit/pull/71)。最终验证和发布证据完成后填写，不把推送当成发布。

## 复用与边界

继续使用 InternalStateStore 的助手归属、当前版本、原子报告、SQLite 增量历史、跨轮候选、统一材料预算、操作回执、书房和原备份。旧人格全文、48 小时导入期限、求生欲规则及 +195 修复保留。没有第二人格、外接主体、记忆库或任务平台。

只读参考 continuity-engine `0567ba4` 的 domain/dreaming.py、domain/sleep_input.py、services/dream_process_service.py，采用梦/现实隔离、有限参与过程及睡中输入暂存边界，适配多助手和手机。不照搬固定队列总量或周期生成。三份指定文档本地存在，本轮读取相关部分；引擎与文档没有修改。

## 睡眠与事件

SubjectRestPolicy / SubjectRestDreams 扩展原 rest：旧 resting 仍为安静休息，不制造历史睡眠。新的 sleep 必须有未来结束时间或真正可受理的来源条件。延长保留开始时间及会话身份；醒来保存实际结束与先前预计结束。

SubjectLife.next / CompanionRuntime 在既有后台执行位置运行一次到期续接。到期不伪造 awake，正常报告选择醒来或延长。无有效来源则不调用模型，无闲置兜底。首页“此刻”及私域“内部状态 → 休息”复用 SubjectLifePanel，读取真实请求和休息状态。到期未续接显示“休息时段已结束，等待续接”。打开页面不创建模型；“请求醒来或续接”才是用户明确的新动作。

life_event 以原触发稳定身份保存原文、来源窗口与时间；重投不新增。分页/按 ID 查询及轮换材料使用原归档，提供不等于已处理，明确 ack 后才标记，之后仍可查询。未选中的通知、批注和普通消息仅暂存。wake_sources/interruption 允许已约定来源的一次有界判断，不以关键词或分数规定心理反应。同一休息版本不反复判断小事；新休息决定可调整条件。

CompanionSchedule.defer 与探索 interrupted_plan 保留原预约、目的和顺延事实，不记为完成。醒后由主体恢复或重新安排，不集中补跑过期事项；不改外界事件时间或外部日历。进程停止时不承诺准点运行；恢复沿原绝对期限，不重造开始时间或补造经历。

## 消息与活动交接

MessageCoordinationDelegate 先把输入写入 SubjectMessageInbox，包括助手、窗口、原时间、附件及引用，再等待后台释放执行位置。忙不等于丢弃消息。睡中排队可在首页查看；实际发送仍走原 MessageProcessingDelegate、共享流及聊天存储。

life_activity 保存同一活动的方向、工具预算、最后请求版本和实际材料。助手在回复中可通过 companion_life activity 选择 continue/pause/end。continue 在前台让出位置后接同一活动，先核对随记及回执，不从头重放，也不声称恢复半个 token。恢复请求使用新片段身份，旧随记保留。

中断后的 sending/saved 输入为结果未知，不自动重发。发送失败且已有工具请求或回复内容时不提供整轮盲重试；先核对已保存事实。纯失败输入可由用户明确重试。未启用机制的助手、群聊和通话不被强行换成这个队列。

## 参与梦与醒后

正常 rest 报告的可选 dream_plan 安排一次梦，没有安排则不增加调用。ParticipatoryDreams 使用助手当前 CHAT 模型。环境只呈现当前场景/后果，参与者看有界自身材料与当前片段，不预知整个剧情。愉快、恐惧、混合、日常、跳跃和嵌套均可达，无固定比例或情绪映射。

安排 1–6 次环境/参与交互，最多 2–12 次模型调用，可提前结束；过程最多 10 分钟且不超过预计休息结束，单步输出有资源上限。双方都用无工具 function-model 通道，梦中工具语法不执行现实动作。醒后决定仍走原权限与工具。

片段与主体材料共享本次预算，默认 2048、最高 16384，完整模型窗口另外核对。此梦通道采用现有 UTF-8 保守估算，不称精确分词；真实 tokenizer、供应商协议开销及计费仍存在估算误差。当前未选材料不删除，保存原文不因预算截断。

dream_step 保存请求、完整段落与中断片段，dream_process 保存进度。网络错误/到期/被打断保留 partial/interrupted，不虚构结束；在步骤边界让出位置。重启不自动重做；明确 resume 接下一步，结果不完整的步骤须明确 retry_step。已完成步骤保留版本。

正常后续请求读取梦来源，原梦、梦中剧情、醒后随记与新理解分别保存。waking_note 仍为私域主要展示，可只有印象或感觉，不要求完整故事。dream_understanding 可引用参与过程，余波仍经正常报告，稳定人格等不越过采用规则。私域查看不等于聊天分享，不默认外发 MCP。

## 经验、目的与实际使用

LifeUnderstandings 在原报告/归档增加 experience/direction，保存实际证据、全文、范围、不确定性、状态、短/长期留存和可选复查时间。复查不删除事实或自动预约。目的、目标、投入限度和长远方向分别描述，不加总为驱力值。

已有工具续轮可立即读取持久回执并形成理解；没有续轮则留到下次，不为反馈强加调用。InnerStructureContext 在同一预算轮换材料，可按 ID 取全文。经验和方向可作为联合成员、决定及操作 intention_refs，严格引用实际提供版本；修订保留旧版，技术成功不自动满足，失败不自动放弃。稳定人格/关系仍走 inner。

整理可由原自主阅读/探索安排选择 organize，或休息中明确安排一次 consolidation；不要求每次睡眠整理，不遍历全部历史。材料仍来自本地记忆、书房、MCP 与原工具，没有新记忆分析服务。

## 摘要与稳定性修复

AssistantRoom.summary_user_address 按助手保存称呼，私域“人格与自我 → 对话回顾称呼”与 companion_room summary_address 共用。ConversationService 草稿及 SummaryReviewer 审阅使用相同约定，移除冲突的默认称呼要求。第三方、群聊与原始记录不替换；缺称呼不中断压缩，不新增调用，原忠实性审阅保留。

通话确认的代码缺陷是录音或播放关闭异常会阻断其余清理，可能留下麦克风订阅或运行标记。VoiceCallCleanup 逐项释放并返回各项错误；控制器在挂断/取消/失败后继续清理录音订阅、播放、摄像头及运行标记。没有关闭通话或吞掉所有异常。实际音频/蓝牙/系统后台仍需要设备证据。

## 存储与时序

技术记录使用原 category_current/category_versions，归档类别身份由宿主绑定，历史不进入热快照无限数组。分页/按 ID 读取，原完整备份包含数据库与房间配置。不改包名、签名或数据目录。

本轮读取旧版本与实际材料；工具请求/回执及时保存；完整主观报告在业务轮末原子采用，新状态供后续请求使用。材料送达不等于主体已理解；非法报告保留原状态与已完成动作，不重跑整轮。不能宣传正文使用了尚未提交的新版本。

## 验证记录与界限

- SubjectRestPolicyTest：兼容、结束条件、时钟到期呈现、来源及梦类型可达。
- SubjectLifeEntryPointsTest：正常报告/材料/归档、事件去重和轮换、输入恢复、默认预算梦、部分失败和步骤打断、同活动随记及工具预算。
- EnhancedReportIsolationTest：真实 EnhancedAIService → 共享流 → MessageProcessingDelegate → 保存 → 下次读取；模型响应为受控替身。覆盖休息/醒来/经验、工具后失败不盲重试；保留 HTTP 400/402、报告、工具续轮和取消回归。
- SubjectActionEntryPointsTest：实际执行器回执 → 有来源经验/方向 → 下一轮引用 → 实际操作 → 新方向版本。测试内容不证明模型会选择指定心理结果。
- SubjectRestDreamsEntryPointsTest：原 RawSnapshotBackupManager 实际导出/恢复原梦、新梦过程/步骤、事件、输入、称呼及原导入期限，再从正常工具读取来源。
- PrivateInnerStateNativeTest：Android Compose + Robolectric 原生图形，实际报告后读取睡眠状态并生成 native-life-sleep-arrangement.png；不是物理手机实测。
- SummaryAddressTest / VoiceCallCleanupTest：称呼隔离、原文及缺省可用，多项清理失败仍释放其余资源。

当前等待最终 Android 全套测试/打包及 shared 双端发布验证。此前构建暴露字符串插值、帮助函数变量、协程声明三项编译错误，已经修正；修复提交不能代替 CI 结果。Windows 先前检查与打包成功，最终提交仍须独立通过。

真实账号模型完整过程、实际 MCP、物理手机录音/播放/蓝牙、系统后台准时性与长期运行尚未实测。书房整书扫描原限制保留。不据此宣称意识、真实心理质量或长期稳定已经得到证明。
